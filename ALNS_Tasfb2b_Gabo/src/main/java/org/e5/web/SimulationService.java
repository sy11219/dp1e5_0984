package org.e5.web;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;
import org.e5.parser.ShipmentParser;
import org.e5.planner.ALNS;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class SimulationService {
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ZoneId SERVER_ZONE = ZoneId.systemDefault();

    public String runAlns(String startDate, int days) throws Exception {
        validate(startDate, days);

        LocalDateTime realStartedAt = LocalDateTime.now(SERVER_ZONE);
        long startedNanos = System.nanoTime();

        AirportParser airportParser = new AirportParser();
        List<Airport> airports = airportParser.parse();
        Map<String, Airport> airportMap = new LinkedHashMap<>();
        for (Airport airport : airports) {
            airportMap.put(airport.getCode(), airport);
        }

        int flightDays = days + 2;
        FlightPlanParser flightParser = new FlightPlanParser();
        List<Flight> flights = flightParser.parse(flightDays, airportMap);

        ShipmentParser shipmentParser = new ShipmentParser(airportMap);
        List<Shipment> shipments = shipmentParser.parseAll(startDate, days);

        ALNS alns = new ALNS(
                Math.min(180, Math.max(80, shipments.size() / 10)),
                15,
                -1,
                420.0,
                0.997,
                4,
                9.0,
                3.0,
                0.0,
                0.8
        );
        Map<String, Route> routes = alns.ejecutar(shipments, flights, airportMap);

        long runtimeMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        LocalDateTime realFinishedAt = LocalDateTime.now(SERVER_ZONE);

        return toJson(startDate, days, airports, flights, shipments, routes, alns,
                realStartedAt, realFinishedAt, runtimeMs);
    }

    private void validate(String startDate, int days) {
        if (startDate == null || !startDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("La fecha inicial debe tener formato aaaammdd.");
        }
        LocalDate.parse(startDate, RAW_DATE);
        if (days != 3 && days != 5 && days != 7) {
            throw new IllegalArgumentException("Solo se permite simular 3, 5 o 7 dias.");
        }
    }

    private String toJson(String startDate, int days,
                          List<Airport> airports,
                          List<Flight> flights,
                          List<Shipment> shipments,
                          Map<String, Route> routes,
                          ALNS alns,
                          LocalDateTime realStartedAt,
                          LocalDateTime realFinishedAt,
                          long runtimeMs) {
        Json json = new Json();
        LocalDate simStartDate = LocalDate.parse(startDate, RAW_DATE);
        String simStart = simStartDate.atStartOfDay().format(ISO_DATE_TIME);
        String simEnd = simStartDate.plusDays(days).atStartOfDay().format(ISO_DATE_TIME);

        Map<String, AirportLoad> airportLoads = buildAirportLoads(airports, shipments);
        List<Flight> usedFlights = flights.stream()
                .filter(f -> f.getAssignedLoad() > 0)
                .sorted(Comparator.comparingInt(Flight::absoluteDepartureMinute))
                .toList();

        int planned = (int) shipments.stream().filter(Shipment::isPlanned).count();
        int onTime = (int) shipments.stream().filter(Shipment::isOnTime).count();
        int totalBags = shipments.stream().mapToInt(Shipment::getSuitcaseCount).sum();
        int plannedBags = shipments.stream()
                .filter(Shipment::isPlanned)
                .mapToInt(Shipment::getSuitcaseCount)
                .sum();

        json.objStart();
        json.prop("simulationId", UUID.randomUUID().toString()).comma();
        json.prop("scenario", "ALNS").comma();
        json.prop("status", "COMPLETED").comma();
        json.prop("days", days).comma();
        json.prop("simulationStartDateTime", simStart).comma();
        json.prop("simulationEndDateTime", simEnd).comma();
        json.prop("realStartedAt", realStartedAt.format(ISO_DATE_TIME)).comma();
        json.prop("realFinishedAt", realFinishedAt.format(ISO_DATE_TIME)).comma();
        json.prop("runtimeMs", runtimeMs).comma();

        json.name("metrics").objStart();
        json.prop("shipments", shipments.size()).comma();
        json.prop("plannedShipments", planned).comma();
        json.prop("onTimeShipments", onTime).comma();
        json.prop("totalBags", totalBags).comma();
        json.prop("plannedBags", plannedBags).comma();
        json.prop("usedFlights", usedFlights.size()).comma();
        json.prop("fitnessInitial", alns.getFitnessMejorInicial()).comma();
        json.prop("fitnessFinal", alns.getFitnessMejorFinal()).comma();
        json.prop("iterations", alns.getIteracionesEjecutadas()).comma();
        json.prop("globalImprovements", alns.getMejorasGlobal()).comma();
        json.prop("acceptedBySa", alns.getAceptadasSA());
        json.objEnd().comma();

        json.name("airports").arrayStart();
        for (int i = 0; i < airports.size(); i++) {
            Airport a = airports.get(i);
            AirportLoad load = airportLoads.get(a.getCode());
            json.objStart();
            json.prop("code", a.getCode()).comma();
            json.prop("city", a.getCity()).comma();
            json.prop("country", a.getCountry()).comma();
            json.prop("continent", a.getContinent()).comma();
            json.prop("latitude", a.getLatitude()).comma();
            json.prop("longitude", a.getLongitude()).comma();
            json.prop("gmtOffset", a.getGmtOffset()).comma();
            json.prop("maxCapacity", a.getMaxCapacity()).comma();
            json.prop("peakLoad", load.peakLoad).comma();
            json.prop("finalLoad", load.finalLoad).comma();
            json.prop("utilization", ratio(load.peakLoad, a.getMaxCapacity())).comma();
            json.prop("status", status(ratio(load.peakLoad, a.getMaxCapacity())));
            json.objEnd();
            if (i < airports.size() - 1) json.comma();
        }
        json.arrayEnd().comma();

        json.name("flights").arrayStart();
        for (int i = 0; i < usedFlights.size(); i++) {
            Flight f = usedFlights.get(i);
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
            if (i < usedFlights.size() - 1) json.comma();
        }
        json.arrayEnd().comma();

        json.name("shipments").arrayStart();
        for (int i = 0; i < shipments.size(); i++) {
            Shipment s = shipments.get(i);
            Route route = routes.get(s.getShipmentId());
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
            if (route != null) {
                List<Flight> routeFlights = route.getFlights();
                for (int f = 0; f < routeFlights.size(); f++) {
                    json.value(routeFlights.get(f).getFlightId());
                    if (f < routeFlights.size() - 1) json.comma();
                }
            }
            json.arrayEnd();
            json.objEnd();
            if (i < shipments.size() - 1) json.comma();
        }
        json.arrayEnd().comma();

        List<AirportEvent> events = buildAirportEvents(shipments);
        json.name("airportEvents").arrayStart();
        for (int i = 0; i < events.size(); i++) {
            AirportEvent e = events.get(i);
            json.objStart();
            json.prop("minute", e.minute).comma();
            json.prop("airport", e.airportCode).comma();
            json.prop("delta", e.delta).comma();
            json.prop("type", e.type);
            json.objEnd();
            if (i < events.size() - 1) json.comma();
        }
        json.arrayEnd();

        json.objEnd();
        try (PrintWriter writer = new PrintWriter(new FileWriter("salida.txt"))) {
            writer.println(json.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return json.toString();
    }

    private Map<String, AirportLoad> buildAirportLoads(List<Airport> airports, List<Shipment> shipments) {
        Map<String, Integer> current = new HashMap<>();
        Map<String, AirportLoad> loads = new HashMap<>();
        for (Airport airport : airports) {
            current.put(airport.getCode(), 0);
            loads.put(airport.getCode(), new AirportLoad());
        }
        List<AirportEvent> events = buildAirportEvents(shipments);
        for (AirportEvent event : events) {
            int next = Math.max(0, current.getOrDefault(event.airportCode, 0) + event.delta);
            current.put(event.airportCode, next);
            AirportLoad load = loads.computeIfAbsent(event.airportCode, ignored -> new AirportLoad());
            load.peakLoad = Math.max(load.peakLoad, next);
            load.finalLoad = next;
        }
        return loads;
    }

    private List<AirportEvent> buildAirportEvents(List<Shipment> shipments) {
        List<AirportEvent> events = new ArrayList<>();
        for (Shipment shipment : shipments) {
            if (!shipment.isPlanned() || shipment.getAssignedRoute() == null) continue;

            int bags = shipment.getSuitcaseCount();
            Route route = shipment.getAssignedRoute();
            List<Flight> routeFlights = route.getFlights();
            if (routeFlights.isEmpty()) continue;

            events.add(new AirportEvent(shipment.getRequestMinute(), shipment.getOriginCode(), bags, "shipment_created"));
            events.add(new AirportEvent(routeFlights.get(0).absoluteDepartureMinute(), shipment.getOriginCode(), -bags, "flight_departure"));

            for (int i = 0; i < routeFlights.size(); i++) {
                Flight flight = routeFlights.get(i);
                boolean finalLeg = i == routeFlights.size() - 1;
                events.add(new AirportEvent(flight.absoluteArrivalMinute(), flight.getDestCode(), bags,
                        finalLeg ? "final_arrival" : "connection_arrival"));
                if (!finalLeg) {
                    Flight next = routeFlights.get(i + 1);
                    events.add(new AirportEvent(next.absoluteDepartureMinute(), flight.getDestCode(), -bags, "connection_departure"));
                }
            }
        }
        events.sort(Comparator.comparingInt((AirportEvent e) -> e.minute).thenComparing(e -> e.airportCode));
        return events;
    }

    private double ratio(int value, int total) {
        return total <= 0 ? 0.0 : Math.min(1.5, (double) value / total);
    }

    private String status(double utilization) {
        if (utilization < 0.70) return "green";
        if (utilization < 0.90) return "yellow";
        return "red";
    }

    private static class AirportLoad {
        int peakLoad;
        int finalLoad;
    }

    private record AirportEvent(int minute, String airportCode, int delta, String type) {}

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
