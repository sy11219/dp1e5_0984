package org.e5.util;

import org.e5.config.OperationParameters;
import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Buscador de rutas para ALNS.
 *
 * La parte costosa del planificador es buscar rutas repetidamente para los
 * mismos envíos. Este buscador mantiene índices por origen y caché de rutas
 * candidatas para que las reparaciones del ALNS prueben rutas ya calculadas
 * antes de ejecutar otra busqueda completa.
 */
public class ALNSRouteFinder {

    private static final int DEFAULT_CANDIDATE_ROUTES = readPositiveInt(
            "TASF_ROUTE_CANDIDATE_ROUTES", 5);
    private static final int MAX_FLIGHTS_PER_LEG = readPositiveInt(
            "TASF_ROUTE_MAX_FLIGHTS_PER_LEG", 180);
    private static final int MAX_FRACTIONAL_FIRST_FLIGHTS = readPositiveInt(
            "TASF_ROUTE_MAX_FRACTIONAL_FIRST_FLIGHTS", 80);
    private static final int FRACTIONAL_CANDIDATE_ROUTES = readPositiveInt(
            "TASF_ROUTE_FRACTIONAL_CANDIDATE_ROUTES", 8);

    private final Map<String, Airport> airportMap;
    private final int maxEscalas;
    private final AirportCapacityTimeline airportCapacityTimeline;
    private final Map<List<Flight>, Map<String, List<Flight>>> flightIndexCache = new IdentityHashMap<>();
    private final Map<RouteCacheKey, List<Route>> candidateRouteCache = new HashMap<>();

    public ALNSRouteFinder(Map<String, Airport> airportMap) {
        this(airportMap, 4);
    }

    public ALNSRouteFinder(Map<String, Airport> airportMap, int maxEscalas) {
        this(airportMap, maxEscalas, null);
    }

    public ALNSRouteFinder(Map<String, Airport> airportMap,
                           int maxEscalas,
                           AirportCapacityTimeline airportCapacityTimeline) {
        this.airportMap = airportMap;
        this.maxEscalas = maxEscalas;
        this.airportCapacityTimeline = airportCapacityTimeline;
    }

    public Route findBestRoute(Shipment shipment, List<Flight> flights) {
        List<Route> routes = searchRoutes(shipment, flights, 1, true);
        return routes.isEmpty() ? null : routes.get(0);
    }

    /**
     * Prueba primero rutas candidatas precalculadas. Si ninguna sigue siendo
     * factible con la capacidad actual, cae a la busqueda completa.
     */
    public Route findBestRouteCached(Shipment shipment, List<Flight> flights) {
        for (Route route : findCandidateRoutesCached(shipment, flights, DEFAULT_CANDIDATE_ROUTES)) {
            if (esFeasible(route, shipment.getSuitcaseCount())) {
                return route;
            }
        }
        return findBestRoute(shipment, flights);
    }

    public List<PartialRoute> findFractionalRoutes(Shipment shipment, List<Flight> flights) {
        List<PartialRoute> result = new ArrayList<>();
        int pending = shipment.getSuitcaseCount();
        if (pending <= 0) return result;

        int partIndex = 1;
        for (Route candidate : findCandidateRoutesCached(shipment, flights, FRACTIONAL_CANDIDATE_ROUTES)) {
            if (pending <= 0) break;

            int bags = maxReservableBags(candidate, pending);
            if (bags <= 0) continue;

            String partId = shipment.getShipmentId() + "_p" + partIndex;
            Route route = new Route(
                    partId,
                    shipment.getOriginCode(),
                    shipment.getDestCode(),
                    candidate.getFlights(),
                    bags,
                    shipment.getRequestMinute()
            );

            if (route != null && route.isValid() && esFeasible(route, bags)) {
                reserve(route, bags);
                result.add(new PartialRoute(route, bags));
                pending -= bags;
                partIndex++;
            }
        }

        if (pending > 0) {
            for (PartialRoute partial : result) {
                release(partial.ruta, partial.maletas);
            }
            return Collections.emptyList();
        }

        return result;
    }

    private int maxReservableBags(Route route, int pending) {
        if (route == null || !route.isValid() || pending <= 0) return 0;
        int maxByFlights = pending;
        for (Flight flight : route.getFlights()) {
            maxByFlights = Math.min(maxByFlights, flight.availableSpace());
            if (maxByFlights <= 0) return 0;
        }
        if (airportCapacityTimeline == null) return maxByFlights;

        int low = 0;
        int high = maxByFlights;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (airportCapacityTimeline.canReserve(route, mid)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    public List<Route> findCandidateRoutes(Shipment shipment, List<Flight> flights, int maxCandidates) {
        return searchRoutes(shipment, flights, maxCandidates, false);
    }

    public List<Route> findCandidateRoutesCached(Shipment shipment, List<Flight> flights, int maxCandidates) {
        RouteCacheKey key = RouteCacheKey.of(shipment, flights, maxCandidates, maxEscalas);
        List<Route> cached = candidateRouteCache.computeIfAbsent(
                key,
                ignored -> findCandidateRoutes(shipment, flights, maxCandidates)
        );
        List<Route> adapted = new ArrayList<>(cached.size());
        for (Route route : cached) {
            adapted.add(new Route(
                    shipment.getShipmentId(),
                    shipment.getOriginCode(),
                    shipment.getDestCode(),
                    route.getFlights(),
                    shipment.getSuitcaseCount(),
                    shipment.getRequestMinute()
            ));
        }
        return adapted;
    }

    public boolean esFeasible(Route route, int bags) {
        if (route == null || !route.isValid()) return false;
        List<Flight> flights = route.getFlights();
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            if (!flight.hasSpaceFor(bags)) return false;
        }
        return airportCapacityTimeline == null || airportCapacityTimeline.canReserve(route, bags);
    }

    public int getDeadlineMinutes(Shipment shipment) {
        Airport origin = airportMap.get(shipment.getOriginCode());
        Airport destination = airportMap.get(shipment.getDestCode());
        String originContinent = origin != null ? origin.getContinent() : "";
        String destinationContinent = destination != null ? destination.getContinent() : "";
        return Shipment.getDeadlineMinutes(originContinent, destinationContinent);
    }

    private List<Route> searchRoutes(Shipment shipment,
                                     List<Flight> flights,
                                     int maxRoutes,
                                     boolean checkCapacity) {
        Map<String, List<Flight>> flightsByOrigin = indexFlightsByOrigin(flights);
        int maxArrival = shipment.getRequestMinute() + getDeadlineMinutes(shipment);
        List<Route> routes = new ArrayList<>();

        PriorityQueue<SearchNode> queue = new PriorityQueue<>(
                Comparator.comparingInt(node -> node.costMinutes));
        queue.add(new SearchNode(
                shipment.getOriginCode(),
                shipment.getRequestMinute(),
                new ArrayList<>(),
                0
        ));

        Map<String, Integer> bestCostByState = new HashMap<>();

        while (!queue.isEmpty() && routes.size() < maxRoutes) {
            SearchNode current = queue.poll();

            if (current.airport.equals(shipment.getDestCode())) {
                if (current.flights.isEmpty()) continue;
                Route route = new Route(
                        shipment.getShipmentId(),
                        shipment.getOriginCode(),
                        shipment.getDestCode(),
                        current.flights,
                        shipment.getSuitcaseCount(),
                        shipment.getRequestMinute()
                );
                if (route.isValid() && (!checkCapacity || esFeasible(route, shipment.getSuitcaseCount()))) {
                    routes.add(route);
                }
                continue;
            }

            if (current.flights.size() >= maxEscalas) continue;

            String stateKey = current.airport + "@" + current.availableMinute;
            Integer bestCost = bestCostByState.get(stateKey);
            if (bestCost != null && bestCost <= current.costMinutes) continue;
            bestCostByState.put(stateKey, current.costMinutes);

            int minDeparture = current.availableMinute
                    + (current.flights.isEmpty() ? 0 : OperationParameters.CONNECTION_WAIT_MINUTES);

            for (Flight flight : candidateFlightsFrom(
                    flightsByOrigin.getOrDefault(current.airport, Collections.emptyList()),
                    minDeparture,
                    maxArrival)) {

                if (checkCapacity && !flight.hasSpaceFor(shipment.getSuitcaseCount())) continue;

                int wait = flight.absoluteDepartureMinute() - current.availableMinute;
                int duration = flight.absoluteArrivalMinute() - flight.absoluteDepartureMinute();
                int newCost = current.costMinutes + wait + duration;

                List<Flight> newFlights = new ArrayList<>(current.flights);
                newFlights.add(flight);
                queue.add(new SearchNode(flight.getDestCode(), flight.absoluteArrivalMinute(), newFlights, newCost));
            }
        }

        return routes;
    }

    private Map<String, List<Flight>> indexFlightsByOrigin(List<Flight> flights) {
        return flightIndexCache.computeIfAbsent(flights, key -> {
            Map<String, List<Flight>> index = new HashMap<>();
            for (Flight flight : key) {
                index.computeIfAbsent(flight.getOriginCode(), ignored -> new ArrayList<>()).add(flight);
            }
            for (List<Flight> originFlights : index.values()) {
                originFlights.sort(Comparator.comparingInt(Flight::absoluteDepartureMinute));
            }
            return index;
        });
    }

    private List<Flight> candidateFlightsFrom(List<Flight> originFlights, int minDeparture, int maxArrival) {
        if (originFlights.isEmpty()) return Collections.emptyList();
        int start = firstDepartureAtOrAfter(originFlights, minDeparture);
        if (start >= originFlights.size()) return Collections.emptyList();

        List<Flight> candidates = new ArrayList<>();
        for (int i = start; i < originFlights.size(); i++) {
            Flight flight = originFlights.get(i);
            if (flight.absoluteDepartureMinute() > maxArrival) break;
            if (flight.absoluteArrivalMinute() <= maxArrival) {
                candidates.add(flight);
                if (candidates.size() >= MAX_FLIGHTS_PER_LEG) break;
            }
        }
        return candidates;
    }

    private static int readPositiveInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int firstDepartureAtOrAfter(List<Flight> flights, int minute) {
        int low = 0;
        int high = flights.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (flights.get(mid).absoluteDepartureMinute() < minute) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private void reserve(Route route, int bags) {
        for (Flight flight : route.getFlights()) {
            flight.assignLoad(bags);
        }
        if (airportCapacityTimeline != null) {
            airportCapacityTimeline.reserve(route, bags);
        }
        List<Flight> routeFlights = route.getFlights();
        for (int i = 0; i < routeFlights.size() - 1; i++) {
            Airport airport = airportMap.get(routeFlights.get(i).getDestCode());
            if (airport != null) airport.addLoad(bags);
        }
    }

    private void release(Route route, int bags) {
        for (Flight flight : route.getFlights()) {
            flight.releaseLoad(bags);
        }
        if (airportCapacityTimeline != null) {
            airportCapacityTimeline.release(route, bags);
        }
        List<Flight> routeFlights = route.getFlights();
        for (int i = 0; i < routeFlights.size() - 1; i++) {
            Airport airport = airportMap.get(routeFlights.get(i).getDestCode());
            if (airport != null) airport.removeLoad(bags);
        }
    }

    public static class PartialRoute {
        public final Route ruta;
        public final int maletas;

        public PartialRoute(Route ruta, int maletas) {
            this.ruta = ruta;
            this.maletas = maletas;
        }

        @Override
        public String toString() {
            return String.format("PartialRoute[maletas=%d | %s]", maletas, ruta);
        }
    }

    private record RouteCacheKey(
            int flightsIdentity,
            String origin,
            String destination,
            int requestMinute,
            int suitcaseCount,
            int maxCandidates,
            int maxStops
    ) {
        static RouteCacheKey of(Shipment shipment, List<Flight> flights, int maxCandidates, int maxStops) {
            return new RouteCacheKey(
                    System.identityHashCode(flights),
                    shipment.getOriginCode(),
                    shipment.getDestCode(),
                    shipment.getRequestMinute(),
                    shipment.getSuitcaseCount(),
                    maxCandidates,
                    maxStops
            );
        }
    }

    private static class SearchNode {
        final String airport;
        final int availableMinute;
        final List<Flight> flights;
        final int costMinutes;

        SearchNode(String airport, int availableMinute, List<Flight> flights, int costMinutes) {
            this.airport = airport;
            this.availableMinute = availableMinute;
            this.flights = flights;
            this.costMinutes = costMinutes;
        }
    }
}
