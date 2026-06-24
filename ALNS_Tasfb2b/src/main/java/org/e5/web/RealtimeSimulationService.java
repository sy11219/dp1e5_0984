package org.e5.web;

import org.e5.config.OperationParameters;
import org.e5.db.FlightPlanService;
import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;
import org.e5.parser.ShipmentParser;
import org.e5.planner.ALNS;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final ScheduledExecutorService realtimeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tasf-realtime-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String sharedRealtimeSessionId;
    private volatile String sharedSimulationSessionId;
    private volatile String lastCreatedSessionId;

    public RealtimeSimulationService() {
        realtimeScheduler.scheduleWithFixedDelay(
                this::advanceSharedRealtimeIfDue,
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

    public synchronized String start(String startDate, int days, String timeZone) throws Exception {
        return start(startDate, days, "00:00", timeZone);
    }

    public synchronized String start(String startDate, int days, String startTime, String timeZone) throws Exception {
        validate(startDate, days, false);
        RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
        if (current != null && !current.completed) return current.snapshotJson();
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
        return current == null ? "{}" : current.snapshotJson();
    }

    private void advanceSharedRealtimeIfDue() {
        try {
            RealtimeSession current = sharedRealtimeSessionId == null ? null : sessions.get(sharedRealtimeSessionId);
            if (current == null || current.completed) return;
            current.advanceRealtimeIfDue();
        } catch (Exception e) {
            System.err.printf("[Tiempo real] Error en scheduler: %s%n", e.getMessage());
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
        validate(startDate, days, true);
        int startOffsetMinutes = parseStartTime(startTime);
        String json = createSession(startDate, days, "SIMULACION_LOTES", startOffsetMinutes, timeZone);
        sharedSimulationSessionId = lastCreatedSessionId;
        return json;
    }

    public String currentSimulation() {
        RealtimeSession current = sharedSimulationSessionId == null ? null : sessions.get(sharedSimulationSessionId);
        return current == null ? "{}" : current.snapshotJson();
    }

    public synchronized String stopBatchSimulation(String id) {
        RealtimeSession session = require(id);
        if (!"SIMULACION_LOTES".equals(session.scenario)) {
            throw new IllegalArgumentException("La sesion indicada no es una simulacion por lotes.");
        }

        sessions.remove(id);
        if (id.equals(sharedSimulationSessionId)) {
            sharedSimulationSessionId = null;
        }
        return "{}";
    }

    public String state(String id) {
        return require(id).snapshotJson();
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
        RealtimeSession session = require(id);
        if ("SIMULACION_LOTES".equals(session.scenario)) {
            if (expectedTick >= 0 && session.tick != expectedTick) return session.snapshotJson();
            session.advanceBatch(BATCH_MINUTES);
        } else {
            session.advanceRealtimeIfDue();
        }
        return session.snapshotJson();
    }

    /**
     * Cancela un vuelo futuro y replanifica.
     * Rechaza con excepción si el vuelo ya despegó en el tick actual.
     */
    public String cancelFlight(String id, String flightId) {
        RealtimeSession session = require(id);
        session.cancel(flightId, flightPlanService);
        return session.snapshotJson();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internos
    // ════════════════════════════════════════════════════════════════════════

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
        return createSession(startDate, days, scenario, 0, ZoneId.systemDefault().getId());
    }

    private String createSession(String startDate, int days, String scenario,
                                 int startOffsetMinutes, String timeZone) throws Exception {
        ZoneId sessionZone = resolveZone(timeZone);
        AirportParser airportParser = new AirportParser();
        List<Airport> airports = airportParser.parse();
        Map<String, Airport> airportMap = new LinkedHashMap<>();
        for (Airport airport : airports) {
            airport.resetLoad();
            airportMap.put(airport.getCode(), airport);
        }

        FlightPlanParser flightParser = new FlightPlanParser();
        List<Flight> flights = flightParser.parseScheduledFromDatabase(startDate, days + 2, airportMap);
        for (Flight flight : flights) flight.resetLoad();
        flights.sort(Comparator
                .comparingInt(Flight::absoluteDepartureMinute)
                .thenComparing(Flight::getFlightId));

        ShipmentParser shipmentParser = new ShipmentParser(airportMap);
        int shipmentDaysToLoad = startOffsetMinutes > 0 ? days + 1 : days;
        int simulationEndMinute = startOffsetMinutes + days * 1440;
        List<Shipment> shipments = shipmentParser.parseAllFromDatabase(startDate, shipmentDaysToLoad, sessionZone);
        shipments.removeIf(s -> s.getRequestMinute() < startOffsetMinutes
                || s.getRequestMinute() >= simulationEndMinute);
        shipments.sort(Comparator.comparingInt(Shipment::getRequestMinute));

        RealtimeSession session = new RealtimeSession(
                startDate, days, scenario, startOffsetMinutes, sessionZone,
                airports, airportMap, flights, shipments);
        sessions.put(session.id, session);
        lastCreatedSessionId = session.id;
        return session.snapshotJson();
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
        if (session == null) throw new IllegalArgumentException("Sesion no encontrada.");
        return session;
    }

    private void validate(String startDate, int days, boolean batch) {
        if (startDate == null || !startDate.matches("\\d{8}"))
            throw new IllegalArgumentException("La fecha inicial debe tener formato aaaammdd.");
        LocalDate.parse(startDate, RAW_DATE);
        if (batch) {
            if (days != 5)
                throw new IllegalArgumentException("Solo se permite simular 5 dias.");
        } else {
            if (days < 1 || days > 7)
                throw new IllegalArgumentException("Tiempo real permite operar entre 1 y 7 dias.");
        }
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
        final List<Airport> airports;
        final Map<String, Airport> airportMap;
        final List<Flight> flights;
        final List<Shipment> shipments;

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

        final long realStartedAtMs = System.currentTimeMillis();
        int tick;
        final int maxTick;
        boolean completed = false;
        int lastBatchStart = -1;
        int lastBatchEnd = -1;
        int batchCount = 0;
        long lastBatchRuntimeMs = 0;
        long lastRealtimeAdvanceMs = 0;
        long lastRealtimeExecutionMs = 0;
        long lastRealtimeShipmentRefreshMs = 0;
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
                        List<Flight> flights, List<Shipment> shipments) {
            this.startDate  = startDate;
            this.days       = days;
            this.scenario   = scenario;
            this.startOffsetMinutes = startOffsetMinutes;
            this.originZone = originZone;
            this.airports   = airports;
            this.airportMap = airportMap;
            this.flights    = flights;
            this.shipments  = shipments;
            for (Shipment s : shipments) {
                knownShipmentIds.add(s.getShipmentId());
                shipmentsByMinute.computeIfAbsent(s.getRequestMinute(), k -> new ArrayList<>()).add(s);
            }
            this.tick = startOffsetMinutes;
            this.maxTick = startOffsetMinutes + days * 1440;
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
            return "SIMULACION_LOTES".equals(scenario);
        }

        private long planningIntervalMs() {
            return isBatchScenario() ? BATCH_INTERVAL_MS : REALTIME_EXECUTION_INTERVAL_MS;
        }

        private int planningIntervalMinutes() {
            return Math.max(1, (int) Math.round(planningIntervalMs() / (double) MINUTE_MS));
        }

        private int planningTimeScale() {
            return isBatchScenario() ? SIMULATION_PLANNING_K : REALTIME_PLANNING_K;
        }

        private int planningWindowMinutes() {
            return isBatchScenario() ? BATCH_MINUTES : REALTIME_PLANNING_WINDOW_MINUTES;
        }

        private boolean planningStable() {
            return lastBatchRuntimeMs == 0 || lastBatchRuntimeMs < planningIntervalMs();
        }

        private int plannedCompletionTick() {
            int completionTick = maxTick;
            List<Shipment> source = isBatchScenario() ? todosLosShipmentsProcesados : processed;

            for (Shipment shipment : source) {
                Route route = shipment.getAssignedRoute();
                if (route == null || !route.isValid() || route.getFlights().isEmpty()) continue;

                Flight lastFlight = route.getFlights().get(route.getFlights().size() - 1);
                completionTick = Math.max(
                        completionTick,
                        lastFlight.absoluteArrivalMinute() + OperationParameters.FINAL_PICKUP_WAIT_MINUTES);
            }

            return completionTick;
        }

        private int effectiveMaxTick() {
            return isBatchScenario() ? plannedCompletionTick() : maxTick;
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
        synchronized void advanceBatch(int steps) {
            if (completed) return;

            long batchStartMs = System.currentTimeMillis();
            int batchStart = tick;
            int batchEnd   = Math.min(tick + steps, maxTick);
            lastBatchStart = batchStart;
            lastBatchEnd   = batchEnd;

            // 1. Recoger envíos que caen en esta ventana [batchStart, batchEnd)
            List<Shipment> loteShipments = new ArrayList<>();
            for (int min = batchStart; min < batchEnd; min++) {
                List<Shipment> en = shipmentsByMinute.getOrDefault(min, Collections.emptyList());
                loteShipments.addAll(en);
            }

            // 2. Vuelos disponibles: no cancelados y que aún no han despegado
            List<Flight> vuelosDisponibles = availableFlightsFrom(batchStart);

            System.out.printf("[Lote] Ventana %d–%d | %d envíos | %d vuelos disponibles%n",
                    batchStart, batchEnd, loteShipments.size(), vuelosDisponibles.size());

            // 3. Ejecutar ALNS incremental (conserva capacidad de lotes anteriores)
            if (!loteShipments.isEmpty()) {
                int n       = loteShipments.size();
                int iters   = Math.max(80, Math.min(400, n * 4));
                int seg     = Math.max(10, iters / 15);
                int nDestr  = Math.max(3, Math.min(n / 5 + 5, 60));

                ALNS alns = new ALNS(iters, seg, nDestr, 300.0, 0.995, 2,
                        9.0, 3.0, 0.0, 0.8);

                Map<String, Route> resultado = alns.ejecutarIncremental(
                        loteShipments, vuelosDisponibles, airportMap);

                registrarEventosLote(loteShipments, resultado);

                solucionGlobal.putAll(resultado);
                processed.addAll(loteShipments);
                todosLosShipmentsProcesados.addAll(loteShipments);

                System.out.printf("[Lote] Rutas: %d / %d%n", resultado.size(), n);
            }

            batchCount++;
            tick = batchEnd;
            if (tick >= maxTick) {
                if (!queue.isEmpty()) planQueue();
                tick = Math.max(tick, plannedCompletionTick());
                completed = true;
                System.out.println("[Lote] Simulación completada.");
            }

            events.add(new RealtimeEvent(tick, "SYSTEM", loteShipments.size(), "batch_complete"));

            lastBatchRuntimeMs = System.currentTimeMillis() - batchStartMs;
            visualWindowStartedAtMs = batchStartMs;
            visualWindowStartTick = batchStart;
            visualWindowEndTick = tick;
            System.out.printf("[Lote] Completado en %d ms (animación sugerida: %d ms)%n",
                    lastBatchRuntimeMs, BATCH_INTERVAL_MS);
            System.out.printf("[Planificacion fija][Lote] Sa=%d ms | K=%d | Sc=%d min | Ta=%d ms | estable=%s%n",
                    planningIntervalMs(), planningTimeScale(), planningWindowMinutes(), lastBatchRuntimeMs, planningStable());
        }

        private void registrarEventosLote(List<Shipment> loteShipments, Map<String, Route> resultado) {
            Set<String> createdEvents = new HashSet<>();
            Set<String> routeEvents = new HashSet<>();

            for (Shipment s : loteShipments) {
                if (!s.isSplitPart() && createdEvents.add(s.getShipmentId())) {
                    events.add(new RealtimeEvent(s.getRequestMinute(),
                            s.getOriginCode(), s.getSuitcaseCount(), "shipment_created"));
                }
            }

            for (Map.Entry<String, Route> entry : resultado.entrySet()) {
                Shipment s = findShipmentById(entry.getKey(), loteShipments);
                if (s != null) {
                    registrarEventosRuta(s, entry.getValue(), routeEvents);
                }
            }
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

            events.add(new RealtimeEvent(
                    rutaVuelos.get(0).absoluteDepartureMinute(),
                    s.getOriginCode(), -s.getSuitcaseCount(), "flight_departure"));

            for (int i = 0; i < rutaVuelos.size(); i++) {
                Flight f = rutaVuelos.get(i);
                boolean last = (i == rutaVuelos.size() - 1);
                if (last) {
                    int arrivalMinute = f.absoluteArrivalMinute();
                    events.add(new RealtimeEvent(arrivalMinute,
                            f.getDestCode(), s.getSuitcaseCount(), "final_arrival"));
                    events.add(new RealtimeEvent(
                            arrivalMinute + OperationParameters.FINAL_PICKUP_WAIT_MINUTES,
                            f.getDestCode(), -s.getSuitcaseCount(), "final_pickup"));
                    continue;
                }

                events.add(new RealtimeEvent(f.absoluteArrivalMinute(),
                        f.getDestCode(), s.getSuitcaseCount(), "connection_arrival"));
                Flight next = rutaVuelos.get(i + 1);
                events.add(new RealtimeEvent(next.absoluteDepartureMinute(),
                        f.getDestCode(), -s.getSuitcaseCount(), "connection_departure"));
            }
        }

        // ── TIEMPO_REAL: avance tick a tick ───────────────────────────────────

        synchronized void advance(int steps) {
            if (completed) return;
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
            if (completed) return false;
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
            ALNS alns   = new ALNS(iters, segment, -1, 80.0, 0.96, 2, 9.0, 3.0, 0.0, 0.8);
            Map<String, Route> result = alns.ejecutar(queue, availableFlightsFrom(tick), airportMap);
            events.add(new RealtimeEvent(tick, "SYSTEM", result.size(), "replan"));
            processed.addAll(queue);
            for (Shipment s : queue) queuedShipmentIds.remove(s.getShipmentId());
            queue.clear();
        }

        private void applyPendingRealtimeCancellations() {
            if (pendingCancellations.isEmpty()) return;
            List<String> pending = new ArrayList<>(pendingCancellations);
            pendingCancellations.clear();
            for (String flightId : pending) {
                cancellations.add(flightId);
                requeueAffectedByRealtimeCancellation(flightId);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
            }
        }

        private void requeueAffectedByRealtimeCancellation(String flightId) {
            Iterator<Shipment> iterator = processed.iterator();
            while (iterator.hasNext()) {
                Shipment s = iterator.next();
                Route route = s.getAssignedRoute();
                if (route == null) continue;
                boolean usesCancelled = route.getFlights().stream()
                        .anyMatch(f -> f.getFlightId().equals(flightId));
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
            Flight toCancel = resolveCancellationFlight(flightId);
            if (toCancel == null)
                throw new IllegalArgumentException("Vuelo no encontrado: " + flightId);

            try {
                flightPlanService.cancelFlight(toCancel.getFlightId());
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo marcar el vuelo como CANCELED en BD: " + toCancel.getFlightId(), e);
            }

            String cancelledFlightId = toCancel.getFlightId();
            if (requested != null && !requested.getFlightId().equals(cancelledFlightId)) {
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancel_redirected"));
            }

            if ("SIMULACION_LOTES".equals(scenario)) {
                cancellations.add(cancelledFlightId);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
                cancelBatch(cancelledFlightId);
            } else {
                pendingCancellations.add(cancelledFlightId);
                events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled_pending"));
            }
        }

        private Flight resolveCancellationFlight(String input) {
            Flight requested = findFlight(input);
            if (requested != null && requested.absoluteDepartureMinute() - tick > 60) {
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
            int minDeparture = tick + 61;
            return flights.stream()
                    .filter(f -> sameRecurringFlight(recurringReference, f))
                    .filter(f -> f.absoluteDepartureMinute() >= minDeparture)
                    .filter(f -> !cancellations.contains(f.getFlightId()))
                    .filter(f -> !pendingCancellations.contains(f.getFlightId()))
                    .min(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                    .orElse(null);
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

        synchronized void markCancelled(String flightId) {
            Flight toCancel = findFlight(flightId);
            if (toCancel == null)
                throw new IllegalArgumentException("Vuelo no encontrado: " + flightId);
            if (toCancel.absoluteDepartureMinute() <= tick)
                throw new IllegalArgumentException("No se puede cancelar un vuelo que ya inicio.");
            cancellations.add(flightId);
            events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));
        }

        /**
         * Cancelación para SIMULACION_LOTES:
         *   1. Afectados directos → ALNS rápido.
         *   2. Reoptimización global de todos los ya planificados.
         */
        private void cancelBatch(String flightId) {
            // Afectados directos
            List<Shipment> afectados = new ArrayList<>();
            for (Shipment s : todosLosShipmentsProcesados) {
                Route route = solucionGlobal.get(s.getShipmentId());
                if (route == null) continue;
                boolean usa = route.getFlights().stream()
                        .anyMatch(f -> f.getFlightId().equals(flightId));
                if (usa) {
                    afectados.add(s);
                    s.resetPlanningState();
                    solucionGlobal.remove(s.getShipmentId());
                }
            }

            System.out.printf("[Cancelación] Vuelo %s | %d envíos afectados directamente%n",
                    flightId, afectados.size());

            List<Flight> disponibles = availableFlightsFrom(tick);

            // Paso 1: replanificar afectados directos
            if (!afectados.isEmpty()) {
                int iters = Math.max(50, Math.min(200, afectados.size() * 5));
                ALNS alns = new ALNS(iters, Math.max(10, iters / 5),
                        Math.max(3, afectados.size() / 5), 150.0, 0.98, 2,
                        9.0, 3.0, 0.0, 0.8);
                Map<String, Route> replanDirectos = alns.ejecutar(afectados, disponibles, airportMap);
                solucionGlobal.putAll(replanDirectos);
                System.out.printf("[Cancelación] Replanificados directos: %d / %d%n",
                        replanDirectos.size(), afectados.size());
            }

            // Paso 2: reoptimización global — todos los procesados juntos
            // para aprovechar capacidad liberada y garantizar plazos 1/2 días
            List<Shipment> todosAReoptar = new ArrayList<>(todosLosShipmentsProcesados);
            // Incluir también los pendientes en cola
            todosAReoptar.addAll(queue);

            if (!todosAReoptar.isEmpty()) {
                System.out.printf("[Cancelación] Reoptimizando globalmente %d envíos...%n",
                        todosAReoptar.size());
                for (Shipment s : todosAReoptar) s.resetPlanningState();

                int n       = todosAReoptar.size();
                int iters   = Math.max(120, Math.min(500, n * 3));
                int seg     = Math.max(20, iters / 10);
                int nDestr  = Math.max(5, Math.min(n / 4, 60));

                ALNS alnsGlobal = new ALNS(iters, seg, nDestr, 250.0, 0.995, 2,
                        9.0, 3.0, 0.0, 0.8);
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
            List<Flight> available = new ArrayList<>();
            int start = firstFlightAtOrAfter(flights, fromMinute);
            for (int i = start; i < flights.size(); i++) {
                Flight f = flights.get(i);
                if (!cancellations.contains(f.getFlightId())
                        && !pendingCancellations.contains(f.getFlightId()))
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
            for (Flight f : flights)
                if (f.getFlightId().equals(flightId)) return f;
            return null;
        }

        // ── Snapshot JSON ─────────────────────────────────────────────────────

        synchronized String snapshotJson() {
            Json json = new Json();

            // Para lotes: mostrar lo consumido; para tiempo real: mostrar todo lo conocido desde BD.
            List<Shipment> shipmentSource = isBatchScenario()
                    ? todosLosShipmentsProcesados : shipments;
            int processedShipmentCount = isBatchScenario()
                    ? todosLosShipmentsProcesados.size() : processed.size();

            int planned    = (int) shipmentSource.stream().filter(Shipment::isPlanned).count();
            int onTime     = (int) shipmentSource.stream().filter(Shipment::isOnTime).count();
            int totalBags  = shipments.stream().mapToInt(Shipment::getSuitcaseCount).sum();
            int plannedBags = shipmentSource.stream()
                    .filter(Shipment::isPlanned).mapToInt(Shipment::getSuitcaseCount).sum();
            int usedFlights = (int) flights.stream().filter(f -> f.getAssignedLoad() > 0).count();

            String message = cancellations.isEmpty() ? "" :
                    "Vuelos cancelados: " + String.join(", ", cancellations) +
                    ". Replanificación completada.";

            json.objStart();
            json.prop("simulationId", id).comma();
            json.prop("scenario", scenarioLabel()).comma();
            json.prop("status", completed ? "COMPLETED" : "RUNNING").comma();
            json.prop("days", days).comma();
            json.prop("tick", tick).comma();
            json.prop("maxTick", effectiveMaxTick()).comma();
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
            json.prop("connectionWaitMinutes", OperationParameters.CONNECTION_WAIT_MINUTES).comma();
            json.prop("finalPickupWaitMinutes", OperationParameters.FINAL_PICKUP_WAIT_MINUTES).comma();
            json.prop("batchCount", batchCount).comma();
            json.prop("lastBatchStart", lastBatchStart).comma();
            json.prop("lastBatchEnd", lastBatchEnd).comma();
            json.prop("lastBatchRuntimeMs", lastBatchRuntimeMs).comma();
            json.prop("visualStartTick", visualWindowStartTick).comma();
            json.prop("visualEndTick", visualWindowEndTick).comma();
            json.prop("visualStartedAt", java.time.Instant.ofEpochMilli(visualWindowStartedAtMs).toString()).comma();
            json.prop("realtimeExecutionIntervalMs", REALTIME_EXECUTION_INTERVAL_MS).comma();
            json.prop("pendingCancellationCount", pendingCancellations.size()).comma();
            json.prop("message", message).comma();
            json.prop("simulationStartDateTime", simulationStartInstant).comma();
            json.prop("simulationEndDateTime", simulationEndInstant).comma();
            long realFinishedAtMs = System.currentTimeMillis();
            json.prop("realStartedAt", java.time.Instant.ofEpochMilli(realStartedAtMs).toString()).comma();
            json.prop("realFinishedAt", java.time.Instant.ofEpochMilli(realFinishedAtMs).toString()).comma();
            json.prop("runtimeMs", realFinishedAtMs - realStartedAtMs).comma();

            // cancelledFlightIds
            json.name("cancelledFlightIds").arrayStart();
            List<String> visibleCancellations = new ArrayList<>(cancellations);
            for (String pending : pendingCancellations) {
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
            json.prop("shipments", shipments.size()).comma();
            json.prop("processedShipments", processedShipmentCount).comma();
            json.prop("queuedShipments", queue.size()).comma();
            json.prop("plannedShipments", planned).comma();
            json.prop("onTimeShipments", onTime).comma();
            json.prop("totalBags", totalBags).comma();
            json.prop("plannedBags", plannedBags).comma();
            json.prop("usedFlights", usedFlights).comma();
            json.prop("pendingCancellations", pendingCancellations.size()).comma();
            json.prop("fitnessInitial", 0).comma();
            json.prop("fitnessFinal", 0).comma();
            json.prop("iterations", 0).comma();
            json.prop("globalImprovements", 0).comma();
            json.prop("acceptedBySa", 0);
            json.objEnd().comma();

            // airports
            json.name("airports").arrayStart();
            for (int i = 0; i < airports.size(); i++) {
                Airport a = airports.get(i);
                double util = ratio(a.getCurrentLoad(), a.getMaxCapacity());
                json.objStart();
                json.prop("code", a.getCode()).comma();
                json.prop("city", a.getCity()).comma();
                json.prop("country", a.getCountry()).comma();
                json.prop("continent", a.getContinent()).comma();
                json.prop("latitude", a.getLatitude()).comma();
                json.prop("longitude", a.getLongitude()).comma();
                json.prop("gmtOffset", a.getGmtOffset()).comma();
                json.prop("maxCapacity", a.getMaxCapacity()).comma();
                json.prop("peakLoad", a.getCurrentLoad()).comma();
                json.prop("finalLoad", a.getCurrentLoad()).comma();
                json.prop("utilization", util).comma();
                json.prop("status", status(util));
                json.objEnd();
                if (i < airports.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // flights
            json.name("flights").arrayStart();
            List<Flight> used = "TIEMPO_REAL".equals(scenario)
                    ? flights.stream()
                    .filter(f -> f.getAssignedLoad() > 0)
                    .filter(f -> f.absoluteArrivalMinute() >= tick)
                    .sorted(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                    .limit(300)
                    .toList()
                    : flights.stream()
                    .filter(f -> f.getAssignedLoad() > 0)
                    .sorted(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                    .toList();
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
                        cancellations.contains(f.getFlightId()) ? "CANCELLED" :
                                pendingCancellations.contains(f.getFlightId()) ? "PENDING_CANCEL" :
                                        f.absoluteDepartureMinute() <= tick ? "IN_PROGRESS" : "QUEUED");
                json.objEnd();
                if (i < used.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // shipments
            json.name("shipments").arrayStart();
            for (int i = 0; i < shipmentSource.size(); i++) {
                writeShipment(json, shipmentSource.get(i));
                if (i < shipmentSource.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            // airportEvents
            List<RealtimeEvent> sortedEvents = new ArrayList<>(events);
            sortedEvents.sort(Comparator
                    .comparingInt(RealtimeEvent::minute)
                    .thenComparing(RealtimeEvent::airport)
                    .thenComparingInt(e -> eventPriority(e.type))
                    .thenComparing(RealtimeEvent::type));
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
            return json.toString();
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
            json.arrayEnd();
            json.objEnd();
        }

        private String scenarioLabel() {
            return "SIMULACION_LOTES".equals(scenario) ? "Simulación 5 días" : "Tiempo real";
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
