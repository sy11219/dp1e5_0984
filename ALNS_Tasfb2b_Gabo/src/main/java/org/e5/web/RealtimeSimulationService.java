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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RealtimeSimulationService {
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int INTERVALO_TICK = 1;
    private static final int INTERVALO_REPLAN = 10;
    private static final int UMBRAL_COLA_REPLAN = 20;

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();

    public String start(String startDate, int days) throws Exception {
        validate(startDate, days);

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

        RealtimeSession session = new RealtimeSession(startDate, days, airports, airportMap, flights, shipments);
        sessions.put(session.id, session);
        return session.snapshotJson();
    }

    public String state(String id) {
        return require(id).snapshotJson();
    }

    public String advance(String id, int steps) {
        RealtimeSession session = require(id);
        session.advance(Math.max(1, Math.min(steps, 240)));
        return session.snapshotJson();
    }

    public String cancelFlight(String id, String flightId) {
        RealtimeSession session = require(id);
        session.cancel(flightId);
        return session.snapshotJson();
    }

    private RealtimeSession require(String id) {
        RealtimeSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Sesion de tiempo real no encontrada.");
        }
        return session;
    }

    private void validate(String startDate, int days) {
        if (startDate == null || !startDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("La fecha inicial debe tener formato aaaammdd.");
        }
        LocalDate.parse(startDate, RAW_DATE);
        if (days < 1 || days > 7) {
            throw new IllegalArgumentException("Tiempo real permite simular entre 1 y 7 dias.");
        }
    }

    private static class RealtimeSession {
        private final String id = UUID.randomUUID().toString();
        private final String startDate;
        private final int days;
        private final List<Airport> airports;
        private final Map<String, Airport> airportMap;
        private final List<Flight> flights;
        private final List<Shipment> shipments;
        private final Map<Integer, List<Shipment>> shipmentsByMinute = new HashMap<>();
        private final List<Shipment> queue = new ArrayList<>();
        private final List<Shipment> processed = new ArrayList<>();
        private final List<RealtimeEvent> events = new ArrayList<>();
        private final Set<String> processedFlightEvents = new HashSet<>();
        private final Set<String> cancellations = new HashSet<>();
        private final LocalDateTime realStartedAt = LocalDateTime.now();
        private int tick = 0;
        private final int maxTick;
        private boolean completed = false;

        RealtimeSession(String startDate, int days, List<Airport> airports, Map<String, Airport> airportMap,
                        List<Flight> flights, List<Shipment> shipments) {
            this.startDate = startDate;
            this.days = days;
            this.airports = airports;
            this.airportMap = airportMap;
            this.flights = flights;
            this.shipments = shipments;
            for (Shipment shipment : shipments) {
                shipmentsByMinute.computeIfAbsent(shipment.getRequestMinute(), ignored -> new ArrayList<>()).add(shipment);
            }
            this.maxTick = Math.max(days * 1440, flights.stream().mapToInt(Flight::absoluteArrivalMinute).max().orElse(days * 1440));
        }

        synchronized void cancel(String flightId) {
            cancellations.add(flightId);
            replanAffected(flightId);
        }

        synchronized void advance(int steps) {
            for (int i = 0; i < steps && !completed; i++) {
                step();
            }
        }

        private void step() {
            List<Shipment> incoming = shipmentsByMinute.getOrDefault(tick, Collections.emptyList());
            if (!incoming.isEmpty()) {
                queue.addAll(incoming);
                for (Shipment shipment : incoming) {
                    Airport origin = airportMap.get(shipment.getOriginCode());
                    if (origin != null) origin.addLoad(shipment.getSuitcaseCount());
                    events.add(new RealtimeEvent(tick, shipment.getOriginCode(), shipment.getSuitcaseCount(), "shipment_created"));
                }
            }

            processFlightMovements();

            if (!queue.isEmpty() && (tick % INTERVALO_REPLAN == 0 || queue.size() >= UMBRAL_COLA_REPLAN)) {
                planQueue();
            }

            tick += INTERVALO_TICK;
            if (tick > maxTick) {
                if (!queue.isEmpty()) planQueue();
                completed = true;
            }
        }

        private void processFlightMovements() {
            for (Shipment shipment : processed) {
                Route route = shipment.getAssignedRoute();
                if (route == null) continue;
                for (int index = 0; index < route.getFlights().size(); index++) {
                    Flight flight = route.getFlights().get(index);
                    String departureKey = shipment.getShipmentId() + ":" + flight.getFlightId() + ":dep";
                    if (flight.absoluteDepartureMinute() == tick && processedFlightEvents.add(departureKey)) {
                        Airport origin = airportMap.get(flight.getOriginCode());
                        if (origin != null) origin.removeLoad(shipment.getSuitcaseCount());
                        events.add(new RealtimeEvent(tick, flight.getOriginCode(), -shipment.getSuitcaseCount(), "flight_departure"));
                    }

                    String arrivalKey = shipment.getShipmentId() + ":" + flight.getFlightId() + ":arr";
                    if (flight.absoluteArrivalMinute() == tick && processedFlightEvents.add(arrivalKey)) {
                        Airport dest = airportMap.get(flight.getDestCode());
                        if (dest != null) dest.addLoad(shipment.getSuitcaseCount());
                        String type = index == route.getFlights().size() - 1 ? "final_arrival" : "connection_arrival";
                        events.add(new RealtimeEvent(tick, flight.getDestCode(), shipment.getSuitcaseCount(), type));
                    }
                }
            }
        }

        private void planQueue() {
            int iters = Math.max(20, Math.min(80, queue.size() * 3));
            int segment = Math.max(5, iters / 5);
            ALNS alns = new ALNS(iters, segment, -1, 80.0, 0.96, 2, 9.0, 3.0, 0.0, 0.8);
            Map<String, Route> result = alns.ejecutar(queue, availableFlights(), airportMap);
            events.add(new RealtimeEvent(tick, "SYSTEM", result.size(), "replan"));
            processed.addAll(queue);
            queue.clear();
        }

        private void replanAffected(String flightId) {
            List<Shipment> affected = new ArrayList<>();
            for (Shipment shipment : processed) {
                Route route = shipment.getAssignedRoute();
                if (route == null) continue;
                for (Flight flight : route.getFlights()) {
                    if (flight.getFlightId().equals(flightId)) {
                        affected.add(shipment);
                        shipment.resetPlanningState();
                        break;
                    }
                }
            }
            if (affected.isEmpty()) return;
            int iters = Math.max(20, Math.min(80, affected.size() * 3));
            int segment = Math.max(5, iters / 5);
            ALNS alns = new ALNS(iters, segment, affected.size(), 80.0, 0.96, 2, 9.0, 3.0, 0.0, 0.8);
            alns.replanificar(affected, flightId, flights, airportMap);
            events.add(new RealtimeEvent(tick, "SYSTEM", affected.size(), "flight_cancelled"));
        }

        private List<Flight> availableFlights() {
            List<Flight> available = new ArrayList<>();
            for (Flight flight : flights) {
                if (!cancellations.contains(flight.getFlightId()) && flight.absoluteDepartureMinute() >= tick) {
                    available.add(flight);
                }
            }
            return available;
        }

        private String snapshotJson() {
            Json json = new Json();
            int planned = (int) processed.stream().filter(Shipment::isPlanned).count();
            int onTime = (int) processed.stream().filter(Shipment::isOnTime).count();
            int totalBags = shipments.stream().mapToInt(Shipment::getSuitcaseCount).sum();
            int plannedBags = processed.stream().filter(Shipment::isPlanned).mapToInt(Shipment::getSuitcaseCount).sum();
            int usedFlights = (int) flights.stream().filter(f -> f.getAssignedLoad() > 0).count();

            json.objStart();
            json.prop("simulationId", id).comma();
            json.prop("scenario", "TIEMPO_REAL").comma();
            json.prop("status", completed ? "COMPLETED" : "RUNNING").comma();
            json.prop("days", days).comma();
            json.prop("tick", tick).comma();
            json.prop("maxTick", maxTick).comma();
            json.prop("simulationStartDateTime", LocalDate.parse(startDate, RAW_DATE).atStartOfDay().toString()).comma();
            json.prop("simulationEndDateTime", LocalDate.parse(startDate, RAW_DATE).plusDays(days).atStartOfDay().toString()).comma();
            json.prop("realStartedAt", realStartedAt.toString()).comma();
            json.prop("realFinishedAt", LocalDateTime.now().toString()).comma();
            json.prop("runtimeMs", java.time.Duration.between(realStartedAt, LocalDateTime.now()).toMillis()).comma();
            json.name("metrics").objStart();
            json.prop("shipments", shipments.size()).comma();
            json.prop("processedShipments", processed.size()).comma();
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

            json.name("airports").arrayStart();
            for (int i = 0; i < airports.size(); i++) {
                Airport a = airports.get(i);
                double utilization = ratio(a.getCurrentLoad(), a.getMaxCapacity());
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
                json.prop("utilization", utilization).comma();
                json.prop("status", status(utilization));
                json.objEnd();
                if (i < airports.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            json.name("flights").arrayStart();
            List<Flight> used = flights.stream().filter(f -> f.getAssignedLoad() > 0).sorted(Comparator.comparingInt(Flight::absoluteDepartureMinute)).toList();
            for (int i = 0; i < used.size(); i++) {
                Flight f = used.get(i);
                double utilization = ratio(f.getAssignedLoad(), f.getMaxCapacity());
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
                json.prop("utilization", utilization).comma();
                json.prop("status", status(utilization));
                json.objEnd();
                if (i < used.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            json.name("shipments").arrayStart();
            for (int i = 0; i < processed.size(); i++) {
                writeShipment(json, processed.get(i));
                if (i < processed.size() - 1) json.comma();
            }
            json.arrayEnd().comma();

            json.name("airportEvents").arrayStart();
            for (int i = 0; i < events.size(); i++) {
                RealtimeEvent e = events.get(i);
                json.objStart();
                json.prop("minute", e.minute).comma();
                json.prop("airport", e.airport).comma();
                json.prop("delta", e.delta).comma();
                json.prop("type", e.type);
                json.objEnd();
                if (i < events.size() - 1) json.comma();
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
                List<Flight> routeFlights = route.getFlights();
                for (int i = 0; i < routeFlights.size(); i++) {
                    json.value(routeFlights.get(i).getFlightId());
                    if (i < routeFlights.size() - 1) json.comma();
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

    private static class Json {
        private final StringBuilder sb = new StringBuilder(128 * 1024);

        Json objStart() { sb.append('{'); return this; }
        Json objEnd() { sb.append('}'); return this; }
        Json arrayStart() { sb.append('['); return this; }
        Json arrayEnd() { sb.append(']'); return this; }
        Json comma() { sb.append(','); return this; }
        Json name(String name) { value(name); sb.append(':'); return this; }
        Json prop(String name, String value) { name(name); value(value); return this; }
        Json prop(String name, int value) { name(name); sb.append(value); return this; }
        Json prop(String name, long value) { name(name); sb.append(value); return this; }
        Json prop(String name, double value) { name(name); sb.append(String.format(Locale.US, "%.6f", value)); return this; }
        Json prop(String name, boolean value) { name(name); sb.append(value); return this; }
        Json value(String value) {
            sb.append('"');
            if (value != null) {
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default -> {
                            if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                            else sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return this;
        }
        @Override public String toString() { return sb.toString(); }
    }
}
