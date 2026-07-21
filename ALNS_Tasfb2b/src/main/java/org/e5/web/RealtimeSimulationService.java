package org.e5.web;

import org.e5.config.OperationParameters;
import org.e5.db.FlightPlanService;
import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.parser.ShipmentParser;
import org.e5.planner.ALNS;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servicio de simulación por lotes y tiempo real para TASF.B2B.
 *
 * ── Escenario SIMULACION_LOTES ────────────────────────────────────────────────
 *
 *   El período total de 5 días se consume con planificación programada fija:
 *   cada Sa real se ejecuta el ALNS y se consumen Sc = K * Sa minutos simulados.
 *
 *   El backend en advance() hace exactamente:
 *     1. Identifica los envíos y vuelos de la ventana [tick, tick+steps).
 *     2. Ejecuta el ALNS para ese lote (una sola vez, parámetros robustos).
 *     3. Mide Ta, el tiempo real de ejecución del planificador.
 *     4. Devuelve el snapshot con tick avanzado y los valores Sa, K, Sc y Ta.
 *
 *   Así las líneas de tiempo de ejecución y carga de paquetes avanzan a la par:
 *     inicio ejecución n → ALNS corre durante Ta → UI anima hasta inicio + Sa
 *     inicio ejecución n+1 = inicio ejecución n + Sa, siempre que Ta < Sa.
 *
 *   El frontend usa visualStartedAt + Sa para pedir el siguiente lote.
 *
 * ── Cancelación de vuelos futuros ─────────────────────────────────────────────
 *
 *   cancelFlight() rechaza si el vuelo ya despegó (tick actual ≥ salida).
 *   Replanifica los envíos afectados directamente y luego hace una
 *   reoptimización global de todos los ya planificados para aprovechar la
 *   capacidad liberada y garantizar plazos de 1/2 días.
 */
public class RealtimeSimulationService {
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ── Parámetros de temporización del lote (simulación incremental) ────────
    private static final long MINUTE_MS = 60_000L;
    private static final int SIMULATION_PLANNING_INTERVAL_MINUTES = readPositiveInt("TASF_SIMULATION_SA_MINUTES", 2);
    private static final int SIMULATION_PLANNING_K = readPositiveInt("TASF_SIMULATION_K", 90);
    /** Sc de simulacion: minutos simulados consumidos en cada ejecucion. */
    public static final int BATCH_MINUTES = readPositiveInt(
            "TASF_SIMULATION_SC_MINUTES",
            SIMULATION_PLANNING_INTERVAL_MINUTES * SIMULATION_PLANNING_K);
    /** Sa de simulacion: milisegundos reales entre inicios de ejecucion. */
    public static final long BATCH_INTERVAL_MS = readPositiveLong(
            "TASF_SIMULATION_SA_MS",
            SIMULATION_PLANNING_INTERVAL_MINUTES * MINUTE_MS);
    /** Escenario de colapso: 12 horas simuladas por cada ventana visual de 2 minutos. */
    public static final int COLLAPSE_WINDOW_MINUTES = readPositiveInt(
            "TASF_COLLAPSE_SC_MINUTES", 12 * 60);
    public static final long COLLAPSE_INTERVAL_MS = readPositiveLong(
            "TASF_COLLAPSE_SA_MS", 2 * MINUTE_MS);
    private static final int BATCH_ALNS_MIN_ITERATIONS = readPositiveInt(
            "TASF_BATCH_ALNS_MIN_ITERATIONS", 35);
    private static final int BATCH_ALNS_MAX_ITERATIONS = readPositiveInt(
            "TASF_BATCH_ALNS_MAX_ITERATIONS", 120);
    private static final int BATCH_ALNS_ITERATIONS_PER_SHIPMENT = readPositiveInt(
            "TASF_BATCH_ALNS_ITERATIONS_PER_SHIPMENT", 2);
    private static final int BATCH_ALNS_DESTROY_CAP = readPositiveInt(
            "TASF_BATCH_ALNS_DESTROY_CAP", 30);
    private static final int BATCH_ALNS_MAX_ESCALAS = readPositiveInt(
            "TASF_BATCH_ALNS_MAX_ESCALAS", 2);
    private static final long BATCH_ALNS_TIME_BUDGET_MS = readPositiveLong(
            "TASF_BATCH_ALNS_TIME_BUDGET_MS",
            Math.max(10_000L, Math.round(BATCH_INTERVAL_MS * 0.75)));
    private static final int BATCH_BACKLOG_MAX_SHIPMENTS = readPositiveInt(
            "TASF_BATCH_BACKLOG_MAX_SHIPMENTS", 1_500);
    private static final int BATCH_BACKLOG_MAX_BAGS = readPositiveInt(
            "TASF_BATCH_BACKLOG_MAX_BAGS", 4_500);
    private static final int BATCH_PREVENTIVE_LOAD_THRESHOLD_PERCENT = readPositiveInt(
            "TASF_BATCH_PREVENTIVE_LOAD_THRESHOLD_PERCENT", 75);
    private static final int BATCH_PREVENTIVE_TOP_AIRPORTS = readPositiveInt(
            "TASF_BATCH_PREVENTIVE_TOP_AIRPORTS", 6);
    private static final boolean BATCH_CANCEL_GLOBAL_REOPT = readBoolean(
            "TASF_BATCH_CANCEL_GLOBAL_REOPT", false);
    private static final int MAX_DELIVERY_DEADLINE_MINUTES = 2880;
    private static final int BATCH_SNAPSHOT_TRAILING_MINUTES = readPositiveInt(
            "TASF_BATCH_SNAPSHOT_TRAILING_MINUTES", 360);
    private static final int BATCH_SNAPSHOT_LOOKAHEAD_MINUTES = readPositiveInt(
            "TASF_BATCH_SNAPSHOT_LOOKAHEAD_MINUTES", 180);
    private static final int BATCH_SNAPSHOT_MAX_SHIPMENTS = readPositiveInt(
            "TASF_BATCH_SNAPSHOT_MAX_SHIPMENTS", 300);
    private static final int BATCH_SNAPSHOT_MAX_EVENTS = readPositiveInt(
            "TASF_BATCH_SNAPSHOT_MAX_EVENTS", 6_000);

    // ── Parámetros del loop de tiempo real ────────────────────────────────────
    private static final int INTERVALO_TICK    = 1;
    private static final int UMBRAL_COLA_REPLAN = 20;
    private static final int REALTIME_PLANNING_INTERVAL_MINUTES = 2;
    private static final int REALTIME_PLANNING_K = 1;
    private static final int REALTIME_PLANNING_WINDOW_MINUTES =
            REALTIME_PLANNING_INTERVAL_MINUTES * REALTIME_PLANNING_K;
    private static final long REALTIME_EXECUTION_INTERVAL_MS =
            REALTIME_PLANNING_INTERVAL_MINUTES * MINUTE_MS;
    private static final long REALTIME_SHIPMENT_REFRESH_MS = readPositiveLong("TASF_REALTIME_SHIPMENT_REFRESH_MS", 30_000L);
    private static final long REALTIME_SCHEDULER_POLL_MS = readPositiveLong("TASF_REALTIME_SCHEDULER_POLL_MS", 1_000L);

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
    private final FlightPlanService flightPlanService = new FlightPlanService();
    private final RuntimeCatalogService catalogService = new RuntimeCatalogService();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tasf-simulation-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String sharedRealtimeSessionId;
    private volatile String sharedSimulationSessionId;
    private volatile String sharedCollapseSessionId;
    private volatile String lastCreatedSessionId;

    public RealtimeSimulationService() {
        scheduler.scheduleWithFixedDelay(
                this::advanceSharedSessionsIfDue,
                REALTIME_SCHEDULER_POLL_MS,
                REALTIME_SCHEDULER_POLL_MS,
                TimeUnit.MILLISECONDS);
    }

    private static int readPositiveInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long readPositiveLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0L ? value : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  API pública
    // ════════════════════════════════════════════════════════════════════════

    /** Inicia una sesión de tiempo real clásica. */
    public String start(String startDate, int days) throws Exception {
        return start(startDate, days, ZoneId.systemDefault().getId());
    }

    private static boolean readBoolean(String name, boolean defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    public synchronized String start(String startDate, int days, String timeZone) throws Exception {
        return start(startDate, days, "00:00", timeZone);
    }

    public synchronized String start(String startDate, int days, String startTime, String timeZone) throws Exception {
        validate(startDate, days, false);
        RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
        if (current != null && !current.completed) return current.snapshotJsonForRead();
        int startOffsetMinutes = parseStartTime(startTime);
        String json = createSession(startDate, days, "TIEMPO_REAL", startOffsetMinutes, timeZone);
        sharedRealtimeSessionId = lastCreatedSessionId;
        return json;
    }

    public synchronized String startAtCurrentTime(int days, String timeZone) throws Exception {
        ZoneId zone = resolveZone(timeZone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        String currentDate = now.format(RAW_DATE);
        String currentTime = "%02d:%02d".formatted(now.getHour(), now.getMinute());
        return start(currentDate, days, currentTime, zone.getId());
    }

    public String currentRealtime() {
        RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
        return current == null ? "{}" : current.snapshotJsonForRead();
    }

    public synchronized String pauseRealtime(String id, boolean paused) {
        RealtimeSession session = require(id);
        if (!"TIEMPO_REAL".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es una operacion de tiempo real.");
        }
        session.setPaused(paused);
        return session.snapshotJson();
    }

    private void advanceSharedSessionsIfDue() {
        advanceSharedRealtimeIfDue();
        advanceSharedBatchIfDue();
        advanceSharedCollapseIfDue();
    }

    private void advanceSharedRealtimeIfDue() {
        try {
            RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
            if (current == null || current.completed || current.paused) return;
            current.advanceRealtimeIfDue();
        } catch (Exception e) {
            System.err.printf("[Tiempo real] Error en scheduler: %s%n", e.getMessage());
        }
    }

    private void advanceSharedBatchIfDue() {
        try {
            RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
            if (current == null || current.completed) return;
            current.advanceBatchIfDue();
        } catch (Exception e) {
            System.err.printf("[Lote] Error en scheduler: %s%n", e.getMessage());
        }
    }

    private void advanceSharedCollapseIfDue() {
        try {
            RealtimeSession current = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
            if (current == null || current.completed || current.paused) return;
            current.advanceBatchIfDue();
        } catch (Exception e) {
            System.err.printf("[Colapso] Error en scheduler: %s%n", e.getMessage());
        }
    }

    public void syncRegisteredShipmentsFromDatabase() {
        RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
        if (current != null && !current.completed) {
            current.syncRegisteredShipmentsFromDatabase();
        }
    }

    /**
     * Inicia una sesión de simulación por lotes.
     * El ALNS se ejecuta una vez por lote de BATCH_MINUTES minutos simulados.
     * advance() controla el tiempo real para que cada lote dure exactamente
     * BATCH_INTERVAL_MS milisegundos desde la perspectiva del frontend.
     */
    public String startBatchSimulation(String startDate, int days) throws Exception {
        return startBatchSimulation(startDate, days, "00:00", ZoneId.systemDefault().getId());
    }

    /**
     * Inicia simulación por lotes con hora de inicio opcional (HH:mm).
     * El tick inicial queda en ese offset; cada lote cubre {@link #BATCH_MINUTES} minutos.
     */
    public String startBatchSimulation(String startDate, int days, String startTime) throws Exception {
        return startBatchSimulation(startDate, days, startTime, ZoneId.systemDefault().getId());
    }

    public synchronized String startBatchSimulation(String startDate, int days, String startTime,
                                                   String timeZone) throws Exception {
        return startBatchSimulation(startDate, days, startTime, timeZone, "", "");
    }

    public synchronized String startBatchSimulation(String startDate, int days, String startTime,
                                                   String timeZone, String clientId,
                                                   String controlToken) throws Exception {
        validate(startDate, days, true);
        RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        if (current != null && !current.completed) {
            requireBatchControl(current, clientId, controlToken);
            current.completed = true;
            sessions.remove(current.id);
        } else if (current != null) {
            sessions.remove(current.id);
        }
        int startOffsetMinutes = parseStartTime(startTime);
        String ownerClientId = normalizeClientId(clientId);
        String json = createSession(startDate, days, "SIMULACION_LOTES", startOffsetMinutes, timeZone, ownerClientId);
        sharedSimulationSessionId = lastCreatedSessionId;
        RealtimeSession created = sessions.get(sharedSimulationSessionId);
        return created == null ? json : created.snapshotJson(true);
    }

    public String currentSimulation() {
        RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        return current == null ? "{}" : current.snapshotJsonForRead();
    }

    public synchronized String stopBatchSimulation(String id) {
        return stopBatchSimulation(id, "", "");
    }

    public synchronized String stopBatchSimulation(String id, String clientId, String controlToken) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            if (id != null && id.equals(sharedSimulationSessionId)) {
                sharedSimulationSessionId = null;
            }
            return "{}";
        }
        if (!"SIMULACION_LOTES".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es una simulacion por lotes.");
        }
        if (!session.completed) {
            requireBatchControl(session, clientId, controlToken);
        }

        sessions.remove(id);
        if (id.equals(sharedSimulationSessionId)) {
            sharedSimulationSessionId = null;
        }
        return "{}";
    }

    public synchronized String pauseBatchSimulation(String id, boolean paused,
                                                    String clientId, String controlToken) {
        RealtimeSession session = require(id);
        if (!"SIMULACION_LOTES".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es una simulación por lotes.");
        }
        requireBatchControl(session, clientId, controlToken);
        session.setPaused(paused);
        return session.snapshotJson();
    }

    public String state(String id) {
        RealtimeSession session = sessions.get(id);
        if (session != null) return session.snapshotJsonForRead();
        RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        return current == null ? "{}" : current.snapshotJsonForRead();
    }

    /**
     * Avanza la simulación en {@code steps} minutos simulados.
     *
     * Para SIMULACION_LOTES:
     *   – Ejecuta el ALNS una sola vez para todo el lote y responde inmediatamente.
     *   – El frontend controla el ritmo: anima la UI durante el tiempo real del lote
     *     y llama a /advance de nuevo al terminar la animación.
     *
     * Para TIEMPO_REAL:
     *   – Avanza tick a tick como antes.
     */
    public String advance(String id, int steps) {
        return advance(id, steps, -1);
    }

    public String advance(String id, int steps, int expectedTick) {
        return advance(id, steps, expectedTick, "", "");
    }

    public String advance(String id, int steps, int expectedTick, String clientId, String controlToken) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
            return current == null ? "{}" : current.snapshotJsonForRead();
        }
        synchronized (session) {
            if ("SIMULACION_LOTES".equals(session.scenario)) {
                requireBatchControl(session, clientId, controlToken);
                if (expectedTick >= 0 && session.tick != expectedTick) return session.snapshotJsonForRead();
                session.advanceBatch(BATCH_MINUTES);
            } else if ("TIEMPO_REAL".equals(session.scenario)) {
                session.advanceRealtimeIfDue();
            } else {
                throw new IllegalArgumentException("Use el endpoint propio del escenario de colapso.");
            }
            return session.snapshotJson();
        }
    }

    /**
     * Cancela un vuelo futuro y replanifica.
     * Rechaza con excepción si el vuelo ya despegó en el tick actual.
     */
    public String cancelFlight(String id, String flightId) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            session = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        }
        if (session == null) throw new IllegalArgumentException("Sesión no encontrada.");
        if (!"SIMULACION_LOTES".equals(session.scenario)) {
            throw new IllegalArgumentException("Las cancelaciones no están disponibles en esta sesión.");
        }
        session.cancel(flightId, flightPlanService);
        return session.snapshotJson();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internos
    // ════════════════════════════════════════════════════════════════════════

    public String batchShipments(String id, int page, int pageSize,
                                 String search, String origin,
                                 String destination, String status,
                                 int currentMinute, int historyMinutes,
                                 int departureWithinMinutes,
                                 String sortBy, String sortOrder) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            session = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        }
        if (session == null) throw new IllegalArgumentException("Sesión no encontrada.");
        if (!"SIMULACION_LOTES".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es una simulación por lotes.");
        }
        return session.shipmentsPageJson(page, pageSize, search, origin, destination, status, currentMinute, historyMinutes, departureWithinMinutes, sortBy, sortOrder);
    }

    // ── Escenario de colapso ───────────────────────────────────────────────

    /**
     * Inicia una ejecucion aislada de colapso. A diferencia de la simulacion
     * de 5 dias, esta sesión consume los TXT en ventanas de 12 horas hasta
     * hallar el primer incumplimiento.
     */
    public synchronized String startCollapseSimulation(String startDate, int days, String startTime,
                                                       String timeZone, String clientId,
                                                       String controlToken) throws Exception {
        validate(startDate, days, true);
        RealtimeSession current = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
        if (current != null) {
            if (!current.completed) {
                requireBatchControl(current, clientId, controlToken);
                throw new IllegalStateException("Ya hay un escenario de colapso en ejecución. Páusalo o cancélalo antes de iniciar otro.");
            }
            throw new IllegalStateException("El escenario anterior terminó. Cualquier máquina puede limpiarlo antes de iniciar uno nuevo.");
        }

        int startOffsetMinutes = parseStartTime(startTime);
        String ownerClientId = normalizeClientId(clientId);
        String json = createSession(startDate, days, "COLAPSO", startOffsetMinutes, timeZone, ownerClientId);
        sharedCollapseSessionId = lastCreatedSessionId;
        RealtimeSession created = sessions.get(sharedCollapseSessionId);
        return created == null ? json : created.snapshotJson(true);
    }

    public String currentCollapseSimulation() {
        RealtimeSession current = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
        return current == null ? "{}" : current.snapshotJsonForRead();
    }

    public String collapseState(String id) {
        RealtimeSession session = sessions.get(id);
        if (session != null && "COLAPSO".equals(session.scenario)) return session.snapshotJsonForRead();
        RealtimeSession current = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
        return current == null ? "{}" : current.snapshotJsonForRead();
    }

    public String advanceCollapseSimulation(String id, int expectedTick,
                                            String clientId, String controlToken) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            session = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
        }
        if (session == null) return "{}";
        if (!"COLAPSO".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es un escenario de colapso.");
        }

        synchronized (session) {
            requireBatchControl(session, clientId, controlToken);
            if (expectedTick >= 0 && session.tick != expectedTick) return session.snapshotJsonForRead();
            session.advanceBatch(COLLAPSE_WINDOW_MINUTES);
            return session.snapshotJson();
        }
    }

    public synchronized String pauseCollapseSimulation(String id, boolean paused,
                                                       String clientId, String controlToken) {
        RealtimeSession session = require(id);
        if (!"COLAPSO".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es un escenario de colapso.");
        }
        requireBatchControl(session, clientId, controlToken);
        session.setPaused(paused);
        return session.snapshotJson();
    }

    /** Solo la maquina dueña puede cancelar una ejecucion activa. */
    public synchronized String cancelCollapseSimulation(String id, String clientId, String controlToken) {
        RealtimeSession session = require(id);
        if (!"COLAPSO".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es un escenario de colapso.");
        }
        requireBatchControl(session, clientId, controlToken);
        session.cancelCollapse();
        return session.snapshotJson();
    }

    /**
     * Una ejecucion que termino o fue cancelada puede eliminarse desde cualquier
     * maquina. La sesión activa nunca se limpia por esta ruta.
     */
    public synchronized String clearCollapseSimulation(String id) {
        RealtimeSession session = sessions.get(id);
        if (session == null) return "{}";
        if (!"COLAPSO".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es un escenario de colapso.");
        }
        if (!session.completed) {
            throw new IllegalStateException("No se puede limpiar un escenario de colapso que sigue activo.");
        }
        sessions.remove(id);
        if (id.equals(sharedCollapseSessionId)) {
            sharedCollapseSessionId = null;
        }
        session.closeResources();
        return "{}";
    }

    public String collapseShipments(String id, int page, int pageSize,
                                    String search, String origin,
                                    String destination, String status,
                                    int currentMinute, int historyMinute,
                                    int departureWithinMinutes,
                                    String sortBy, String sortOrder) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            session = sharedCollapseSessionId == null ? null : sessions.get(sharedCollapseSessionId);
        }
        if (session == null) throw new IllegalArgumentException("Sesión no encontrada.");
        if (!"COLAPSO".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesión indicada no es un escenario de colapso.");
        }
        return session.shipmentsPageJson(page, pageSize, search, origin, destination, status, currentMinute, historyMinute, departureWithinMinutes, sortBy, sortOrder);
    }

    private static int parseStartTime(String startTime) {
        if (startTime == null || startTime.isBlank()) return 0;
        String[] parts = startTime.trim().split(":");
        if (parts.length != 2)
            throw new IllegalArgumentException("startTime debe tener formato HH:mm.");
        int hours;
        int minutes;
        try {
            hours = Integer.parseInt(parts[0]);
            minutes = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("startTime debe tener formato HH:mm.");
        }
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59)
            throw new IllegalArgumentException("startTime fuera de rango (00:00–23:59).");
        return hours * 60 + minutes;
    }

    private String createSession(String startDate, int days, String scenario) throws Exception {
        return createSession(startDate, days, scenario, 0, ZoneId.systemDefault().getId(), "");
    }

    private String createSession(String startDate, int days, String scenario,
                                 int startOffsetMinutes, String timeZone) throws Exception {
        return createSession(startDate, days, scenario, startOffsetMinutes, timeZone, "");
    }

    private String createSession(String startDate, int days, String scenario,
                                 int startOffsetMinutes, String timeZone,
                                 String ownerClientId) throws Exception {
        ZoneId sessionZone = resolveZone(timeZone);
        RuntimeCatalogService.RuntimeCatalog catalog =
                catalogService.loadRuntimeCatalog(startDate, days + 2);
        List<Airport> airports = catalog.airports();
        Map<String, Airport> airportMap = catalog.airportMap();
        List<Flight> flights = alignFlightsToSessionZone(catalog.flights(), startDate, sessionZone);
        flights.sort(Comparator
                .comparingInt(Flight::absoluteDepartureMinute)
                .thenComparing(Flight::getFlightId));

        ShipmentParser shipmentParser = new ShipmentParser(airportMap);
        int simulationEndMinute = startOffsetMinutes + days * 1440;
        List<Shipment> shipments;

        if ("COLAPSO".equals(scenario)) {
            // El colapso no precarga anos de datos: los TXT se consumen de forma
            // secuencial cuando se abre cada ventana de 12 horas.
            shipments = new ArrayList<>();
        } else if (days <= 5) {
            // Escenario original: carga completa (5 días)
            int shipmentDaysToLoad = startOffsetMinutes > 0 ? days + 1 : days;
            shipments = "SIMULACION_LOTES".equals(scenario)
                    ? shipmentParser.parseAll("data/envios", startDate, shipmentDaysToLoad, sessionZone)
                    : shipmentParser.parseAllFromDatabase(startDate, shipmentDaysToLoad, sessionZone);
        } else {
            // Escenario largo (> 5 días): carga inicial limitada a 5 días
            int windowDays = 5;
            int windowEndMinute = startOffsetMinutes + (windowDays * 1440);
            shipments = "SIMULACION_LOTES".equals(scenario)
                    ? shipmentParser.parseShipmentsInWindow("data/envios", startDate, startOffsetMinutes, windowEndMinute, sessionZone)
                    : shipmentParser.parseShipmentsInWindow(startDate, startOffsetMinutes, windowEndMinute, sessionZone);
        }

        shipments.removeIf(s -> s.getRequestMinute() < startOffsetMinutes
                || s.getRequestMinute() >= simulationEndMinute);
        shipments.sort(Comparator.comparingInt(Shipment::getRequestMinute));

        RealtimeSession session = new RealtimeSession(
                startDate, days, scenario, startOffsetMinutes, sessionZone,
                airports, airportMap, flights, shipments, ownerClientId);
        sessions.put(session.id, session);
        lastCreatedSessionId = session.id;
        if ("TIEMPO_REAL".equals(scenario)) {
            session.syncRegisteredShipmentsFromDatabase();
        }
        return session.snapshotJson();
    }

    private List<Flight> alignFlightsToSessionZone(
            List<Flight> flights,
            String startDate,
            ZoneId sessionZone
    ) {
        LocalDate date = LocalDate.parse(startDate, RAW_DATE);
        ZonedDateTime localStart = date.atStartOfDay(sessionZone);
        ZonedDateTime utcStart = date.atStartOfDay(ZoneOffset.UTC);
        List<Flight> aligned = new ArrayList<>(flights.size());

        for (Flight flight : flights) {
            ZonedDateTime departureUtc = utcStart.plusMinutes(flight.absoluteDepartureMinute());
            ZonedDateTime arrivalUtc = utcStart.plusMinutes(flight.absoluteArrivalMinute());
            int localDeparture = Math.toIntExact(Duration.between(
                    localStart.toInstant(),
                    departureUtc.toInstant()
            ).toMinutes());
            int localArrival = Math.toIntExact(Duration.between(
                    localStart.toInstant(),
                    arrivalUtc.toInstant()
            ).toMinutes());
            int dayOffset = Math.floorDiv(localDeparture, 1440);
            int departureMinute = Math.floorMod(localDeparture, 1440);
            int arrivalMinute = localArrival - dayOffset * 1440;

            aligned.add(new Flight(
                    flight.getFlightId(),
                    flight.getOriginCode(),
                    flight.getDestCode(),
                    departureMinute,
                    arrivalMinute,
                    flight.getMaxCapacity(),
                    dayOffset
            ));
        }

        return aligned;
    }

    private ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(timeZone.trim());
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private RealtimeSession require(String id) {
        RealtimeSession session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("Sesión no encontrada.");
        return session;
    }

    private void requireBatchControl(RealtimeSession session, String clientId, String controlToken) {
        if (session == null || !session.isBatchScenario()) return;
        if (session.controlsAllowed(clientId, controlToken)) return;
        throw new SecurityException("Solo la máquina que inició la simulación puede controlar esta sesión.");
    }

    private static String normalizeClientId(String clientId) {
        return clientId == null ? "" : clientId.trim();
    }

    private void validate(String startDate, int days, boolean batch) {
        if (startDate == null || !startDate.matches("\\d{8}"))
            throw new IllegalArgumentException("La fecha inicial debe tener formato aaaammdd.");
        LocalDate.parse(startDate, RAW_DATE);
//        if (batch) {
//            if (days != 5)
//                throw new IllegalArgumentException("Solo se permite simular 5 dias.");
//        } else {
//            if (days < 1 || days > 7)
//                throw new IllegalArgumentException("Tiempo real permite operar entre 1 y 7 dias.");
//        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Sesión
    // ════════════════════════════════════════════════════════════════════════

    private static class RealtimeSession {
        final String id = UUID.randomUUID().toString();
        final String startDate;
        final int days;
        final String scenario;
        final int startOffsetMinutes;
        final ZoneId originZone;
        final String simulationStartInstant;
        final String simulationEndInstant;
        final String ownerClientId;
        final String controlToken;
        final List<Airport> airports;
        final Map<String, Airport> airportMap;
        final List<Flight> flights;
        final Map<String, Flight> flightById;
        final List<Shipment> shipments;
        final CollapseShipmentSource collapseShipmentSource;

        // Índice por minuto para acceso O(1)
        final Map<Integer, List<Shipment>> shipmentsByMinute = new HashMap<>();

        // Estado de tiempo real
        final List<Shipment> queue     = new ArrayList<>();
        final List<Shipment> processed = new ArrayList<>();
        final List<RealtimeEvent> events = new ArrayList<>();
        final Set<String> processedFlightEvents = new HashSet<>();
        final Set<String> queuedShipmentIds = new HashSet<>();
        final Set<String> knownShipmentIds = new HashSet<>();
        final Set<String> pendingCancellations = new HashSet<>();
        final Set<String> cancellations = new HashSet<>();
        final List<CancellationRequest> queuedCancellationRequests = new ArrayList<>();
        final List<Shipment> batchPlanningBacklog = new ArrayList<>();
        final Set<String> batchPlanningBacklogIds = new HashSet<>();
        final Set<String> batchProcessedShipmentIds = new HashSet<>();
        final Set<String> batchCreatedEventShipmentIds = new HashSet<>();

        final long realStartedAtMs = System.currentTimeMillis();
        int tick;
        final int maxTick;
        final int planningMaxTick;
        boolean completed = false;
        boolean cancelled = false;
        CollapseOutcome collapseOutcome;
        volatile boolean planningInProgress = false;
        volatile String lastSnapshotJson = "{}";
        private int cachedHistoricalPeakStartMinute = Integer.MIN_VALUE;
        private int cachedHistoricalPeakBoundaryMinute = Integer.MIN_VALUE;
        private int cachedHistoricalPeakEventCount = -1;
        private Map<String, Integer> cachedHistoricalPeakLoads = Collections.emptyMap();
        int lastBatchStart = -1;
        int lastBatchEnd = -1;
        int batchCount = 0;
        long lastBatchRuntimeMs = 0;
        PlanningMetrics lastPlanningMetrics = new PlanningMetrics();
        int lastPlanningUsedFlights = 0;
        long realFinishedAtMs = 0;
        boolean paused = false;
        long lastRealtimeAdvanceMs = 0;
        long lastRealtimeExecutionMs = 0;
        long lastRealtimeShipmentRefreshMs = 0;
        long lastBatchSchedulerCheckMs = 0;
        long visualWindowStartedAtMs;
        int visualWindowStartTick;
        int visualWindowEndTick;
        int realtimeExecutionCount = 0;

        // ── Solo para SIMULACION_LOTES: solución acumulada global ─────────────
        // Guarda las rutas de todos los lotes anteriores para poder hacer
        // reoptimización global en cancelaciones.
        final Map<String, Route> solucionGlobal = new HashMap<>();
        final List<Shipment> todosLosShipmentsProcesados = new ArrayList<>();

        RealtimeSession(String startDate, int days, String scenario, int startOffsetMinutes,
                        ZoneId originZone,
                        List<Airport> airports, Map<String, Airport> airportMap,
                        List<Flight> flights, List<Shipment> shipments,
                        String ownerClientId) throws IOException {
            this.startDate  = startDate;
            this.days       = days;
            this.scenario   = scenario;
            this.startOffsetMinutes = startOffsetMinutes;
            this.originZone = originZone;
            this.ownerClientId = isBatchScenarioName(scenario) ? normalizeClientId(ownerClientId) : "";
            this.controlToken = this.ownerClientId.isBlank() ? "" : UUID.randomUUID().toString();
            this.airports   = airports;
            this.airportMap = airportMap;
            this.flights    = flights;
            this.flightById = new HashMap<>();
            for (Flight flight : flights) {
                this.flightById.putIfAbsent(flight.getFlightId(), flight);
            }
            this.shipments  = shipments;
            for (Shipment s : shipments) {
                knownShipmentIds.add(s.getShipmentId());
                shipmentsByMinute.computeIfAbsent(s.getRequestMinute(), k -> new ArrayList<>()).add(s);
            }
            this.tick = startOffsetMinutes;
            this.maxTick = startOffsetMinutes + days * 1440;
            this.planningMaxTick = isBatchScenario()
                    ? this.maxTick + MAX_DELIVERY_DEADLINE_MINUTES
                    : this.maxTick;
            this.collapseShipmentSource = isCollapseScenario()
                    ? new CollapseShipmentSource(startDate, this.maxTick, airportMap, originZone)
                    : null;
            this.visualWindowStartedAtMs = System.currentTimeMillis();
            this.visualWindowStartTick = startOffsetMinutes;
            this.visualWindowEndTick = startOffsetMinutes;
            ZonedDateTime simulationStart = LocalDate.parse(startDate, RAW_DATE)
                    .atStartOfDay(originZone)
                    .plusMinutes(startOffsetMinutes);
            this.simulationStartInstant = simulationStart.toInstant().toString();
            this.simulationEndInstant = simulationStart.plusDays(days).toInstant().toString();
        }

        private boolean isBatchScenario() {
            return "SIMULACION_LOTES".equals(scenario) || isCollapseScenario();
        }

        private static boolean isBatchScenarioName(String scenario) {
            return "SIMULACION_LOTES".equals(scenario) || "COLAPSO".equals(scenario);
        }

        private boolean isCollapseScenario() {
            return "COLAPSO".equals(scenario);
        }

        private boolean controlsAllowed(String clientId, String token) {
            if (!isBatchScenario() || ownerClientId.isBlank()) return true;
            return ownerClientId.equals(normalizeClientId(clientId))
                    && controlToken.equals(token == null ? "" : token.trim());
        }

        private long planningIntervalMs() {
            if (isCollapseScenario()) return COLLAPSE_INTERVAL_MS;
            return isBatchScenario() ? BATCH_INTERVAL_MS : REALTIME_EXECUTION_INTERVAL_MS;
        }

        private int planningIntervalMinutes() {
            return Math.max(1, (int) Math.round(planningIntervalMs() / (double) MINUTE_MS));
        }

        private int planningTimeScale() {
            if (isCollapseScenario()) {
                return Math.max(1, (int) Math.round(COLLAPSE_WINDOW_MINUTES
                        / (COLLAPSE_INTERVAL_MS / (double) MINUTE_MS)));
            }
            return isBatchScenario() ? SIMULATION_PLANNING_K : REALTIME_PLANNING_K;
        }

        private int planningWindowMinutes() {
            if (isCollapseScenario()) return COLLAPSE_WINDOW_MINUTES;
            return isBatchScenario() ? BATCH_MINUTES : REALTIME_PLANNING_WINDOW_MINUTES;
        }

        private boolean planningStable() {
            return lastBatchRuntimeMs == 0 || lastBatchRuntimeMs < planningIntervalMs();
        }

        private boolean plannerFastMode() {
            return lastBatchRuntimeMs > 0
                    && lastBatchRuntimeMs >= Math.round(planningIntervalMs() * 0.80);
        }

        private Map<String, Double> calcularPrioridadPreventivaOrigen(
                List<Shipment> nuevosShipments,
                List<Shipment> backlogDisponible,
                int batchStart) {
            Map<String, Integer> cargaActual = calcularCargaAeropuertosHasta(batchStart);
            Map<String, Integer> maletasNuevas = sumarMaletasPorOrigen(nuevosShipments);
            Map<String, Integer> maletasBacklog = sumarMaletasPorOrigen(backlogDisponible);
            double umbral = Math.min(0.95,
                    Math.max(0.50, BATCH_PREVENTIVE_LOAD_THRESHOLD_PERCENT / 100.0));

            List<Map.Entry<String, Double>> prioridades = new ArrayList<>();
            for (Airport airport : airports) {
                int capacidad = Math.max(1, airport.getMaxCapacity());
                int proyectado = cargaActual.getOrDefault(airport.getCode(), 0)
                        + maletasNuevas.getOrDefault(airport.getCode(), 0);
                double utilizacionProyectada = proyectado / (double) capacidad;
                double presion = Math.max(0.0, (utilizacionProyectada - umbral) / (1.0 - umbral));
                double backlogRatio = maletasBacklog.getOrDefault(airport.getCode(), 0) / (double) capacidad;
                double prioridad = Math.min(4.0, presion + Math.min(1.5, backlogRatio));
                if (prioridad > 0.0) {
                    prioridades.add(Map.entry(airport.getCode(), prioridad));
                }
            }

            prioridades.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<String, Double> resultado = new HashMap<>();
            int limite = Math.min(BATCH_PREVENTIVE_TOP_AIRPORTS, prioridades.size());
            for (int i = 0; i < limite; i++) {
                resultado.put(prioridades.get(i).getKey(), prioridades.get(i).getValue());
            }
            return resultado;
        }

        private List<Shipment> seleccionarBacklogPreventivo(
                List<Shipment> backlogDisponible,
                int batchStart,
                Map<String, Double> prioridadOrigen) {
            if (backlogDisponible.isEmpty()) return Collections.emptyList();

            List<Shipment> ordenados = new ArrayList<>(backlogDisponible);
            ordenados.sort(Comparator
                    .comparingDouble((Shipment s) ->
                            -prioridadOrigen.getOrDefault(s.getOriginCode(), 0.0))
                    .thenComparingInt(s -> tiempoRestanteSla(s, batchStart))
                    .thenComparing(Comparator.comparingInt(Shipment::getSuitcaseCount))
                    .thenComparingInt(Shipment::getRequestMinute));

            List<Shipment> seleccionados = new ArrayList<>();
            int maletas = 0;
            for (Shipment shipment : ordenados) {
                if (seleccionados.size() >= BATCH_BACKLOG_MAX_SHIPMENTS) break;
                if (!seleccionados.isEmpty()
                        && maletas + shipment.getSuitcaseCount() > BATCH_BACKLOG_MAX_BAGS) {
                    break;
                }
                seleccionados.add(shipment);
                maletas += Math.max(0, shipment.getSuitcaseCount());
            }
            return seleccionados;
        }

        private Map<String, Integer> calcularCargaAeropuertosHasta(int minute) {
            Map<String, Integer> cargas = new HashMap<>();
            for (RealtimeEvent event : events) {
                if (event.minute() > minute) continue;
                if (!airportMap.containsKey(event.airport())) continue;
                cargas.merge(event.airport(), event.delta(), Integer::sum);
            }
            cargas.replaceAll((ignored, value) -> Math.max(0, value));
            return cargas;
        }

        private Map<String, Integer> sumarMaletasPorOrigen(Collection<Shipment> source) {
            Map<String, Integer> result = new HashMap<>();
            for (Shipment shipment : source) {
                if (shipment.isSplitPart()) continue;
                result.merge(shipment.getOriginCode(),
                        Math.max(0, shipment.getSuitcaseCount()),
                        Integer::sum);
            }
            return result;
        }

        private int tiempoRestanteSla(Shipment shipment, int batchStart) {
            Airport origin = airportMap.get(shipment.getOriginCode());
            Airport destination = airportMap.get(shipment.getDestCode());
            int deadline = Shipment.getDeadlineMinutes(
                    origin != null ? origin.getContinent() : "",
                    destination != null ? destination.getContinent() : "");
            return shipment.getRequestMinute() + deadline - batchStart;
        }

        private void reencolarBacklogBatch(Shipment shipment) {
            if (shipment == null) return;
            if (batchPlanningBacklogIds.add(shipment.getShipmentId())) {
                batchPlanningBacklog.add(shipment);
            }
        }

        private int currentVisualTick(long nowMs) {
            if (visualWindowEndTick <= visualWindowStartTick) return visualWindowEndTick;
            long elapsed = Math.max(0L, nowMs - visualWindowStartedAtMs);
            double progress = Math.min(1.0, elapsed / (double) Math.max(1L, planningIntervalMs()));
            return (int) Math.round(visualWindowStartTick
                    + (visualWindowEndTick - visualWindowStartTick) * progress);
        }

        synchronized void setPaused(boolean nextPaused) {
            if (completed || paused == nextPaused) return;
            long now = System.currentTimeMillis();
            int visualTick = Math.min(tick, currentVisualTick(now));
            if (nextPaused) {
                paused = true;
                visualWindowStartTick = visualTick;
                visualWindowEndTick = visualTick;
                visualWindowStartedAtMs = now;
                events.add(new RealtimeEvent(visualTick, "SYSTEM", 0, "simulation_paused"));
                return;
            }

            paused = false;
            visualWindowStartTick = visualTick;
            visualWindowEndTick = tick;
            visualWindowStartedAtMs = now;
            events.add(new RealtimeEvent(visualTick, "SYSTEM", 0, "simulation_resumed"));
        }

        synchronized void cancelCollapse() {
            if (!isCollapseScenario() || completed) return;
            long now = System.currentTimeMillis();
            int visualTick = Math.min(tick, currentVisualTick(now));
            cancelled = true;
            completed = true;
            paused = false;
            tick = visualTick;
            lastBatchEnd = visualTick;
            visualWindowStartTick = visualTick;
            visualWindowEndTick = visualTick;
            visualWindowStartedAtMs = now;
            realFinishedAtMs = now;
            addVisibleEvent(visualTick, "SYSTEM", 0, "scenario_cancelled");
            closeResources();
        }

        synchronized void closeResources() {
            if (collapseShipmentSource != null) {
                collapseShipmentSource.close();
            }
        }

        private int effectiveMaxTick() {
            return collapseOutcome == null ? maxTick : collapseOutcome.minute;
        }

        // ── SIMULACION_LOTES: avance de un lote completo ─────────────────────
        /**
         * Avanza un lote de {@code steps} minutos simulados.
         *
         * Flujo:
         *   1. Registra el instante de inicio.
         *   2. Recoge envíos de la ventana [tick, tick+steps).
         *   3. Ejecuta el ALNS UNA VEZ para ese lote con parámetros robustos.
         *   4. Actualiza el estado global (events, processed, solucionGlobal).
         *   5. Avanza tick.
         *   6. Devuelve Ta y marca el inicio real para que el frontend respete Sa.
         *      El frontend compara visualStartedAt + Sa para pedir el siguiente lote.
         */
        synchronized void syncRegisteredShipmentsFromDatabase() {
            refreshRealtimeShipmentsFromDatabase();
        }

        synchronized void advanceBatch(int steps) {
            if (completed || paused) return;

            planningInProgress = true;
            try {
            long batchStartMs = System.currentTimeMillis();
            int batchStart = tick;
            int batchEnd   = Math.min(tick + steps, maxTick);
            lastBatchStart = batchStart;
            lastBatchEnd   = batchEnd;
            applyQueuedCancellationRequests();

            if (isCollapseScenario()) {
                loadCollapseShipments(batchStart, batchEnd);
            }

            // 1. Recoger envíos que caen en esta ventana [batchStart, batchEnd)
            List<Shipment> nuevosShipments = new ArrayList<>();
            for (int min = batchStart; min < batchEnd; min++) {
                List<Shipment> en = shipmentsByMinute.getOrDefault(min, Collections.emptyList());
                nuevosShipments.addAll(en);
            }
            List<Shipment> backlogDisponible = new ArrayList<>(batchPlanningBacklog);
            int backlogEntrante = backlogDisponible.size();
            Map<String, Double> prioridadOrigen =
                    calcularPrioridadPreventivaOrigen(nuevosShipments, backlogDisponible, batchStart);
            List<Shipment> backlogSeleccionado =
                    seleccionarBacklogPreventivo(backlogDisponible, batchStart, prioridadOrigen);
            batchPlanningBacklog.clear();
            batchPlanningBacklogIds.clear();
            Set<String> backlogSeleccionadoIds = new HashSet<>();
            for (Shipment shipment : backlogSeleccionado) {
                backlogSeleccionadoIds.add(shipment.getShipmentId());
            }
            for (Shipment shipment : backlogDisponible) {
                if (!backlogSeleccionadoIds.contains(shipment.getShipmentId())) {
                    reencolarBacklogBatch(shipment);
                }
            }
            List<Shipment> loteShipments = new ArrayList<>(backlogSeleccionado);
            loteShipments.addAll(nuevosShipments);

            // 2. Vuelos disponibles: no cancelados y que aún no han despegado
            int routeSearchHorizon = Math.min(planningMaxTick, batchEnd + MAX_DELIVERY_DEADLINE_MINUTES);
            List<Flight> vuelosDisponibles = availableFlightsFrom(batchStart, routeSearchHorizon);

            System.out.printf("[Lote] Ventana %d–%d | %d envíos | %d vuelos disponibles%n",
                    batchStart, batchEnd, loteShipments.size(), vuelosDisponibles.size());

            System.out.printf("[Lote] Backlog preventivo: %d/%d seleccionados | origenes priorizados: %d%n",
                    backlogSeleccionado.size(), backlogEntrante, prioridadOrigen.size());

            // 3. Ejecutar ALNS incremental (conserva capacidad de lotes anteriores)
            if (!loteShipments.isEmpty()) {
                int n       = loteShipments.size();
                int iters   = Math.max(BATCH_ALNS_MIN_ITERATIONS,
                        Math.min(BATCH_ALNS_MAX_ITERATIONS, n * BATCH_ALNS_ITERATIONS_PER_SHIPMENT));
                int seg     = Math.max(8, iters / 10);
                int nDestr  = Math.max(3, Math.min(n / 8 + 3, BATCH_ALNS_DESTROY_CAP));
                boolean fastMode = plannerFastMode();

                ALNS alns = new ALNS(iters, seg, nDestr, 260.0, 0.994, BATCH_ALNS_MAX_ESCALAS,
                        9.0, 3.0, 0.0, 0.8, fastMode)
                        .withOriginPriorities(prioridadOrigen)
                        .withTimeBudgetMillis(BATCH_ALNS_TIME_BUDGET_MS);

                Map<String, Route> resultado = alns.ejecutarIncremental(
                        loteShipments, vuelosDisponibles, airportMap, todosLosShipmentsProcesados);

                registrarEventosLote(loteShipments, resultado);

                solucionGlobal.putAll(resultado);
                registrarBatchProcesados(loteShipments);
                reencolarBatchNoPlanificados(loteShipments);

                System.out.printf("[Lote] Rutas: %d / %d | backlog entrante: %d | backlog saliente: %d%n",
                        resultado.size(), n, backlogEntrante, batchPlanningBacklog.size());
            }
            captureLastPlanningSummary(loteShipments, batchEnd);

            batchCount++;
            CollapseOutcome outcome = isCollapseScenario()
                    ? findFirstCollapseOutcome(batchStart, batchEnd)
                    : null;
            tick = outcome == null ? batchEnd : outcome.minute;
            boolean completedThisBatch = false;
            if (outcome != null) {
                collapseOutcome = outcome;
                completed = true;
                completedThisBatch = true;
                lastBatchEnd = outcome.minute;
                addVisibleEvent(outcome.minute, "SYSTEM", 0, "system_collapsed");
                System.out.printf("[Colapso] %s en minuto %d%n", outcome.reason, outcome.minute);
            } else if (tick >= maxTick) {
                tick = maxTick;
                queue.clear();
                completed = true;
                completedThisBatch = true;
                System.out.println("[Lote] Simulación completada.");
            }

            addVisibleEvent(tick, "SYSTEM", loteShipments.size(), "batch_complete");

            lastBatchRuntimeMs = System.currentTimeMillis() - batchStartMs;
            visualWindowStartedAtMs = batchStartMs;
            visualWindowStartTick = batchStart;
            visualWindowEndTick = tick;
            if (completedThisBatch && realFinishedAtMs == 0) {
                realFinishedAtMs = visualWindowStartedAtMs + planningIntervalMs();
            }
            System.out.printf("[Lote] Completado en %d ms (animación sugerida: %d ms)%n",
                    lastBatchRuntimeMs, BATCH_INTERVAL_MS);
            System.out.printf("[Planificacion fija][Lote] Sa=%d ms | K=%d | Sc=%d min | Ta=%d ms | estable=%s%n",
                    planningIntervalMs(), planningTimeScale(), planningWindowMinutes(), lastBatchRuntimeMs, planningStable());
            if (completedThisBatch) {
                closeResources();
            }
            } finally {
                planningInProgress = false;
            }
        }

        private void loadCollapseShipments(int startMinute, int endMinute) {
            if (collapseShipmentSource == null) return;
            try {
                List<Shipment> loaded = collapseShipmentSource.takeWindow(startMinute, endMinute);
                for (Shipment shipment : loaded) {
                    if (!knownShipmentIds.add(shipment.getShipmentId())) continue;
                    shipments.add(shipment);
                    shipmentsByMinute.computeIfAbsent(shipment.getRequestMinute(), ignored -> new ArrayList<>())
                            .add(shipment);
                }
                if (!loaded.isEmpty()) {
                    System.out.printf("[Colapso] Ventana TXT %d-%d: %d envios consumidos%n",
                            startMinute, endMinute, loaded.size());
                }
            } catch (IOException e) {
                throw new IllegalStateException("No se pudieron consumir los TXT del escenario de colapso.", e);
            }
        }

        synchronized boolean advanceBatchIfDue() {
            if (!isBatchScenario() || completed || paused) return false;
            long now = System.currentTimeMillis();
            if (lastBatchSchedulerCheckMs > 0
                    && now - lastBatchSchedulerCheckMs < Math.max(250L, REALTIME_SCHEDULER_POLL_MS)) {
                return false;
            }
            lastBatchSchedulerCheckMs = now;

            if (batchCount == 0) {
                advanceBatch(planningWindowMinutes());
                return true;
            }

            long referenceMs = visualWindowStartedAtMs > 0 ? visualWindowStartedAtMs : realStartedAtMs;
            if (now - referenceMs < planningIntervalMs()) {
                return false;
            }

            advanceBatch(planningWindowMinutes());
            return true;
        }

        private void registrarEventosLote(List<Shipment> loteShipments, Map<String, Route> resultado) {
            Set<String> routeEvents = new HashSet<>();

            for (Shipment s : loteShipments) {
                if (!s.isSplitPart() && batchCreatedEventShipmentIds.add(s.getShipmentId())) {
                    addVisibleEvent(s.getRequestMinute(),
                            s.getOriginCode(), s.getSuitcaseCount(), "shipment_created");
                }
            }

            for (Map.Entry<String, Route> entry : resultado.entrySet()) {
                Shipment s = findShipmentById(entry.getKey(), loteShipments);
                if (s != null) {
                    registrarEventosRuta(s, entry.getValue(), routeEvents);
                }
            }
        }

        private void registrarBatchProcesados(List<Shipment> loteShipments) {
            for (Shipment shipment : loteShipments) {
                if (batchProcessedShipmentIds.add(shipment.getShipmentId())) {
                    processed.add(shipment);
                    todosLosShipmentsProcesados.add(shipment);
                }
            }
        }

        private void reencolarBatchNoPlanificados(List<Shipment> loteShipments) {
            Map<String, ShipmentRollup> rollups = new LinkedHashMap<>();
            for (Shipment shipment : loteShipments) {
                String rootId = rootShipmentId(shipment);
                Shipment root = findRootShipment(rootId, loteShipments);
                rollups.computeIfAbsent(rootId, ignored -> new ShipmentRollup(root != null ? root : shipment))
                        .include(shipment, maxTick);
            }

            int requeued = 0;
            for (Map.Entry<String, ShipmentRollup> entry : rollups.entrySet()) {
                Shipment root = findRootShipment(entry.getKey(), loteShipments);
                if (root == null) continue;
                ShipmentRollup rollup = entry.getValue();
                int total = Math.max(0, rollup.totalBags);
                int planned = Math.min(total, rollup.plannedBags);
                if (total > 0 && planned < total) {
                    root.resetPlanningState();
                    reencolarBacklogBatch(root);
                    requeued++;
                }
            }
            if (requeued > 0) {
                System.out.printf("[Lote] %d envios sin ruta quedan en backlog de planificacion%n", requeued);
            }
        }

        private String rootShipmentId(Shipment shipment) {
            return shipment.isSplitPart() ? shipment.getParentShipmentId() : shipment.getShipmentId();
        }

        private Shipment findRootShipment(String rootId, List<Shipment> loteShipments) {
            for (Shipment shipment : loteShipments) {
                if (!shipment.isSplitPart() && shipment.getShipmentId().equals(rootId)) return shipment;
            }
            for (Shipment shipment : shipments) {
                if (!shipment.isSplitPart() && shipment.getShipmentId().equals(rootId)) return shipment;
            }
            return null;
        }

        /**
         * El colapso se evalua contra la operacion real hasta el final de la
         * ventana: capacidad fisica de almacenes y vencimiento real del SLA.
         * Un envio sin ruta no colapsa de inmediato; lo hace cuando vence su
         * plazo de 1 o 2 dias y aun falta alguna de sus maletas.
         */
        private CollapseOutcome findFirstCollapseOutcome(int windowStart, int windowEnd) {
            CollapseOutcome capacity = findWarehouseCapacityBreach(windowStart, windowEnd);
            CollapseOutcome deadline = findDeliveryDeadlineBreach(windowStart, windowEnd);

            if (capacity == null) return deadline;
            if (deadline == null) return capacity;
            return capacity.minute <= deadline.minute ? capacity : deadline;
        }

        private CollapseOutcome findWarehouseCapacityBreach(int windowStart, int windowEnd) {
            Map<String, Integer> loads = new HashMap<>();
            List<RealtimeEvent> ordered = new ArrayList<>(events);
            ordered.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparingInt(event -> eventPriority(event.type)));

            for (RealtimeEvent event : ordered) {
                if (event.minute > windowEnd) break;
                if (!airportMap.containsKey(event.airport)) continue;

                int previous = loads.getOrDefault(event.airport, 0);
                int next = Math.max(0, previous + event.delta);
                loads.put(event.airport, next);

                if (event.minute < windowStart) continue;
                Airport airport = airportMap.get(event.airport);
                if (next > airport.getMaxCapacity()) {
                    return CollapseOutcome.capacity(event.minute, airport, next);
                }
            }
            return null;
        }

        private Map<String, Integer> airportLoadsAt(int minute) {
            Map<String, Integer> loads = new HashMap<>();
            List<RealtimeEvent> ordered = new ArrayList<>(events);
            ordered.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparingInt(event -> eventPriority(event.type)));
            for (RealtimeEvent event : ordered) {
                if (event.minute > minute) break;
                if (!airportMap.containsKey(event.airport)) continue;
                loads.compute(event.airport, (ignored, current) ->
                        Math.max(0, (current == null ? 0 : current) + event.delta));
            }
            return loads;
        }

        private Map<String, Integer> airportPeakLoadsBefore(int startMinute, int boundaryMinute) {
            int safeStartMinute = Math.max(0, startMinute);
            int safeBoundaryMinute = Math.max(safeStartMinute, boundaryMinute);
            if (cachedHistoricalPeakStartMinute == safeStartMinute
                    && cachedHistoricalPeakBoundaryMinute == safeBoundaryMinute
                    && cachedHistoricalPeakEventCount == events.size()) {
                return cachedHistoricalPeakLoads;
            }

            Map<String, Integer> loads = new HashMap<>();
            Map<String, Integer> peaks = new HashMap<>();
            List<RealtimeEvent> ordered = new ArrayList<>(events);
            ordered.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparingInt(event -> eventPriority(event.type)));

            boolean trackingPeaks = safeStartMinute == 0;
            for (RealtimeEvent event : ordered) {
                if (event.minute >= safeBoundaryMinute) break;
                if (!airportMap.containsKey(event.airport)) continue;

                if (!trackingPeaks && event.minute >= safeStartMinute) {
                    peaks.putAll(loads);
                    trackingPeaks = true;
                }

                int next = Math.max(0, loads.getOrDefault(event.airport, 0) + event.delta);
                loads.put(event.airport, next);
                if (trackingPeaks) {
                    peaks.merge(event.airport, next, Math::max);
                }
            }

            if (!trackingPeaks) {
                peaks.putAll(loads);
            }

            cachedHistoricalPeakStartMinute = safeStartMinute;
            cachedHistoricalPeakBoundaryMinute = safeBoundaryMinute;
            cachedHistoricalPeakEventCount = events.size();
            cachedHistoricalPeakLoads = Map.copyOf(peaks);
            return cachedHistoricalPeakLoads;
        }

        private CollapseOutcome findDeliveryDeadlineBreach(int windowStart, int windowEnd) {
            Map<String, List<Shipment>> byRoot = new LinkedHashMap<>();
            for (Shipment shipment : todosLosShipmentsProcesados) {
                String rootId = rootShipmentId(shipment);
                byRoot.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(shipment);
            }

            CollapseOutcome first = null;
            for (Map.Entry<String, List<Shipment>> entry : byRoot.entrySet()) {
                List<Shipment> group = entry.getValue();
                Shipment root = group.stream()
                        .filter(shipment -> !shipment.isSplitPart())
                        .findFirst()
                        .orElse(group.get(0));
                Airport origin = airportMap.get(root.getOriginCode());
                Airport destination = airportMap.get(root.getDestCode());
                int deadline = root.getRequestMinute() + Shipment.getDeadlineMinutes(
                        origin == null ? "" : origin.getContinent(),
                        destination == null ? "" : destination.getContinent());
                if (deadline < windowStart || deadline > windowEnd) continue;

                boolean split = group.stream().anyMatch(Shipment::isSplitPart);
                int deliveredOnTime = 0;
                for (Shipment shipment : group) {
                    if (split && !shipment.isSplitPart()) continue;
                    if (!split && shipment.isSplitPart()) continue;
                    if (shipment.isPlanned()
                            && shipment.getEstimatedArrival() > 0
                            && shipment.getEstimatedArrival() <= deadline) {
                        deliveredOnTime += Math.max(0, shipment.getSuitcaseCount());
                    }
                }

                int expected = Math.max(0, root.getOriginalSuitcaseCount());
                if (deliveredOnTime >= expected) continue;

                CollapseOutcome candidate = CollapseOutcome.deadline(
                        deadline, root, expected, deliveredOnTime);
                if (first == null || candidate.minute < first.minute) first = candidate;
            }
            return first;
        }

        private Shipment findShipmentById(String shipmentId, List<Shipment> loteShipments) {
            for (Shipment s : loteShipments) {
                if (shipmentId.equals(s.getShipmentId())) return s;
            }
            for (Shipment s : shipments) {
                if (shipmentId.equals(s.getShipmentId())) return s;
            }
            return null;
        }

        private void registrarEventosRuta(Shipment s, Route ruta, Set<String> registrados) {
            if (s == null || ruta == null || !ruta.isValid() || !registrados.add(s.getShipmentId())) return;

            List<Flight> rutaVuelos = ruta.getFlights();
            if (rutaVuelos.isEmpty()) return;

            addVisibleEvent(
                    rutaVuelos.get(0).absoluteDepartureMinute(),
                    s.getOriginCode(), -s.getSuitcaseCount(), "flight_departure");

            for (int i = 0; i < rutaVuelos.size(); i++) {
                Flight f = rutaVuelos.get(i);
                boolean last = (i == rutaVuelos.size() - 1);
                if (last) {
                    int arrivalMinute = f.absoluteArrivalMinute();
                    addVisibleEvent(arrivalMinute,
                            f.getDestCode(), s.getSuitcaseCount(), "final_arrival");
                    addVisibleEvent(
                            arrivalMinute + OperationParameters.FINAL_PICKUP_WAIT_MINUTES,
                            f.getDestCode(), -s.getSuitcaseCount(), "final_pickup");
                    continue;
                }

                addVisibleEvent(f.absoluteArrivalMinute(),
                        f.getDestCode(), s.getSuitcaseCount(), "connection_arrival");
                Flight next = rutaVuelos.get(i + 1);
                addVisibleEvent(next.absoluteDepartureMinute(),
                        f.getDestCode(), -s.getSuitcaseCount(), "connection_departure");
            }
        }

        private void addVisibleEvent(int minute, String airport, int delta, String type) {
            if (isBatchScenario() && minute > maxTick) return;
            events.add(new RealtimeEvent(minute, airport, delta, type));
        }

        // ── TIEMPO_REAL: avance tick a tick ───────────────────────────────────

        synchronized void advance(int steps) {
            if (completed || paused) return;
            refreshRealtimeShipmentsFromDatabase();
            executeRealtimeCycleIfDue(true);
            int visualStart = tick;
            for (int i = 0; i < steps && !completed; i++) step();
            visualWindowStartedAtMs = System.currentTimeMillis();
            visualWindowStartTick = visualStart;
            visualWindowEndTick = Math.min(tick, maxTick);
        }

        synchronized boolean advanceRealtimeIfDue() {
            long now = System.currentTimeMillis();
            if (completed || paused) return false;
            long lastReferenceMs = Math.max(lastRealtimeAdvanceMs, lastRealtimeExecutionMs);
            if (lastReferenceMs > 0
                    && now - lastReferenceMs < REALTIME_EXECUTION_INTERVAL_MS) {
                return false;
            }
            lastRealtimeAdvanceMs = now;
            advance(REALTIME_PLANNING_WINDOW_MINUTES);
            return true;
        }

        private void refreshRealtimeShipmentsFromDatabase() {
            if (isBatchScenario()) return;

            long now = System.currentTimeMillis();
            if (lastRealtimeShipmentRefreshMs > 0
                    && now - lastRealtimeShipmentRefreshMs < REALTIME_SHIPMENT_REFRESH_MS) {
                return;
            }
            lastRealtimeShipmentRefreshMs = now;

            List<Shipment> latest;
            try {
                latest = new ShipmentParser(airportMap).parseAllFromDatabase(startDate, days, originZone);
            } catch (IOException e) {
                System.err.printf("[Tiempo real] No se pudieron refrescar envios desde BD: %s%n", e.getMessage());
                return;
            }

            int added = 0;
            int queuedLate = 0;
            for (Shipment shipment : latest) {
                if (shipment.getRequestMinute() < startOffsetMinutes
                        || shipment.getRequestMinute() >= maxTick) {
                    continue;
                }

                if (!knownShipmentIds.add(shipment.getShipmentId())) {
                    continue;
                }

                shipments.add(shipment);
                shipmentsByMinute.computeIfAbsent(shipment.getRequestMinute(), ignored -> new ArrayList<>()).add(shipment);
                added++;

                if (shipment.getRequestMinute() < tick) {
                    enqueueShipment(shipment);
                    Airport origin = airportMap.get(shipment.getOriginCode());
                    if (origin != null) {
                        origin.addLoad(shipment.getSuitcaseCount());
                    }
                    events.add(new RealtimeEvent(tick, shipment.getOriginCode(),
                            shipment.getSuitcaseCount(), "shipment_created"));
                    queuedLate++;
                }
            }

            if (added > 0) {
                System.out.printf("[Tiempo real] +%d envios nuevos desde BD (%d en cola inmediata)%n",
                        added, queuedLate);
            }
        }

        private void step() {
            List<Shipment> incoming = shipmentsByMinute.getOrDefault(tick, Collections.emptyList());
            if (!incoming.isEmpty()) {
                for (Shipment s : incoming) enqueueShipment(s);
                for (Shipment s : incoming) {
                    Airport origin = airportMap.get(s.getOriginCode());
                    if (origin != null) origin.addLoad(s.getSuitcaseCount());
                    events.add(new RealtimeEvent(tick, s.getOriginCode(), s.getSuitcaseCount(), "shipment_created"));
                }
            }
            processFlightMovements();
            executeRealtimeCycleIfDue(false);
            tick += INTERVALO_TICK;
            if (tick > maxTick) {
                if (!queue.isEmpty()) planQueue();
                completed = true;
                if (realFinishedAtMs == 0) {
                    realFinishedAtMs = System.currentTimeMillis();
                }
            }
        }

        private void executeRealtimeCycleIfDue(boolean allowInitial) {
            long now = System.currentTimeMillis();
            boolean firstCycle = lastRealtimeExecutionMs == 0;
            boolean dueByClock = !firstCycle && now - lastRealtimeExecutionMs >= REALTIME_EXECUTION_INTERVAL_MS;
            if (firstCycle) {
                if (!allowInitial) return;
            } else if (!dueByClock) {
                return;
            }

            long cycleStartMs = System.currentTimeMillis();
            applyQueuedCancellationRequests();
            applyPendingRealtimeCancellations();

            lastBatchStart = tick;
            lastBatchEnd = Math.min(tick + REALTIME_PLANNING_WINDOW_MINUTES, maxTick);
            batchCount++;
            realtimeExecutionCount++;

            if (!queue.isEmpty()) planQueue();

            events.add(new RealtimeEvent(tick, "SYSTEM", queue.size(), "replan"));
            lastBatchRuntimeMs = System.currentTimeMillis() - cycleStartMs;
            lastRealtimeExecutionMs = now;
            System.out.printf("[Planificacion fija][Tiempo real] Sa=%d ms | K=%d | Sc=%d min | Ta=%d ms | estable=%s%n",
                    planningIntervalMs(), planningTimeScale(), planningWindowMinutes(), lastBatchRuntimeMs, planningStable());
        }

        private void enqueueShipment(Shipment shipment) {
            if (shipment == null) return;
            if (queuedShipmentIds.add(shipment.getShipmentId())) queue.add(shipment);
        }

        private void processFlightMovements() {
            for (Shipment s : processed) {
                Route route = s.getAssignedRoute();
                if (route == null) continue;
                for (int idx = 0; idx < route.getFlights().size(); idx++) {
                    Flight f = route.getFlights().get(idx);
                    String depKey = s.getShipmentId() + ":" + f.getFlightId() + ":dep";
                    if (f.absoluteDepartureMinute() == tick && processedFlightEvents.add(depKey)) {
                        Airport origin = airportMap.get(f.getOriginCode());
                        if (origin != null) origin.removeLoad(s.getSuitcaseCount());
                        events.add(new RealtimeEvent(tick, f.getOriginCode(), -s.getSuitcaseCount(), "flight_departure"));
                    }
                    String arrKey = s.getShipmentId() + ":" + f.getFlightId() + ":arr";
                    if (f.absoluteArrivalMinute() == tick && processedFlightEvents.add(arrKey)) {
                        boolean finalLeg = idx == route.getFlights().size() - 1;
                        Airport dest = airportMap.get(f.getDestCode());
                        if (dest != null) dest.addLoad(s.getSuitcaseCount());
                        events.add(new RealtimeEvent(tick, f.getDestCode(), s.getSuitcaseCount(),
                                finalLeg ? "final_arrival" : "connection_arrival"));
                    }
                    String pickupKey = s.getShipmentId() + ":" + f.getFlightId() + ":pickup";
                    int pickupMinute = f.absoluteArrivalMinute() + OperationParameters.FINAL_PICKUP_WAIT_MINUTES;
                    if (idx == route.getFlights().size() - 1
                            && pickupMinute == tick
                            && processedFlightEvents.add(pickupKey)) {
                        Airport dest = airportMap.get(f.getDestCode());
                        if (dest != null) dest.removeLoad(s.getSuitcaseCount());
                        events.add(new RealtimeEvent(tick, f.getDestCode(), -s.getSuitcaseCount(), "final_pickup"));
                    }
                }
            }
        }

        private void planQueue() {
            int iters   = Math.max(20, Math.min(80, queue.size() * 3));
            int segment = Math.max(5, iters / 5);
            ALNS alns   = new ALNS(iters, segment, -1, 80.0, 0.96, 2,
                    9.0, 3.0, 0.0, 0.8, plannerFastMode());
            Map<String, Route> result = alns.ejecutarIncremental(
                    queue, availableFlightsFrom(tick), airportMap, processed);
            List<Shipment> planningScope = new ArrayList<>(queue);
            events.add(new RealtimeEvent(tick, "SYSTEM", result.size(), "replan"));
            processed.addAll(queue);
            for (Shipment s : queue) queuedShipmentIds.remove(s.getShipmentId());
            captureLastPlanningSummary(planningScope, Math.min(maxTick, Math.max(tick, lastBatchEnd)));
            queue.clear();
        }

        private void applyPendingRealtimeCancellations() {
            if (pendingCancellations.isEmpty()) return;
            List<String> pending = new ArrayList<>(pendingCancellations);
            pendingCancellations.clear();
            for (String cancellationKey : pending) {
                cancellations.add(cancellationKey);
                requeueAffectedByRealtimeCancellation(cancellationKey);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
            }
        }

        private void requeueAffectedByRealtimeCancellation(String cancellationKey) {
            Iterator<Shipment> iterator = processed.iterator();
            while (iterator.hasNext()) {
                Shipment s = iterator.next();
                Route route = s.getAssignedRoute();
                if (route == null) continue;
                boolean usesCancelled = route.getFlights().stream()
                        .anyMatch(f -> cancellationKey(f).equals(cancellationKey));
                if (!usesCancelled) continue;

                for (Flight f : route.getFlights()) {
                    if (f.absoluteDepartureMinute() >= tick) {
                        f.releaseLoad(s.getSuitcaseCount());
                    }
                }
                s.resetPlanningState();
                iterator.remove();
                enqueueShipment(s);
            }
        }

        // ── Cancelación de vuelos futuros ────────────────────────────────────

        /**
         * Cancela un vuelo futuro y replanifica.
         *
         * Para SIMULACION_LOTES:
         *   – Replanifica los afectados directamente.
         *   – Luego reoptimiza TODOS los envíos ya planificados juntos,
         *     aprovechando la capacidad liberada y garantizando plazos.
         *
         * Para TIEMPO_REAL: solo replanifica los afectados.
         */
        synchronized void cancel(String flightId, FlightPlanService flightPlanService) {
            Flight requested = findFlight(flightId);
            boolean exactOccurrenceRequested = findFlightByCancellationKey(flightId) != null;
            Flight toCancel = resolveCancellationFlight(flightId);
            if (toCancel == null) {
                if (!hasCancellationCode(flightId)) {
                    throw new IllegalArgumentException("Vuelo no encontrado: " + normalizeFlightInput(flightId));
                }
                if (isBatchScenario() || exactOccurrenceRequested) {
                    throw new IllegalArgumentException(
                            "No hay una salida futura cancelable para el vuelo: " + normalizeFlightInput(flightId));
                }
                queueCancellationRequest(flightId);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled_pending"));
                return;
            }

            String cancelledFlightKey = cancellationKey(toCancel);
            if (requested != null && !cancellationKey(requested).equals(cancelledFlightKey)) {
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancel_redirected"));
            }

            if ("SIMULACION_LOTES".equals(scenario)) {
                cancellations.add(cancelledFlightKey);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
                cancelBatch(cancelledFlightKey);
            } else {
                pendingCancellations.add(cancelledFlightKey);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled_pending"));
            }
        }

        private Flight resolveCancellationFlight(String input) {
            Flight exact = findFlightByCancellationKey(input);
            if (exact != null) {
                if (exact.absoluteDepartureMinute() - tick >= 60
                        && !isCancelled(exact)
                        && !isPendingCancellation(exact)) {
                    return exact;
                }
                return null;
            }

            Flight requested = findFlight(input);
            if (requested != null
                    && requested.absoluteDepartureMinute() - tick >= 60
                    && !isCancelled(requested)
                    && !isPendingCancellation(requested)) {
                return requested;
            }

            Flight reference = requested;
            if (reference == null) {
                reference = flights.stream()
                        .filter(f -> sameCancellationCode(f, input))
                        .min(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                        .orElse(null);
            }
            if (reference == null) return null;

            final Flight recurringReference = reference;
            int minDeparture = tick + 60;
            return flights.stream()
                    .filter(f -> sameRecurringFlight(recurringReference, f))
                    .filter(f -> f.absoluteDepartureMinute() >= minDeparture)
                    .filter(f -> !isCancelled(f))
                    .filter(f -> !isPendingCancellation(f))
                    .min(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                    .orElse(null);
        }

        private void queueCancellationRequest(String input) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("Codigo de vuelo invalido.");
            }
            String normalized = normalizeFlightInput(input);
            boolean exists = queuedCancellationRequests.stream()
                    .anyMatch(request -> request.matchesInput(normalized));
            if (!exists) queuedCancellationRequests.add(new CancellationRequest(normalized));
        }

        private boolean hasCancellationCode(String input) {
            if (input == null || input.isBlank()) return false;
            if (findFlightByCancellationKey(input) != null) return true;
            return flights.stream().anyMatch(f -> sameCancellationCode(f, input));
        }

        private Flight findFlightByCancellationKey(String input) {
            if (input == null || input.isBlank() || !input.contains("@")) return null;
            String normalized = input.trim();
            return flights.stream()
                    .filter(f -> cancellationKey(f).equalsIgnoreCase(normalized))
                    .findFirst()
                    .orElse(null);
        }

        private String normalizeFlightInput(String input) {
            return input == null ? "" : input.trim();
        }

        private void applyQueuedCancellationRequests() {
            if (queuedCancellationRequests.isEmpty()) return;

            Iterator<CancellationRequest> iterator = queuedCancellationRequests.iterator();
            while (iterator.hasNext()) {
                CancellationRequest request = iterator.next();
                Flight toCancel = resolveCancellationFlight(request.input());
                if (toCancel == null) continue;

                String key = cancellationKey(toCancel);
                if (isBatchScenario()) {
                    cancellations.add(key);
                    cancelBatch(key);
                    events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
                } else {
                    pendingCancellations.add(key);
                    events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled_pending"));
                }
                iterator.remove();
            }
        }

        private boolean sameCancellationCode(Flight flight, String input) {
            if (input == null || input.isBlank()) return false;
            String normalized = input.trim();
            return flight.getFlightId().equalsIgnoreCase(normalized)
                    || flight.getFlightId().toUpperCase(Locale.ROOT).startsWith(normalized.toUpperCase(Locale.ROOT));
        }

        private boolean sameRecurringFlight(Flight a, Flight b) {
            return a.getOriginCode().equals(b.getOriginCode())
                    && a.getDestCode().equals(b.getDestCode())
                    && a.getDepartureMinute() == b.getDepartureMinute()
                    && flightSequence(a.getFlightId()).equals(flightSequence(b.getFlightId()));
        }

        private String flightSequence(String flightId) {
            int idx = flightId == null ? -1 : flightId.lastIndexOf('-');
            return idx < 0 ? "" : flightId.substring(idx + 1);
        }

        private String cancellationKey(Flight flight) {
            return flight.getFlightId() + "@" + flight.absoluteDepartureMinute();
        }

        private boolean isCancelled(Flight flight) {
            return cancellations.contains(cancellationKey(flight));
        }

        private boolean isPendingCancellation(Flight flight) {
            return pendingCancellations.contains(cancellationKey(flight));
        }

        synchronized void markCancelled(String flightId) {
            Flight toCancel = findFlight(flightId);
            if (toCancel == null)
                throw new IllegalArgumentException("Vuelo no encontrado: " + flightId);
            if (toCancel.absoluteDepartureMinute() <= tick)
                throw new IllegalArgumentException("No se puede cancelar un vuelo que ya inicio.");
            cancellations.add(cancellationKey(toCancel));
            events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
        }

        /**
         * Cancelación para SIMULACION_LOTES:
         *   1. Afectados directos → ALNS rápido.
         *   2. Reoptimización global de todos los ya planificados.
         */
        private void cancelBatch(String cancellationKey) {
            // Afectados directos
            List<Shipment> afectados = new ArrayList<>();
            for (Shipment s : todosLosShipmentsProcesados) {
                Route route = solucionGlobal.get(s.getShipmentId());
                if (route == null) continue;
                boolean usa = route.getFlights().stream()
                        .anyMatch(f -> cancellationKey(f).equals(cancellationKey));
                if (usa) {
                    afectados.add(s);
                    s.resetPlanningState();
                    solucionGlobal.remove(s.getShipmentId());
                }
            }

            System.out.printf("[Cancelación] Vuelo %s | %d envíos afectados directamente%n",
                    cancellationKey, afectados.size());

            List<Flight> disponibles = availableFlightsFrom(tick);

            // Paso 1: replanificar afectados directos
            if (!afectados.isEmpty()) {
                int iters = Math.max(30, Math.min(120, afectados.size() * 3));
                ALNS alns = new ALNS(iters, Math.max(10, iters / 5),
                        Math.max(3, Math.min(afectados.size() / 8 + 2, BATCH_ALNS_DESTROY_CAP)),
                        140.0, 0.985, BATCH_ALNS_MAX_ESCALAS, 9.0, 3.0, 0.0, 0.8, true);
                List<Shipment> baseSinAfectados = todosLosShipmentsProcesados.stream()
                        .filter(s -> !afectados.contains(s))
                        .filter(Shipment::isPlanned)
                        .toList();
                Map<String, Route> replanDirectos = alns.ejecutarIncremental(
                        afectados, disponibles, airportMap, baseSinAfectados);
                solucionGlobal.putAll(replanDirectos);
                System.out.printf("[Cancelación] Replanificados directos: %d / %d%n",
                        replanDirectos.size(), afectados.size());
            }

            // Paso 2: reoptimización global — todos los procesados juntos
            // para aprovechar capacidad liberada y garantizar plazos 1/2 días
            List<Shipment> todosAReoptar = new ArrayList<>(todosLosShipmentsProcesados);
            // Incluir también los pendientes en cola
            todosAReoptar.addAll(queue);

            if (BATCH_CANCEL_GLOBAL_REOPT && !todosAReoptar.isEmpty()) {
                System.out.printf("[Cancelación] Reoptimizando globalmente %d envíos...%n",
                        todosAReoptar.size());
                for (Shipment s : todosAReoptar) s.resetPlanningState();

                int n       = todosAReoptar.size();
                int iters   = Math.max(60, Math.min(180, n * 2));
                int seg     = Math.max(10, iters / 10);
                int nDestr  = Math.max(5, Math.min(n / 8, BATCH_ALNS_DESTROY_CAP));

                ALNS alnsGlobal = new ALNS(iters, seg, nDestr, 220.0, 0.992, BATCH_ALNS_MAX_ESCALAS,
                        9.0, 3.0, 0.0, 0.8, true);
                Map<String, Route> replanGlobal = alnsGlobal.ejecutar(todosAReoptar, disponibles, airportMap);
                solucionGlobal.putAll(replanGlobal);

                long conRuta = replanGlobal.values().stream().filter(r -> r != null && r.isValid()).count();
                System.out.printf("[Cancelación] Reoptimización global: %d / %d con ruta válida%n",
                        conRuta, todosAReoptar.size());
            }
        }

        /** Cancelación para TIEMPO_REAL: solo replanifica afectados. */
        private void replanAffected(String flightId) {
            List<Shipment> affected = new ArrayList<>();
            for (Shipment s : processed) {
                Route route = s.getAssignedRoute();
                if (route == null) continue;
                for (Flight f : route.getFlights()) {
                    if (f.getFlightId().equals(flightId)) {
                        affected.add(s);
                        s.resetPlanningState();
                        break;
                    }
                }
            }
            if (affected.isEmpty()) return;
            int iters = Math.max(20, Math.min(80, affected.size() * 3));
            int seg   = Math.max(5, iters / 5);
            ALNS alns = new ALNS(iters, seg, affected.size(), 80.0, 0.96, 2,
                    9.0, 3.0, 0.0, 0.8);
            alns.replanificar(affected, flightId, flights, airportMap);
            events.add(new RealtimeEvent(tick, "SYSTEM", affected.size(), "flight_cancelled"));
        }

        // ── Utilidades ────────────────────────────────────────────────────────

        private List<Flight> availableFlightsFrom(int fromMinute) {
            return availableFlightsFrom(fromMinute, planningMaxTick);
        }

        private List<Flight> availableFlightsFrom(int fromMinute, int untilMinute) {
            List<Flight> available = new ArrayList<>();
            int start = firstFlightAtOrAfter(flights, fromMinute);
            int horizon = Math.min(untilMinute, planningMaxTick);
            for (int i = start; i < flights.size(); i++) {
                Flight f = flights.get(i);
                if (isBatchScenario() && f.absoluteDepartureMinute() > horizon) break;
                if (!isCancelled(f)
                        && !isPendingCancellation(f))
                    available.add(f);
            }
            return available;
        }

        private int firstFlightAtOrAfter(List<Flight> source, int fromMinute) {
            int low = 0;
            int high = source.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (source.get(mid).absoluteDepartureMinute() < fromMinute) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        private Flight findFlight(String flightId) {
            return flightById.get(flightId);
        }

        private boolean flightVisibleInSnapshot(Flight flight) {
            int windowStart = visualWindowStartTick;
            int windowEnd = visualWindowEndTick;
            windowEnd = Math.min(windowEnd, maxTick);

            if (isBatchScenario() && flight.absoluteDepartureMinute() > maxTick) {
                return false;
            }

            return flight.absoluteDepartureMinute() <= windowEnd
                    && flight.absoluteArrivalMinute() >= windowStart;
        }

        // ── Snapshot JSON ─────────────────────────────────────────────────────

        private int snapshotWindowStart() {
            if (!isBatchScenario()) return 0;
            int anchor = visualWindowStartTick > 0 ? visualWindowStartTick : tick;
            return Math.max(0, anchor - BATCH_SNAPSHOT_TRAILING_MINUTES);
        }

        private int snapshotWindowEnd() {
            if (!isBatchScenario()) return maxTick;
            if (isCollapseScenario()) return Math.min(effectiveMaxTick(), visualWindowEndTick);
            int anchor = Math.max(tick, visualWindowEndTick);
            return Math.min(maxTick, anchor + BATCH_SNAPSHOT_LOOKAHEAD_MINUTES);
        }

        private List<Shipment> shipmentsForSnapshot(List<Shipment> source, int windowStart, int windowEnd) {
            if (!isBatchScenario()) return source;

            List<Shipment> visible = new ArrayList<>();
            for (Shipment shipment : source) {
                if (shipment.getRequestMinute() >= windowStart && shipment.getRequestMinute() <= windowEnd) {
                    visible.add(shipment);
                    continue;
                }

                Route route = shipment.getAssignedRoute();
                if (route == null || !route.isValid()) continue;
                for (Flight flight : route.getFlights()) {
                    if (flight.absoluteArrivalMinute() >= windowStart
                            && flight.absoluteDepartureMinute() <= windowEnd) {
                        visible.add(shipment);
                        break;
                    }
                }
            }

            if (isCollapseScenario() || visible.size() <= BATCH_SNAPSHOT_MAX_SHIPMENTS) return visible;
            return new ArrayList<>(visible.subList(
                    visible.size() - BATCH_SNAPSHOT_MAX_SHIPMENTS,
                    visible.size()));
        }

        private List<RealtimeEvent> eventsForSnapshot(int windowStart, int windowEnd) {
            if (!isBatchScenario()) return new ArrayList<>(events);

            Map<String, Integer> baseline = new LinkedHashMap<>();
            List<RealtimeEvent> visible = new ArrayList<>();
            for (RealtimeEvent event : events) {
                if (event.minute <= windowStart) {
                    baseline.merge(event.airport, event.delta, Integer::sum);
                } else if (event.minute <= windowEnd) {
                    visible.add(event);
                }
            }

            visible.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparing(RealtimeEvent::airport)
                    .thenComparingInt(e -> eventPriority(e.type))
                    .thenComparing(RealtimeEvent::type));
            if (!isCollapseScenario() && visible.size() > BATCH_SNAPSHOT_MAX_EVENTS) {
                visible = new ArrayList<>(visible.subList(
                        visible.size() - BATCH_SNAPSHOT_MAX_EVENTS,
                        visible.size()));
            }

            List<RealtimeEvent> snapshot = new ArrayList<>(baseline.size() + visible.size());
            for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
                int load = Math.max(0, entry.getValue());
                if (load > 0) {
                    snapshot.add(new RealtimeEvent(windowStart, entry.getKey(), load, "snapshot_baseline"));
                }
            }
            snapshot.addAll(visible);
            snapshot.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparing(RealtimeEvent::airport)
                    .thenComparingInt(e -> eventPriority(e.type))
                    .thenComparing(RealtimeEvent::type));
            return snapshot;
        }

        synchronized String shipmentsPageJson(int page, int pageSize,
                                               String search, String origin,
                                               String destination, String statusFilter,
                                               int currentMinute, int historyMinutes,
                                               int departureWithinMinutes,
                                               String sortBy, String sortOrder) {
            int safePageSize = Math.max(1, Math.min(100, pageSize));
            int safePage = Math.max(1, page);
            int referenceMinute = currentMinute >= 0 ? currentMinute : tick;
            String normalizedSearch = normalizeFilter(search);
            String normalizedOrigin = normalizeFilter(origin);
            String normalizedDestination = normalizeFilter(destination);
            String normalizedStatus = normalizeFilter(statusFilter);

            List<Shipment> source = isBatchScenario()
                    ? todosLosShipmentsProcesados : shipments;
            List<Shipment> filtered = new ArrayList<>();
            for (Shipment shipment : source) {
                if (!matchesShipmentFilters(
                        shipment,
                        normalizedSearch,
                        normalizedOrigin,
                        normalizedDestination,
                        normalizedStatus,
                        referenceMinute,
                        historyMinutes,
                        departureWithinMinutes)) {
                    continue;
                }
                filtered.add(shipment);
            }
            filtered.sort((a, b) -> compareShipments(a, b, referenceMinute, sortBy, sortOrder));

            int total = filtered.size();
            int totalPages = total == 0 ? 1 : (int) Math.ceil(total / (double) safePageSize);
            safePage = Math.min(safePage, totalPages);
            int from = Math.min(total, (safePage - 1) * safePageSize);
            int to = Math.min(total, from + safePageSize);

            Json json = new Json();
            json.objStart();
            json.prop("page", safePage).comma();
            json.prop("pageSize", safePageSize).comma();
            json.prop("total", total).comma();
            json.prop("totalPages", totalPages).comma();
            json.name("items").arrayStart();
            for (int i = from; i < to; i++) {
                writeShipment(json, filtered.get(i));
                if (i < to - 1) json.comma();
            }
            json.arrayEnd();
            json.objEnd();
            return json.toString();
        }

        private int compareShipmentsByNextDelivery(Shipment a, Shipment b, int referenceMinute) {
            int bucketDiff = shipmentDeliveryBucket(a, referenceMinute) - shipmentDeliveryBucket(b, referenceMinute);
            if (bucketDiff != 0) return bucketDiff;

            boolean aDelivered = isShipmentDelivered(a, referenceMinute);
            boolean bDelivered = isShipmentDelivered(b, referenceMinute);
            boolean aUpcoming = isShipmentUpcoming(a, referenceMinute);
            boolean bUpcoming = isShipmentUpcoming(b, referenceMinute);

            if (aUpcoming && bUpcoming) {
                int arrivalDiff = Integer.compare(a.getEstimatedArrival(), b.getEstimatedArrival());
                if (arrivalDiff != 0) return arrivalDiff;
            }
            if (aDelivered && bDelivered) {
                int arrivalDiff = Integer.compare(b.getEstimatedArrival(), a.getEstimatedArrival());
                if (arrivalDiff != 0) return arrivalDiff;
            }

            int requestDiff = Integer.compare(a.getRequestMinute(), b.getRequestMinute());
            if (requestDiff != 0) return requestDiff;
            return a.getShipmentId().compareTo(b.getShipmentId());
        }

        private int compareShipments(Shipment a, Shipment b, int referenceMinute, String sortBy, String sortOrder) {
            String normalizedSortBy = normalizeFilter(sortBy);
            if (normalizedSortBy.isBlank() || "delivery".equals(normalizedSortBy)) {
                return compareShipmentsByNextDelivery(a, b, referenceMinute);
            }

            boolean descending = "desc".equalsIgnoreCase(sortOrder);
            int direction = descending ? -1 : 1;
            int compare;
            if ("departure".equals(normalizedSortBy)) {
                compare = Integer.compare(firstDepartureMinute(a), firstDepartureMinute(b));
            } else {
                compare = Integer.compare(a.getRequestMinute(), b.getRequestMinute());
            }

            if (compare != 0) return compare * direction;
            return a.getShipmentId().compareTo(b.getShipmentId());
        }

        private int firstDepartureMinute(Shipment shipment) {
            Route route = shipment.getAssignedRoute();
            if (route == null || route.getFlights().isEmpty()) {
                return Integer.MAX_VALUE;
            }

            int firstDeparture = Integer.MAX_VALUE;
            for (Flight flight : route.getFlights()) {
                firstDeparture = Math.min(firstDeparture, flight.absoluteDepartureMinute());
            }
            return firstDeparture;
        }

        private int shipmentDeliveryBucket(Shipment shipment, int referenceMinute) {
            if (isShipmentUpcoming(shipment, referenceMinute)) return 0;
            if (!isShipmentDelivered(shipment, referenceMinute)) return 1;
            return 2;
        }

        private boolean isShipmentUpcoming(Shipment shipment, int referenceMinute) {
            return shipment.isPlanned()
                    && shipment.getEstimatedArrival() > 0
                    && shipment.getEstimatedArrival() > referenceMinute;
        }

        private boolean isShipmentDelivered(Shipment shipment, int referenceMinute) {
            return shipment.isPlanned()
                    && shipment.getEstimatedArrival() > 0
                    && shipment.getEstimatedArrival() <= referenceMinute;
        }

        private String normalizeFilter(String value) {
            if (value == null || value.isBlank()) return "";
            String trimmed = value.trim();
            if ("any".equalsIgnoreCase(trimmed)
                    || "all".equalsIgnoreCase(trimmed)
                    || "cualquiera".equalsIgnoreCase(trimmed)) {
                return "";
            }
            return trimmed.toLowerCase(Locale.ROOT);
        }

        private boolean matchesShipmentFilters(Shipment shipment,
                                               String search,
                                               String origin,
                                               String destination,
                                               String statusFilter,
                                               int referenceMinute, int historyMinutes,
                                               int departureWithinMinutes) {
            if (!matchesShipmentHistoryWindow(shipment, referenceMinute, historyMinutes)) {
                return false;
            }
            if (!matchesShipmentAirportFilters(shipment, origin, destination)) {
                return false;
            }
            if (!statusFilter.isBlank() && !matchesShipmentStatus(shipment, statusFilter, referenceMinute)) {
                return false;
            }
            if (departureWithinMinutes >= 0
                    && !matchesDepartureWindow(shipment, referenceMinute, departureWithinMinutes)) {
                return false;
            }
            if (search.isBlank()) return true;

            if (shipment.getShipmentId().toLowerCase(Locale.ROOT).contains(search)
                    || shipment.getClientId().toLowerCase(Locale.ROOT).contains(search)
                    || shipment.getOriginCode().toLowerCase(Locale.ROOT).contains(search)
                    || shipment.getDestCode().toLowerCase(Locale.ROOT).contains(search)) {
                return true;
            }

            Route route = shipment.getAssignedRoute();
            if (route == null) return false;
            for (Flight flight : route.getFlights()) {
                if (flight.getFlightId().toLowerCase(Locale.ROOT).contains(search)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesDepartureWindow(Shipment shipment, int referenceMinute, int departureWithinMinutes) {
            int departureMinute = firstDepartureMinute(shipment);
            return departureMinute >= referenceMinute
                    && departureMinute <= referenceMinute + departureWithinMinutes;
        }

        private boolean matchesShipmentHistoryWindow(Shipment shipment, int referenceMinute, int historyMinutes) {
            if (shipment.getRequestMinute() > referenceMinute) {
                return false;
            }
            if (!shipment.isPlanned()) {
                return true;
            }
            return referenceMinute <= shipment.getEstimatedArrival() + historyMinutes;
        }

        private boolean matchesShipmentAirportFilters(Shipment shipment, String origin, String destination) {
            if (origin.isBlank() && destination.isBlank()) return true;

            if (matchesAirportPair(shipment.getOriginCode(), shipment.getDestCode(), origin, destination)) {
                return true;
            }

            Route route = shipment.getAssignedRoute();
            if (route == null) return false;
            for (Flight flight : route.getFlights()) {
                if (matchesAirportPair(flight.getOriginCode(), flight.getDestCode(), origin, destination)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesAirportPair(String originCode, String destinationCode, String origin, String destination) {
            return (origin.isBlank() || originCode.toLowerCase(Locale.ROOT).equals(origin))
                    && (destination.isBlank() || destinationCode.toLowerCase(Locale.ROOT).equals(destination));
        }

        private boolean matchesShipmentStatus(Shipment shipment, String statusFilter, int referenceMinute) {
            return switch (statusFilter) {
                case "planned", "planificado" -> shipment.isPlanned()
                        && !isShipmentDelivered(shipment, referenceMinute)
                        && !isShipmentInFlight(shipment, referenceMinute);
                case "in-progress", "in_progress", "en-curso", "en_curso" ->
                        isShipmentInFlight(shipment, referenceMinute);
                case "unplanned", "sin-ruta", "sin_ruta" -> !shipment.isPlanned();
                case "ontime", "on-time", "a-tiempo", "a_tiempo" -> shipment.isPlanned() && shipment.isOnTime();
                case "late", "tarde" -> shipment.isPlanned() && !shipment.isOnTime();
                case "delivered", "entregado" -> isShipmentDelivered(shipment, referenceMinute);
                case "pending", "pendiente" -> !shipment.isPlanned()
                        || shipment.getEstimatedArrival() <= 0
                        || shipment.getEstimatedArrival() > referenceMinute;
                default -> true;
            };
        }

        private boolean isShipmentInFlight(Shipment shipment, int referenceMinute) {
            if (!shipment.isPlanned()) return false;
            Route route = shipment.getAssignedRoute();
            if (route == null) return false;
            for (Flight flight : route.getFlights()) {
                if (referenceMinute >= flight.absoluteDepartureMinute()
                        && referenceMinute <= flight.absoluteArrivalMinute()) {
                    return true;
                }
            }
            return false;
        }

        String snapshotJsonForRead() {
            String cached = lastSnapshotJson;
            if (planningInProgress && cached != null && !cached.isBlank() && !"{}".equals(cached)) {
                return cached;
            }
            return snapshotJson();
        }

        synchronized String snapshotJson() {
            return snapshotJson(false);
        }

        synchronized String snapshotJson(boolean includeControlToken) {
            Json json = new Json();

            // Para lotes: mostrar lo consumido; para tiempo real: mostrar todo lo conocido desde BD.
            List<Shipment> shipmentSource = isBatchScenario()
                    ? todosLosShipmentsProcesados : shipments;
            int snapshotStart = snapshotWindowStart();
            int snapshotEnd = snapshotWindowEnd();
            List<Shipment> shipmentPayload = shipmentsForSnapshot(shipmentSource, snapshotStart, snapshotEnd);
            PlanningMetrics planMetrics = calculatePlanningMetrics(
                    shipmentSource,
                    isBatchScenario() ? Math.min(effectiveMaxTick(), tick) : maxTick);
            int processedShipmentCount = planMetrics.totalShipments;
            Map<String, Integer> snapshotAirportLoads = isBatchScenario()
                    ? airportLoadsAt(snapshotEnd)
                    : Collections.emptyMap();
            Map<String, Integer> historicalAirportPeakLoads = isBatchScenario()
                    ? airportPeakLoadsBefore(startOffsetMinutes, snapshotStart)
                    : Collections.emptyMap();
            List<Flight> used = flights.stream()
                    .filter(this::flightVisibleInSnapshot)
                    .sorted(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                    .toList();
            int usedFlights = (int) used.stream()
                    .filter(f -> f.getAssignedLoad() > 0)
                    .count();

            String message = cancellations.isEmpty() ? "" :
                    "Vuelos cancelados: " + String.join(", ", cancellations) +
                    ". Replanificación completada.";
            String lifecycleStatus = collapseOutcome != null ? "COLLAPSED" :
                    cancelled ? "CANCELLED" :
                            completed ? "COMPLETED" : paused ? "PAUSED" : "RUNNING";

            json.objStart();
            json.prop("simulationId", id).comma();
            json.prop("scenario", scenarioLabel()).comma();
            json.prop("status", lifecycleStatus).comma();
            json.prop("paused", paused).comma();
            json.prop("ownerClientId", ownerClientId).comma();
            if (includeControlToken && !controlToken.isBlank()) {
                json.prop("controlToken", controlToken).comma();
            }
            json.prop("days", days).comma();
            json.prop("tick", tick).comma();
            json.prop("maxTick", effectiveMaxTick()).comma();
            json.prop("planningMaxTick", planningMaxTick).comma();
            json.prop("startOffsetMinutes", startOffsetMinutes).comma();
            json.prop("batchMinutes", planningWindowMinutes()).comma();
            json.prop("batchIntervalMs", planningIntervalMs()).comma();
            json.prop("planningMode", "FIXED_SCHEDULE").comma();
            json.prop("planningExecutionMs", lastBatchRuntimeMs).comma();
            json.prop("planningIntervalMs", planningIntervalMs()).comma();
            json.prop("planningIntervalMinutes", planningIntervalMinutes()).comma();
            json.prop("planningTimeScale", planningTimeScale()).comma();
            json.prop("planningWindowMinutes", planningWindowMinutes()).comma();
            json.prop("planningStable", planningStable()).comma();
            json.prop("planningFastMode", plannerFastMode()).comma();
            json.prop("connectionWaitMinutes", OperationParameters.CONNECTION_WAIT_MINUTES).comma();
            json.prop("finalPickupWaitMinutes", OperationParameters.FINAL_PICKUP_WAIT_MINUTES).comma();
            json.prop("batchCount", batchCount).comma();
            json.prop("lastBatchStart", lastBatchStart).comma();
            json.prop("lastBatchEnd", lastBatchEnd).comma();
            json.prop("lastBatchRuntimeMs", lastBatchRuntimeMs).comma();
            json.prop("visualStartTick", visualWindowStartTick).comma();
            json.prop("visualEndTick", visualWindowEndTick).comma();
            json.prop("snapshotWindowStart", snapshotStart).comma();
            json.prop("snapshotWindowEnd", snapshotEnd).comma();
            json.prop("snapshotLimited", isBatchScenario()).comma();
            json.prop("visualStartedAt", java.time.Instant.ofEpochMilli(visualWindowStartedAtMs).toString()).comma();
            json.prop("realtimeExecutionIntervalMs", REALTIME_EXECUTION_INTERVAL_MS).comma();
            json.prop("pendingCancellationCount", pendingCancellations.size() + queuedCancellationRequests.size()).comma();
            json.prop("message", message).comma();
            json.prop("simulationStartDateTime", simulationStartInstant).comma();
            json.prop("simulationEndDateTime", simulationEndInstant).comma();
            json.prop("simulationStoppedDateTime", completed ? simulationInstantAt(tick) : "").comma();
            long effectiveRealFinishedAtMs = realFinishedAtMs > 0
                    ? realFinishedAtMs
                    : System.currentTimeMillis();
            json.prop("realStartedAt", java.time.Instant.ofEpochMilli(realStartedAtMs).toString()).comma();
            json.prop("realFinishedAt", java.time.Instant.ofEpochMilli(effectiveRealFinishedAtMs).toString()).comma();
            json.prop("runtimeMs", effectiveRealFinishedAtMs - realStartedAtMs).comma();

            json.name("collapse").objStart();
            if (collapseOutcome != null) {
                json.prop("reason", collapseOutcome.reason).comma();
                json.prop("minute", collapseOutcome.minute).comma();
                json.prop("occurredAt", simulationInstantAt(collapseOutcome.minute)).comma();
                json.prop("airport", collapseOutcome.airportCode).comma();
                json.prop("shipmentId", collapseOutcome.shipmentId).comma();
                json.prop("expectedBags", collapseOutcome.expectedBags).comma();
                json.prop("deliveredBags", collapseOutcome.deliveredBags).comma();
                json.prop("currentLoad", collapseOutcome.currentLoad).comma();
                json.prop("maxCapacity", collapseOutcome.maxCapacity);
            } else {
                json.prop("reason", "").comma();
                json.prop("minute", -1).comma();
                json.prop("occurredAt", "").comma();
                json.prop("airport", "").comma();
                json.prop("shipmentId", "").comma();
                json.prop("expectedBags", 0).comma();
                json.prop("deliveredBags", 0).comma();
                json.prop("currentLoad", 0).comma();
                json.prop("maxCapacity", 0);
            }
            json.objEnd().comma();

            // cancelledFlightIds
            json.name("cancelledFlightIds").arrayStart();
            List<String> visibleCancellations = new ArrayList<>(cancellations);
            for (String pending : pendingCancellations) {
                if (!visibleCancellations.contains(pending)) visibleCancellations.add(pending);
            }
            for (CancellationRequest request : queuedCancellationRequests) {
                String pending = "PENDING@" + request.input();
                if (!visibleCancellations.contains(pending)) visibleCancellations.add(pending);
            }
            int ci = 0;
            for (String cid : visibleCancellations) {
                json.value(cid);
                if (++ci < visibleCancellations.size()) json.comma();
            }
            json.arrayEnd().comma();

            // metrics
            json.name("metrics").objStart();
            json.prop("shipments", planMetrics.totalShipments).comma();
            json.prop("processedShipments", processedShipmentCount).comma();
            json.prop("queuedShipments", queue.size()).comma();
            json.prop("plannedShipments", planMetrics.plannedShipments).comma();
            json.prop("onTimeShipments", planMetrics.onTimeShipments).comma();
            json.prop("deliveredShipments", planMetrics.deliveredShipments).comma();
            json.prop("deliveredOnTimeShipments", planMetrics.deliveredOnTimeShipments).comma();
            json.prop("unplannedShipments", planMetrics.unplannedShipments).comma();
            json.prop("lateShipments", planMetrics.lateShipments).comma();
            json.prop("inTransitShipments", planMetrics.inTransitShipments).comma();
            json.prop("firstWarehouseShipments", planMetrics.firstWarehouseShipments).comma();
            json.prop("totalBags", planMetrics.totalBags).comma();
            json.prop("plannedBags", planMetrics.plannedBags).comma();
            json.prop("onTimeBags", planMetrics.onTimeBags).comma();
            json.prop("deliveredBags", planMetrics.deliveredBags).comma();
            json.prop("deliveredOnTimeBags", planMetrics.deliveredOnTimeBags).comma();
            json.prop("unplannedBags", planMetrics.unplannedBags).comma();
            json.prop("lateBags", planMetrics.lateBags).comma();
            json.prop("inTransitBags", planMetrics.inTransitBags).comma();
            json.prop("firstWarehouseBags", planMetrics.firstWarehouseBags).comma();
            json.prop("planningComplete", planMetrics.planningComplete).comma();
            json.prop("deliveryCompleteWithinSimulation", planMetrics.deliveryCompleteWithinSimulation).comma();
            json.prop("usedFlights", usedFlights).comma();
            json.prop("payloadShipments", shipmentPayload.size()).comma();
            json.prop("pendingCancellations", pendingCancellations.size() + queuedCancellationRequests.size()).comma();
            json.prop("fitnessInitial", 0).comma();
            json.prop("fitnessFinal", 0).comma();
            json.prop("iterations", 0).comma();
            json.prop("globalImprovements", 0).comma();
            json.prop("acceptedBySa", 0);
            json.objEnd().comma();

            json.name("lastPlanningMetrics").objStart();
            writeMetricsFields(
                    json,
                    lastPlanningMetrics,
                    lastPlanningUsedFlights,
                    lastPlanningMetrics.totalShipments,
                    Math.max(0, lastPlanningMetrics.unplannedShipments));
            json.objEnd().comma();

            // airports
            json.name("airports").arrayStart();
            for (int i = 0; i < airports.size(); i++) {
                Airport a = airports.get(i);
                int airportLoad = isBatchScenario()
                        ? snapshotAirportLoads.getOrDefault(a.getCode(), 0)
                        : a.getCurrentLoad();
                double util = ratio(airportLoad, a.getMaxCapacity());
                json.objStart();
                json.prop("code", a.getCode()).comma();
                json.prop("city", a.getCity()).comma();
                json.prop("country", a.getCountry()).comma();
                json.prop("continent", a.getContinent()).comma();
                json.prop("latitude", a.getLatitude()).comma();
                json.prop("longitude", a.getLongitude()).comma();
                json.prop("gmtOffset", a.getGmtOffset()).comma();
                json.prop("maxCapacity", a.getMaxCapacity()).comma();
                json.prop("peakLoad", airportLoad).comma();
                json.prop("historicalPeakLoad", isBatchScenario()
                        ? historicalAirportPeakLoads.getOrDefault(a.getCode(), 0)
                        : 0).comma();
                json.prop("finalLoad", airportLoad).comma();
                json.prop("utilization", util).comma();
                json.prop("status", status(util));
                json.objEnd();
                if (i < airports.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // flights
            json.name("flights").arrayStart();
            for (int i = 0; i < used.size(); i++) {
                Flight f = used.get(i);
                double util = ratio(f.getAssignedLoad(), f.getMaxCapacity());
                json.objStart();
                json.prop("id", f.getFlightId()).comma();
                json.prop("origin", f.getOriginCode()).comma();
                json.prop("destination", f.getDestCode()).comma();
                json.prop("dayOffset", f.getDayOffset()).comma();
                json.prop("departureMinute", f.getDepartureMinute()).comma();
                json.prop("arrivalMinute", f.getArrivalMinute()).comma();
                json.prop("absoluteDepartureMinute", f.absoluteDepartureMinute()).comma();
                json.prop("absoluteArrivalMinute", f.absoluteArrivalMinute()).comma();
                json.prop("assignedLoad", f.getAssignedLoad()).comma();
                json.prop("maxCapacity", f.getMaxCapacity()).comma();
                json.prop("utilization", util).comma();
                json.prop("status", status(util)).comma();
                json.prop("scheduleStatus",
                        isCancelled(f) ? "CANCELLED" :
                                isPendingCancellation(f) ? "PENDING_CANCEL" :
                                        f.absoluteDepartureMinute() <= tick ? "IN_PROGRESS" : "QUEUED");
                json.objEnd();
                if (i < used.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // shipments
            json.name("shipments").arrayStart();
            for (int i = 0; i < shipmentPayload.size(); i++) {
                writeShipment(json, shipmentPayload.get(i));
                if (i < shipmentPayload.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // airportEvents
            List<RealtimeEvent> sortedEvents = eventsForSnapshot(snapshotStart, snapshotEnd);
            json.name("airportEvents").arrayStart();
            for (int i = 0; i < sortedEvents.size(); i++) {
                RealtimeEvent e = sortedEvents.get(i);
                json.objStart();
                json.prop("minute", e.minute).comma();
                json.prop("airport", e.airport).comma();
                json.prop("delta", e.delta).comma();
                json.prop("type", e.type);
                json.objEnd();
                if (i < sortedEvents.size() - 1) json.comma();
            }
            json.arrayEnd();

            json.objEnd();
            String snapshot = json.toString();
            lastSnapshotJson = snapshot;
            return snapshot;
        }

        private PlanningMetrics calculatePlanningMetrics(List<Shipment> source) {
            return calculatePlanningMetrics(source, maxTick);
        }

        private PlanningMetrics calculatePlanningMetrics(List<Shipment> source, int deliveredByMinute) {
            Map<String, ShipmentRollup> rollups = new LinkedHashMap<>();
            for (Shipment shipment : source) {
                String rootId = shipment.isSplitPart()
                        ? shipment.getParentShipmentId()
                        : shipment.getShipmentId();
                rollups.computeIfAbsent(rootId, ignored -> new ShipmentRollup(shipment))
                        .include(shipment, deliveredByMinute);
            }

            PlanningMetrics metrics = new PlanningMetrics();
            metrics.totalShipments = rollups.size();
            for (ShipmentRollup rollup : rollups.values()) {
                int total = Math.max(0, rollup.totalBags);
                int planned = Math.min(total, rollup.plannedBags);
                int onTime = Math.min(total, rollup.onTimeBags);
                int delivered = Math.min(total, rollup.deliveredBags);
                int deliveredOnTime = Math.min(total, rollup.deliveredOnTimeBags);
                int firstWarehouse = Math.min(total, rollup.firstWarehouseBags);
                int inTransit = Math.min(total, rollup.inTransitBags);

                metrics.totalBags += total;
                metrics.plannedBags += planned;
                metrics.onTimeBags += onTime;
                metrics.deliveredBags += delivered;
                metrics.deliveredOnTimeBags += deliveredOnTime;
                metrics.firstWarehouseBags += firstWarehouse;
                metrics.inTransitBags += inTransit;

                if (total > 0 && planned >= total) {
                    metrics.plannedShipments++;
                    if (delivered >= total) {
                        metrics.deliveredShipments++;
                    } else if (firstWarehouse >= total) {
                        metrics.firstWarehouseShipments++;
                    } else {
                        metrics.inTransitShipments++;
                    }
                }
                if (total > 0 && onTime >= total) {
                    metrics.onTimeShipments++;
                }
                if (total > 0 && deliveredOnTime >= total) {
                    metrics.deliveredOnTimeShipments++;
                }
            }

            metrics.unplannedShipments = Math.max(0, metrics.totalShipments - metrics.plannedShipments);
            metrics.unplannedBags = Math.max(0, metrics.totalBags - metrics.plannedBags);
            metrics.lateShipments = Math.max(0, metrics.plannedShipments - metrics.onTimeShipments);
            metrics.lateBags = Math.max(0, metrics.plannedBags - metrics.onTimeBags);
            metrics.planningComplete = metrics.totalShipments > 0
                    && metrics.plannedShipments == metrics.totalShipments
                    && metrics.plannedBags == metrics.totalBags;
            metrics.deliveryCompleteWithinSimulation = metrics.totalShipments > 0
                    && metrics.deliveredShipments == metrics.totalShipments
                    && metrics.deliveredBags == metrics.totalBags;
            return metrics;
        }

        private static class ShipmentRollup {
            int totalBags;
            int plannedBags;
            int onTimeBags;
            int deliveredBags;
            int deliveredOnTimeBags;
            int firstWarehouseBags;
            int inTransitBags;

            ShipmentRollup(Shipment seed) {
                totalBags = Math.max(0, seed.getOriginalSuitcaseCount());
            }

            void include(Shipment shipment, int simulationEndMinute) {
                totalBags = Math.max(totalBags, shipment.getOriginalSuitcaseCount());
                if (!shipment.isPlanned()) return;

                int bags = Math.max(0, shipment.getSuitcaseCount());
                plannedBags += bags;
                if (shipment.isOnTime()) {
                    onTimeBags += bags;
                }
                if (shipment.getEstimatedArrival() <= 0) {
                    firstWarehouseBags += bags;
                    return;
                }
                if (shipment.getEstimatedArrival() <= simulationEndMinute) {
                    deliveredBags += bags;
                    if (shipment.isOnTime()) {
                        deliveredOnTimeBags += bags;
                    }
                    return;
                }

                Route route = shipment.getAssignedRoute();
                if (route == null || !route.isValid() || route.getFlights().isEmpty()) {
                    firstWarehouseBags += bags;
                    return;
                }

                int firstDeparture = route.getFlights().get(0).absoluteDepartureMinute();
                if (firstDeparture > simulationEndMinute) {
                    firstWarehouseBags += bags;
                } else {
                    inTransitBags += bags;
                }
            }
        }

        private static class PlanningMetrics {
            int totalShipments;
            int plannedShipments;
            int onTimeShipments;
            int deliveredShipments;
            int deliveredOnTimeShipments;
            int unplannedShipments;
            int lateShipments;
            int firstWarehouseShipments;
            int inTransitShipments;
            int totalBags;
            int plannedBags;
            int onTimeBags;
            int deliveredBags;
            int deliveredOnTimeBags;
            int unplannedBags;
            int lateBags;
            int firstWarehouseBags;
            int inTransitBags;
            boolean planningComplete;
            boolean deliveryCompleteWithinSimulation;
        }

        private static class CollapseOutcome {
            final String reason;
            final int minute;
            final String airportCode;
            final String shipmentId;
            final int expectedBags;
            final int deliveredBags;
            final int currentLoad;
            final int maxCapacity;

            private CollapseOutcome(String reason, int minute, String airportCode,
                                    String shipmentId, int expectedBags, int deliveredBags,
                                    int currentLoad, int maxCapacity) {
                this.reason = reason;
                this.minute = minute;
                this.airportCode = airportCode;
                this.shipmentId = shipmentId;
                this.expectedBags = expectedBags;
                this.deliveredBags = deliveredBags;
                this.currentLoad = currentLoad;
                this.maxCapacity = maxCapacity;
            }

            static CollapseOutcome capacity(int minute, Airport airport, int currentLoad) {
                return new CollapseOutcome(
                        "WAREHOUSE_CAPACITY", minute, airport.getCode(), "", 0, 0,
                        currentLoad, airport.getMaxCapacity());
            }

            static CollapseOutcome deadline(int minute, Shipment shipment,
                                            int expectedBags, int deliveredBags) {
                return new CollapseOutcome(
                        "DELIVERY_DEADLINE", minute, "", shipment.getShipmentId(),
                        expectedBags, deliveredBags, 0, 0);
            }
        }

        private int eventPriority(String type) {
            return switch (type) {
                case "flight_departure", "connection_departure", "final_pickup" -> 0;
                case "shipment_created", "connection_arrival", "final_arrival" -> 1;
                default -> 2;
            };
        }

        private void writeShipment(Json json, Shipment s) {
            json.objStart();
            json.prop("id", s.getShipmentId()).comma();
            json.prop("clientId", s.getClientId()).comma();
            json.prop("origin", s.getOriginCode()).comma();
            json.prop("destination", s.getDestCode()).comma();
            json.prop("requestMinute", s.getRequestMinute()).comma();
            json.prop("suitcases", s.getSuitcaseCount()).comma();
            json.prop("planned", s.isPlanned()).comma();
            json.prop("onTime", s.isOnTime()).comma();
            json.prop("estimatedArrival", s.getEstimatedArrival()).comma();
            json.prop("delayMinutes", s.getDelayMinutes()).comma();
            json.name("flightIds").arrayStart();
            Route route = s.getAssignedRoute();
            if (route != null) {
                List<Flight> rf = route.getFlights();
                for (int i = 0; i < rf.size(); i++) {
                    json.value(rf.get(i).getFlightId());
                    if (i < rf.size() - 1) json.comma();
                }
            }
            json.arrayEnd().comma();
            json.name("flightLegs").arrayStart();
            if (route != null) {
                List<Flight> rf = route.getFlights();
                for (int i = 0; i < rf.size(); i++) {
                    Flight f = rf.get(i);
                    double util = ratio(f.getAssignedLoad(), f.getMaxCapacity());
                    json.objStart();
                    json.prop("flightId", f.getFlightId()).comma();
                    json.prop("origin", f.getOriginCode()).comma();
                    json.prop("destination", f.getDestCode()).comma();
                    json.prop("dayOffset", f.getDayOffset()).comma();
                    json.prop("departureMinute", f.getDepartureMinute()).comma();
                    json.prop("arrivalMinute", f.getArrivalMinute()).comma();
                    json.prop("absoluteDepartureMinute", f.absoluteDepartureMinute()).comma();
                    json.prop("absoluteArrivalMinute", f.absoluteArrivalMinute()).comma();
                    json.prop("assignedLoad", f.getAssignedLoad()).comma();
                    json.prop("maxCapacity", f.getMaxCapacity()).comma();
                    json.prop("utilization", util);
                    json.objEnd();
                    if (i < rf.size() - 1) json.comma();
                }
            }
            json.arrayEnd();
            json.objEnd();
        }

        private String scenarioLabel() {
            if (isCollapseScenario()) return "Colapso";
            return "SIMULACION_LOTES".equals(scenario) ? "Simulación 5 días" : "Tiempo real";
        }

        private String simulationInstantAt(int minute) {
            return LocalDate.parse(startDate, RAW_DATE)
                    .atStartOfDay(originZone)
                    .plusMinutes(minute)
                    .toInstant()
                    .toString();
        }

        private void captureLastPlanningSummary(List<Shipment> plannedScope, int deliveredByMinute) {
            List<Shipment> scope = plannedScope == null
                    ? Collections.emptyList()
                    : new ArrayList<>(plannedScope);
            lastPlanningMetrics = calculatePlanningMetrics(scope, deliveredByMinute);
            lastPlanningUsedFlights = countUsedFlights(scope);
        }

        private int countUsedFlights(List<Shipment> source) {
            Set<String> usedFlightIds = new HashSet<>();
            for (Shipment shipment : source) {
                Route route = shipment.getAssignedRoute();
                if (route == null || !route.isValid()) continue;
                for (Flight flight : route.getFlights()) {
                    usedFlightIds.add(flight.getFlightId());
                }
            }
            return usedFlightIds.size();
        }

        private void writeMetricsFields(
                Json json,
                PlanningMetrics metrics,
                int usedFlights,
                int payloadShipments,
                int queuedShipments) {
            json.prop("shipments", metrics.totalShipments).comma();
            json.prop("processedShipments", metrics.totalShipments).comma();
            json.prop("queuedShipments", queuedShipments).comma();
            json.prop("plannedShipments", metrics.plannedShipments).comma();
            json.prop("onTimeShipments", metrics.onTimeShipments).comma();
            json.prop("deliveredShipments", metrics.deliveredShipments).comma();
            json.prop("deliveredOnTimeShipments", metrics.deliveredOnTimeShipments).comma();
            json.prop("unplannedShipments", metrics.unplannedShipments).comma();
            json.prop("lateShipments", metrics.lateShipments).comma();
            json.prop("inTransitShipments", metrics.inTransitShipments).comma();
            json.prop("firstWarehouseShipments", metrics.firstWarehouseShipments).comma();
            json.prop("totalBags", metrics.totalBags).comma();
            json.prop("plannedBags", metrics.plannedBags).comma();
            json.prop("onTimeBags", metrics.onTimeBags).comma();
            json.prop("deliveredBags", metrics.deliveredBags).comma();
            json.prop("deliveredOnTimeBags", metrics.deliveredOnTimeBags).comma();
            json.prop("unplannedBags", metrics.unplannedBags).comma();
            json.prop("lateBags", metrics.lateBags).comma();
            json.prop("inTransitBags", metrics.inTransitBags).comma();
            json.prop("firstWarehouseBags", metrics.firstWarehouseBags).comma();
            json.prop("planningComplete", metrics.planningComplete).comma();
            json.prop("deliveryCompleteWithinSimulation", metrics.deliveryCompleteWithinSimulation).comma();
            json.prop("usedFlights", usedFlights).comma();
            json.prop("payloadShipments", payloadShipments).comma();
            json.prop("pendingCancellations", 0).comma();
            json.prop("fitnessInitial", 0).comma();
            json.prop("fitnessFinal", 0).comma();
            json.prop("iterations", 0).comma();
            json.prop("globalImprovements", 0).comma();
            json.prop("acceptedBySa", 0);
        }

        private double ratio(int value, int total) {
            return total <= 0 ? 0.0 : Math.min(1.5, (double) value / total);
        }

        private String status(double utilization) {
            if (utilization < 0.70) return "green";
            if (utilization < 0.90) return "yellow";
            return "red";
        }
    }

    private record RealtimeEvent(int minute, String airport, int delta, String type) {}

    private record CancellationRequest(String input) {
        boolean matchesInput(String candidate) {
            return input.equalsIgnoreCase(candidate == null ? "" : candidate.trim());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Builder JSON mínimo (sin dependencias externas)
    // ════════════════════════════════════════════════════════════════════════

    private static class Json {
        private final StringBuilder sb = new StringBuilder(256 * 1024);

        Json objStart()   { sb.append('{'); return this; }
        Json objEnd()     { sb.append('}'); return this; }
        Json arrayStart() { sb.append('['); return this; }
        Json arrayEnd()   { sb.append(']'); return this; }
        Json comma()      { sb.append(','); return this; }
        Json name(String n) { value(n); sb.append(':'); return this; }

        Json prop(String n, String v)  { name(n); value(v);                               return this; }
        Json prop(String n, int v)     { name(n); sb.append(v);                           return this; }
        Json prop(String n, long v)    { name(n); sb.append(v);                           return this; }
        Json prop(String n, double v)  { name(n); sb.append(String.format(Locale.US, "%.6f", v)); return this; }
        Json prop(String n, boolean v) { name(n); sb.append(v);                           return this; }

        Json value(String v) {
            sb.append('"');
            if (v != null) {
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    switch (c) {
                        case '"'  -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default   -> { if (c < 32) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                    }
                }
            }
            sb.append('"');
            return this;
        }

        @Override public String toString() { return sb.toString(); }
    }
}
