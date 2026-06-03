package org.e5.web;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;
import org.e5.parser.ShipmentParser;
import org.e5.planner.ALNS;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de simulación por lotes y tiempo real para TASF.B2B.
 *
 * ── Escenario SIMULACION_LOTES ────────────────────────────────────────────────
 *
 *   El período total (3/5/7 días) se divide en lotes de BATCH_MINUTES minutos
 *   simulados (360 min = 6 h por defecto). El frontend llama a /advance con
 *   steps=BATCH_MINUTES cada BATCH_INTERVAL_MS ms (120 000 ms = 2 min).
 *
 *   El backend en advance() hace exactamente:
 *     1. Identifica los envíos y vuelos de la ventana [tick, tick+steps).
 *     2. Ejecuta el ALNS para ese lote (una sola vez, parámetros robustos).
 *     3. Duerme el tiempo que falta para completar BATCH_INTERVAL_MS desde
 *        que empezó la petición, de modo que la respuesta llega al frontend
 *        justo cuando debe cargar el siguiente lote.
 *     4. Devuelve el snapshot con tick avanzado.
 *
 *   Así las líneas de tiempo de ejecución y carga de paquetes avanzan a la par:
 *     t=0 s   → /advance lote 1 → ALNS corre → responde en t≈2 min
 *     t=2 min → /advance lote 2 → ALNS corre → responde en t≈4 min
 *     ...
 *
 *   El frontend NO necesita su propio setTimeout: simplemente llama /advance
 *   inmediatamente al recibir cada respuesta, porque el backend ya esperó el
 *   tiempo correcto.
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

    // ── Parámetros de temporización del lote ─────────────────────────────────
    // ── Parámetros del loop de tiempo real ────────────────────────────────────
    private static final int INTERVALO_TICK    = 1;
    private static final int INTERVALO_REPLAN  = 10;
    private static final int UMBRAL_COLA_REPLAN = 20;

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    //  API pública
    // ════════════════════════════════════════════════════════════════════════

    /** Inicia una sesión de tiempo real clásica. */
    public String start(String startDate, int days) throws Exception {
        validate(startDate, days, false);
        return createSession(startDate, days, "TIEMPO_REAL");
    }

    /**
     * Inicia una sesión de simulación por lotes.
     * El ALNS se ejecuta una vez por lote de BATCH_MINUTES minutos simulados.
     * advance() controla el tiempo real para que cada lote dure exactamente
     * BATCH_REAL_DURATION_MS milisegundos desde la perspectiva del frontend.
     */
    public String startBatchSimulation(String startDate, int days) throws Exception {
        validate(startDate, days, true);
        return createSession(startDate, days, "SIMULACION_LOTES");
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
        RealtimeSession session = require(id);
        if ("SIMULACION_LOTES".equals(session.scenario)) {
            session.advanceBatch(Math.max(1, steps));
        } else {
            session.advance(Math.max(1, Math.min(steps, 720)));
        }
        return session.snapshotJson();
    }

    /**
     * Cancela un vuelo futuro y replanifica.
     * Rechaza con excepción si el vuelo ya despegó en el tick actual.
     */
    public String cancelFlight(String id, String flightId) {
        RealtimeSession session = require(id);
        session.cancel(flightId);
        return session.snapshotJson();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internos
    // ════════════════════════════════════════════════════════════════════════

    private String createSession(String startDate, int days, String scenario) throws Exception {
        AirportParser airportParser = new AirportParser();
        List<Airport> airports = airportParser.parse();
        Map<String, Airport> airportMap = new LinkedHashMap<>();
        for (Airport airport : airports) {
            airport.resetLoad();
            airportMap.put(airport.getCode(), airport);
        }

        FlightPlanParser flightParser = new FlightPlanParser();
        List<Flight> flights = flightParser.parse(days + 2, airportMap);
        for (Flight flight : flights) flight.resetLoad();

        ShipmentParser shipmentParser = new ShipmentParser(airportMap);
        List<Shipment> shipments = shipmentParser.parseAll(startDate, days);
        shipments.sort(Comparator.comparingInt(Shipment::getRequestMinute));

        RealtimeSession session = new RealtimeSession(
                startDate, days, scenario, airports, airportMap, flights, shipments);
        sessions.put(session.id, session);
        return session.snapshotJson();
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
            if (days != 3 && days != 5 && days != 7)
                throw new IllegalArgumentException("Solo se permite simular 3, 5 o 7 dias.");
        } else {
            if (days < 1 || days > 7)
                throw new IllegalArgumentException("Tiempo real permite simular entre 1 y 7 dias.");
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
        final Set<String> cancellations = new HashSet<>();

        final LocalDateTime realStartedAt = LocalDateTime.now();
        int tick = 0;
        final int maxTick;
        boolean completed = false;

        // ── Solo para SIMULACION_LOTES: solución acumulada global ─────────────
        // Guarda las rutas de todos los lotes anteriores para poder hacer
        // reoptimización global en cancelaciones.
        final Map<String, Route> solucionGlobal = new HashMap<>();
        final List<Shipment> todosLosShipmentsProcesados = new ArrayList<>();

        RealtimeSession(String startDate, int days, String scenario,
                        List<Airport> airports, Map<String, Airport> airportMap,
                        List<Flight> flights, List<Shipment> shipments) {
            this.startDate  = startDate;
            this.days       = days;
            this.scenario   = scenario;
            this.airports   = airports;
            this.airportMap = airportMap;
            this.flights    = flights;
            this.shipments  = shipments;
            for (Shipment s : shipments)
                shipmentsByMinute.computeIfAbsent(s.getRequestMinute(), k -> new ArrayList<>()).add(s);
            this.maxTick = Math.max(days * 1440,
                    flights.stream().mapToInt(Flight::absoluteArrivalMinute).max().orElse(days * 1440));
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
         *   6. Duerme el tiempo restante para completar BATCH_REAL_DURATION_MS.
         *      → El frontend recibe la respuesta exactamente cuando debe cargar
         *        el siguiente lote, sin necesitar su propio setTimeout.
         */
        synchronized void advanceBatch(int steps) {
            if (completed) return;

            long batchStartMs = System.currentTimeMillis();
            int batchStart = tick;
            int batchEnd   = Math.min(tick + steps, maxTick);

            // 1. Recoger envíos que caen en esta ventana
            List<Shipment> loteShipments = new ArrayList<>();
            for (int min = batchStart; min < batchEnd; min++) {
                List<Shipment> en = shipmentsByMinute.getOrDefault(min, Collections.emptyList());
                loteShipments.addAll(en);
            }

            // 2. Vuelos disponibles: no cancelados, que salen en o después del lote
            List<Flight> vuelosDisponibles = availableFlightsFrom(batchStart);

            System.out.printf("[Lote] Ventana %d–%d | %d envíos | %d vuelos disponibles%n",
                    batchStart, batchEnd, loteShipments.size(), vuelosDisponibles.size());

            // 3. Ejecutar ALNS para el lote (si hay envíos nuevos)
            if (!loteShipments.isEmpty()) {
                int n       = loteShipments.size();
                int iters   = Math.max(80, Math.min(400, n * 4));
                int seg     = Math.max(10, iters / 15);
                int nDestr  = Math.max(3, Math.min(n / 5 + 5, 60));

                ALNS alns = new ALNS(iters, seg, nDestr, 300.0, 0.995, 2,
                        9.0, 3.0, 0.0, 0.8);

                Map<String, Route> resultado = alns.ejecutar(loteShipments, vuelosDisponibles, airportMap);

                // Registrar eventos de aeropuerto para la animación
                for (Shipment s : loteShipments) {
                    Airport origin = airportMap.get(s.getOriginCode());
                    if (origin != null) origin.addLoad(s.getSuitcaseCount());
                    events.add(new RealtimeEvent(s.getRequestMinute(),
                            s.getOriginCode(), s.getSuitcaseCount(), "shipment_created"));

                    Route ruta = resultado.get(s.getShipmentId());
                    if (ruta != null && ruta.isValid()) {
                        List<Flight> rutaVuelos = ruta.getFlights();
                        if (!rutaVuelos.isEmpty()) {
                            events.add(new RealtimeEvent(
                                    rutaVuelos.get(0).absoluteDepartureMinute(),
                                    s.getOriginCode(), -s.getSuitcaseCount(), "flight_departure"));
                        }
                        for (int i = 0; i < rutaVuelos.size(); i++) {
                            Flight f = rutaVuelos.get(i);
                            boolean last = (i == rutaVuelos.size() - 1);
                            events.add(new RealtimeEvent(f.absoluteArrivalMinute(),
                                    f.getDestCode(), s.getSuitcaseCount(),
                                    last ? "final_arrival" : "connection_arrival"));
                        }
                    }
                }

                // Acumular en la solución global
                solucionGlobal.putAll(resultado);
                processed.addAll(loteShipments);
                todosLosShipmentsProcesados.addAll(loteShipments);

                System.out.printf("[Lote] Rutas: %d / %d%n", resultado.size(), n);
            }

            // 4. Avanzar tick
            tick = batchEnd;
            if (tick >= maxTick) {
                if (!queue.isEmpty()) planQueue();
                completed = true;
                System.out.println("[Lote] Simulación completada.");
            }

            events.add(new RealtimeEvent(tick, "SYSTEM", loteShipments.size(), "batch_complete"));

            long elapsed = System.currentTimeMillis() - batchStartMs;
            System.out.printf("[Lote] Completado en %d ms%n", elapsed);
        }

        // ── TIEMPO_REAL: avance tick a tick ───────────────────────────────────

        synchronized void advance(int steps) {
            for (int i = 0; i < steps && !completed; i++) step();
        }

        private void step() {
            List<Shipment> incoming = shipmentsByMinute.getOrDefault(tick, Collections.emptyList());
            if (!incoming.isEmpty()) {
                queue.addAll(incoming);
                for (Shipment s : incoming) {
                    Airport origin = airportMap.get(s.getOriginCode());
                    if (origin != null) origin.addLoad(s.getSuitcaseCount());
                    events.add(new RealtimeEvent(tick, s.getOriginCode(), s.getSuitcaseCount(), "shipment_created"));
                }
            }
            processFlightMovements();
            if (!queue.isEmpty() && (tick % INTERVALO_REPLAN == 0 || queue.size() >= UMBRAL_COLA_REPLAN))
                planQueue();
            tick += INTERVALO_TICK;
            if (tick > maxTick) {
                if (!queue.isEmpty()) planQueue();
                completed = true;
            }
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
                        Airport dest = airportMap.get(f.getDestCode());
                        if (dest != null) dest.addLoad(s.getSuitcaseCount());
                        String type = idx == route.getFlights().size() - 1 ? "final_arrival" : "connection_arrival";
                        events.add(new RealtimeEvent(tick, f.getDestCode(), s.getSuitcaseCount(), type));
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
            queue.clear();
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
        synchronized void cancel(String flightId) {
            Flight toCancel = findFlight(flightId);
            if (toCancel == null)
                throw new IllegalArgumentException("Vuelo no encontrado: " + flightId);
            if (toCancel.absoluteDepartureMinute() <= tick)
                throw new IllegalArgumentException(
                        "Solo se pueden cancelar vuelos futuros. El vuelo " + flightId +
                        " despega en el minuto " + toCancel.absoluteDepartureMinute() +
                        " y el minuto actual es " + tick + ".");

            cancellations.add(flightId);
            events.add(new RealtimeEvent(tick, "SYSTEM", 1, "flight_cancelled"));

            if ("SIMULACION_LOTES".equals(scenario)) {
                cancelBatch(flightId);
            } else {
                replanAffected(flightId);
            }
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
            for (Flight f : flights) {
                if (!cancellations.contains(f.getFlightId())
                        && f.absoluteDepartureMinute() >= fromMinute)
                    available.add(f);
            }
            return available;
        }

        private Flight findFlight(String flightId) {
            for (Flight f : flights)
                if (f.getFlightId().equals(flightId)) return f;
            return null;
        }

        // ── Snapshot JSON ─────────────────────────────────────────────────────

        synchronized String snapshotJson() {
            Json json = new Json();

            // Para lotes: usar solucionGlobal; para tiempo real: usados los de processed
            List<Shipment> shipmentSource = "SIMULACION_LOTES".equals(scenario)
                    ? todosLosShipmentsProcesados : processed;

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
            json.prop("scenario", scenario).comma();
            json.prop("status", completed ? "COMPLETED" : "RUNNING").comma();
            json.prop("days", days).comma();
            json.prop("tick", tick).comma();
            json.prop("maxTick", maxTick).comma();
            json.prop("message", message).comma();
            json.prop("simulationStartDateTime",
                    LocalDate.parse(startDate, RAW_DATE).atStartOfDay().toString()).comma();
            json.prop("simulationEndDateTime",
                    LocalDate.parse(startDate, RAW_DATE).plusDays(days).atStartOfDay().toString()).comma();
            json.prop("realStartedAt", realStartedAt.toString()).comma();
            json.prop("realFinishedAt", LocalDateTime.now().toString()).comma();
            json.prop("runtimeMs",
                    java.time.Duration.between(realStartedAt, LocalDateTime.now()).toMillis()).comma();

            // cancelledFlightIds
            json.name("cancelledFlightIds").arrayStart();
            int ci = 0;
            for (String cid : cancellations) {
                json.value(cid);
                if (++ci < cancellations.size()) json.comma();
            }
            json.arrayEnd().comma();

            // metrics
            json.name("metrics").objStart();
            json.prop("shipments", shipments.size()).comma();
            json.prop("processedShipments", shipmentSource.size()).comma();
            json.prop("queuedShipments", queue.size()).comma();
            json.prop("plannedShipments", planned).comma();
            json.prop("onTimeShipments", onTime).comma();
            json.prop("totalBags", totalBags).comma();
            json.prop("plannedBags", plannedBags).comma();
            json.prop("usedFlights", usedFlights).comma();
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
            List<Flight> used = flights.stream()
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
                json.prop("status", status(util));
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
