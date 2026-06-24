package org.e5.util;

import org.e5.config.OperationParameters;
import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reserva capacidad de aeropuertos por intervalos de tiempo.
 *
 * El planificador usa buckets para evitar tratar la capacidad de almacenes como
 * una sola carga acumulada. Una maleta ocupa:
 * - el origen desde que se registra hasta que sale el primer vuelo;
 * - cada escala desde que aterriza hasta que sale el siguiente vuelo;
 * - el destino final hasta que pasan los minutos de retiro final.
 */
public class AirportCapacityTimeline {

    private static final int DEFAULT_BUCKET_MINUTES = 15;
    private static final int BUCKET_MINUTES = readPositiveInt(
            "TASF_AIRPORT_CAPACITY_BUCKET_MINUTES",
            DEFAULT_BUCKET_MINUTES
    );

    private final Map<String, Airport> airportMap;
    private final Map<String, Map<Integer, Integer>> baseLoads = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> loads = new HashMap<>();

    public AirportCapacityTimeline(Map<String, Airport> airportMap) {
        this.airportMap = airportMap;
    }

    public void seedExistingAssignments(Iterable<Shipment> shipments) {
        if (shipments == null) return;
        for (Shipment shipment : shipments) {
            Route route = shipment.getAssignedRoute();
            if (route == null || !route.isValid()) continue;
            addRoute(baseLoads, route, shipment.getSuitcaseCount(), 1);
            addRoute(loads, route, shipment.getSuitcaseCount(), 1);
        }
    }

    public void seedAggregateLoads() {
        for (Airport airport : airportMap.values()) {
            int currentLoad = airport.getCurrentLoad();
            if (currentLoad <= 0) continue;
            // Fallback conservador cuando no se tienen rutas previas para
            // reconstruir los intervalos exactos.
            baseLoads
                    .computeIfAbsent(airport.getCode(), ignored -> new HashMap<>())
                    .put(Integer.MIN_VALUE, currentLoad);
            loads
                    .computeIfAbsent(airport.getCode(), ignored -> new HashMap<>())
                    .put(Integer.MIN_VALUE, currentLoad);
        }
    }

    public void resetToBase() {
        loads.clear();
        for (Map.Entry<String, Map<Integer, Integer>> entry : baseLoads.entrySet()) {
            loads.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    public boolean canReserve(Route route, int bags) {
        if (route == null || !route.isValid() || bags <= 0) return false;
        return forEachRouteInterval(route, (airportCode, startMinute, endMinute) ->
                canReserveInterval(airportCode, startMinute, endMinute, bags));
    }

    public void reserve(Route route, int bags) {
        addRoute(loads, route, bags, 1);
    }

    public void release(Route route, int bags) {
        addRoute(loads, route, bags, -1);
    }

    private boolean canReserveInterval(String airportCode, int startMinute, int endMinute, int bags) {
        Airport airport = airportMap.get(airportCode);
        if (airport == null || endMinute <= startMinute) return true;
        Map<Integer, Integer> airportLoads = loads.getOrDefault(airportCode, Map.of());
        int baseLoad = airportLoads.getOrDefault(Integer.MIN_VALUE, 0);

        for (int bucket = firstBucket(startMinute); bucket <= lastBucket(endMinute); bucket++) {
            int current = baseLoad + airportLoads.getOrDefault(bucket, 0);
            if (current + bags > airport.getMaxCapacity()) return false;
        }
        return true;
    }

    private void addRoute(Map<String, Map<Integer, Integer>> target, Route route, int bags, int sign) {
        if (route == null || !route.isValid() || bags <= 0) return;
        forEachRouteInterval(route, (airportCode, startMinute, endMinute) -> {
            if (endMinute <= startMinute) return true;
            Map<Integer, Integer> airportLoads =
                    target.computeIfAbsent(airportCode, ignored -> new HashMap<>());
            for (int bucket = firstBucket(startMinute); bucket <= lastBucket(endMinute); bucket++) {
                airportLoads.merge(bucket, sign * bags, Integer::sum);
                if (airportLoads.get(bucket) == 0) airportLoads.remove(bucket);
            }
            return true;
        });
    }

    private boolean forEachRouteInterval(Route route, IntervalVisitor visitor) {
        List<Flight> flights = route.getFlights();
        if (flights.isEmpty()) return true;

        Flight first = flights.get(0);
        if (!visitor.visit(route.getOriginCode(), route.getStartMinute(), first.absoluteDepartureMinute())) {
            return false;
        }

        for (int i = 0; i < flights.size() - 1; i++) {
            Flight current = flights.get(i);
            Flight next = flights.get(i + 1);
            if (!visitor.visit(current.getDestCode(),
                    current.absoluteArrivalMinute(),
                    next.absoluteDepartureMinute())) {
                return false;
            }
        }

        Flight last = flights.get(flights.size() - 1);
        return visitor.visit(last.getDestCode(),
                last.absoluteArrivalMinute(),
                last.absoluteArrivalMinute() + OperationParameters.FINAL_PICKUP_WAIT_MINUTES);
    }

    private int firstBucket(int minute) {
        return Math.floorDiv(minute, BUCKET_MINUTES);
    }

    private int lastBucket(int exclusiveEndMinute) {
        return Math.floorDiv(Math.max(exclusiveEndMinute - 1, 0), BUCKET_MINUTES);
    }

    private static int readPositiveInt(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    @FunctionalInterface
    private interface IntervalVisitor {
        boolean visit(String airportCode, int startMinute, int endMinute);
    }
}
