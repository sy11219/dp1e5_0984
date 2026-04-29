package org.e5.util;

import org.e5.model.Flight;
import org.e5.model.Route;

import java.util.*;

/**
 * Buscador de rutas candidatas para un envío.
 *
 * Dado un aeropuerto origen, un aeropuerto destino, un tiempo de disponibilidad
 * y la lista de vuelos disponibles, este módulo genera todas las rutas posibles
 * (secuencias de vuelos) que van de origen a destino.
 *
 * Se usa una búsqueda BFS (Breadth-First Search) con límite de escalas para
 * encontrar rutas directas y con conexiones. El Simulated Annealing luego
 * elige entre estas rutas candidatas la mejor según la función objetivo.
 *
 * Restricciones de la búsqueda:
 * - Máximo MAX_LAYOVERS escalas intermedias (para evitar rutas demasiado largas)
 * - El tiempo de conexión mínimo entre vuelos es Route.TRANSIT_TIME_MINUTES (10 min)
 * - No se visita el mismo aeropuerto dos veces (evita ciclos)
 * - Solo se consideran vuelos que puedan ser alcanzados dado el tiempo actual
 *
 * Si no hay ruta directa, se busca con una escala, luego con dos, etc.
 */
public class RouteFinder {

    /** Número máximo de escalas permitidas en una ruta. */
    private static final int MAX_LAYOVERS = 3;

    /** Número máximo de rutas candidatas a generar por envío (limita carga computacional). */
    private static final int MAX_CANDIDATES = 20;
    private Map<String,List<Flight>> flightsByOrigin;

    public RouteFinder(List<Flight> allFlights) {
        this.flightsByOrigin = indexFlightsByOrigin(allFlights);
    }

    private Map<String, List<Flight>> indexFlightsByOrigin(List<Flight> flights) {
        Map<String, List<Flight>> index = new HashMap<>();

        for (Flight flight : flights) {
            // computeIfAbsent: si la clave no existe, crea una nueva lista
            index.computeIfAbsent(flight.getOriginCode(), k -> new ArrayList<>())
                    .add(flight);
        }

        return index;
    }

    /**
     * Encuentra todas las rutas candidatas para un envío dado.
     *
     * Algoritmo BFS por niveles de escalas:
     * 1. Nivel 0: Busca vuelos directos origen→destino
     * 2. Nivel 1: Busca rutas con una escala: origen→X→destino
     * 3. Nivel 2: Busca rutas con dos escalas: origen→X→Y→destino
     * ...hasta MAX_LAYOVERS
     *
     * Para cada nivel, se retornan las rutas válidas encontradas.
     * Si hay rutas directas, igualmente se exploran rutas con escalas
     * para darle opciones al SA (puede haber rutas con escala que lleguen antes).
     *
     * @param originCode    Código ICAO del aeropuerto origen
     * @param destCode      Código ICAO del aeropuerto destino
     * @param availableFrom Minuto absoluto desde el que el envío está disponible
     * @param suitcaseCount Número de maletas del envío
     * @param allFlights    Lista completa de vuelos disponibles
     * @param shipmentId    ID del envío (para construir la Route)
     * @return Lista de rutas candidatas válidas (puede estar vacía si no hay ruta posible)
     */
    public List<Route> findCandidateRoutes(String originCode, String destCode,
                                            int availableFrom, int suitcaseCount,
                                            String shipmentId) {
        List<Route> candidates = new ArrayList<>();

        // Estado BFS: cada entrada es [lista de vuelos acumulados hasta ahora, aeropuerto actual, minuto actual]
        // Usamos una cola de "estados parciales" para explorar rutas
        Queue<RouteState> queue = new LinkedList<>();
        queue.add(new RouteState(originCode, availableFrom, new ArrayList<>(), new HashSet<>()));

        int explored = 0;

        while (!queue.isEmpty() && candidates.size() < MAX_CANDIDATES) {
            RouteState state = queue.poll();

            // Si la ruta ya tiene demasiadas escalas, no seguir expandiendo
            if (state.flightsSoFar.size() > MAX_LAYOVERS) continue;
            // Obtener vuelos que salen del aeropuerto actual (O(1) lookup)
            List<Flight> flightsFromCurrent = flightsByOrigin.get(state.currentAirport);

            // Si no hay vuelos desde este aeropuerto, saltar al siguiente estado
            if (flightsFromCurrent == null || flightsFromCurrent.isEmpty()) {
                continue;
            }
            // Buscar vuelos que parten del aeropuerto actual y pueden ser alcanzados
            for (Flight flight : flightsFromCurrent) {

                // El vuelo debe salir DESPUÉS de que el envío esté listo (con margen mínimo de 0 min)
                if (flight.absoluteDepartureMinute() < state.readyAt) continue;

                // No visitar aeropuertos ya visitados (evita ciclos)
                if (state.visitedAirports.contains(flight.getDestCode())) continue;

                // El vuelo debe tener espacio para las maletas
                if (!flight.hasSpaceFor(suitcaseCount)) continue;

                // Construir la nueva lista de vuelos
                List<Flight> newFlights = new ArrayList<>(state.flightsSoFar);
                newFlights.add(flight);

                // Si llegamos al destino, tenemos una ruta candidata completa
                if (flight.getDestCode().equals(destCode)) {
                    Route route = new Route(shipmentId, originCode, destCode,
                            newFlights, suitcaseCount, availableFrom);
                    if (route.isValid()) {
                        candidates.add(route);
                    }
                    // No seguir expandiendo desde el destino
                    continue;
                }

                // Si aún no llegamos al destino, seguir explorando desde el aeropuerto de llegada
                // El envío estará listo en el aeropuerto intermedio después de: llegada + *10* min de escala
                int nextReadyAt = flight.absoluteArrivalMinute() + Route.TRANSIT_TIME_MINUTES;
                Set<String> newVisited = new HashSet<>(state.visitedAirports);
                newVisited.add(flight.getDestCode());

                queue.add(new RouteState(flight.getDestCode(), nextReadyAt, newFlights, newVisited));
            }

            explored++;
            // Límite de seguridad para no explotar la memoria en grafos grandes
            if (explored > 5000) break;
        }

        // Ordenar candidatos por tiempo de llegada estimado (de más rápido a más lento)
        candidates.sort(Comparator.comparingInt(Route::calculateArrivalMinute));

        return candidates;
    }

    /**
     * Estado interno del BFS: representa un camino parcial hacia el destino.
     * Contiene el aeropuerto actual, el minuto en que el envío estaría listo,
     * los vuelos acumulados hasta ahora, y los aeropuertos ya visitados.
     */
    private static class RouteState {
        final String       currentAirport;  // Aeropuerto donde está el envío ahora
        final int          readyAt;         // Minuto en que el envío puede tomar el próximo vuelo
        final List<Flight> flightsSoFar;    // Vuelos ya tomados en esta ruta parcial
        final Set<String>  visitedAirports; // Aeropuertos ya visitados (para evitar ciclos)

        RouteState(String airport, int readyAt, List<Flight> flights, Set<String> visited) {
            this.currentAirport  = airport;
            this.readyAt         = readyAt;
            this.flightsSoFar    = flights;
            this.visitedAirports = visited;
        }
    }
}
