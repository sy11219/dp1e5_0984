package org.e5.util;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;

import java.util.*;

/**
 * Buscador de rutas para el planificador ALNS de TASF.B2B.
 * Encapsula el Dijkstra modificado, verificación de factibilidad
 * y cálculo de plazos. Reutilizable por cualquier algoritmo del sistema.
 */
public class ALNSRouteFinder {

    private final Map<String, Airport> airportMap;
    private final int maxEscalas;
    private final Map<List<Flight>, Map<String, List<Flight>>> flightIndexCache = new IdentityHashMap<>();

    public ALNSRouteFinder(Map<String, Airport> airportMap) {
        this(airportMap, 4);
    }

    public ALNSRouteFinder(Map<String, Airport> airportMap, int maxEscalas) {
        this.airportMap = airportMap;
        this.maxEscalas = maxEscalas;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  BÚSQUEDA DE MEJOR RUTA — Dijkstra modificado
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Encuentra la ruta de menor tiempo de tránsito para un envío.
     * Extraído directamente de ALNS.encontrarMejorRuta().
     *
     * @param shipment envío a rutar
     * @param flights  vuelos disponibles con estado de carga actual
     * @return mejor Route encontrada, o null si no hay ruta válida en plazo
     */
    public Route findBestRoute(Shipment shipment, List<Flight> flights) {
        Map<String, List<Flight>> flightsByOrigin = indexFlightsByOrigin(flights);
        int deadlineMin = getDeadlineMinutes(shipment);
        int maxLlegada  = shipment.getRequestMinute() + deadlineMin;

        PriorityQueue<NodoBusqueda> cola = new PriorityQueue<>(
                Comparator.comparingInt(n -> n.costoMinutos));
        cola.add(new NodoBusqueda(
                shipment.getOriginCode(),
                shipment.getRequestMinute(),
                new ArrayList<>(), 0));

        Map<String, Integer> mejorCosto = new HashMap<>();

        while (!cola.isEmpty()) {
            NodoBusqueda actual = cola.poll();

            if (actual.aeropuerto.equals(shipment.getDestCode())) {
                if (actual.vuelos.isEmpty()) continue;
                Route ruta = new Route(
                        shipment.getShipmentId(),
                        shipment.getOriginCode(),
                        shipment.getDestCode(),
                        actual.vuelos,
                        shipment.getSuitcaseCount(),
                        shipment.getRequestMinute());
                if (ruta.isValid()) return ruta;
                continue;
            }

            if (actual.vuelos.size() >= maxEscalas) continue;

            String claveNodo = actual.aeropuerto + "@" + actual.minutoDisponible;
            if (mejorCosto.containsKey(claveNodo)
                    && mejorCosto.get(claveNodo) <= actual.costoMinutos) continue;
            mejorCosto.put(claveNodo, actual.costoMinutos);

            for (Flight f : flightsByOrigin.getOrDefault(actual.aeropuerto, Collections.emptyList())) {
                if (!f.hasSpaceFor(shipment.getSuitcaseCount())) continue;

                int salidaAbs  = f.absoluteDepartureMinute();
                int llegadaAbs = f.absoluteArrivalMinute();

                int minimoSalida = actual.minutoDisponible
                        + (actual.vuelos.isEmpty() ? 0 : Route.TRANSIT_TIME_MINUTES);

                if (salidaAbs < minimoSalida) continue;
                if (llegadaAbs > maxLlegada) continue;

                // Capacidad del aeropuerto intermedio (nunca del destino final)
                if (!f.getDestCode().equals(shipment.getDestCode())) {
                    Airport apt = airportMap.get(f.getDestCode());
                    if (apt != null && !apt.hasCapacityFor(shipment.getSuitcaseCount())) continue;
                }

                int espera     = salidaAbs - actual.minutoDisponible;
                int duracion   = llegadaAbs - salidaAbs;
                int nuevoCosto = actual.costoMinutos + espera + duracion;

                List<Flight> nuevosVuelos = new ArrayList<>(actual.vuelos);
                nuevosVuelos.add(f);

                cola.add(new NodoBusqueda(
                        f.getDestCode(), llegadaAbs, nuevosVuelos, nuevoCosto));
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PRE-CÁLCULO DE RUTAS CANDIDATAS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Pre-calcula hasta maxCandidatas rutas para un envío.
     * Las candidatas NO verifican capacidad actual — se usa esFeasible()
     * en tiempo real durante la reparación ALNS.
     *
     * Permite hasta 3 visitas por nodo (agrupado por hora) para
     * encontrar rutas alternativas diversas.
     *
     * @param shipment      envío para el que calcular candidatas
     * @param flights       vuelos disponibles
     * @param maxCandidatas número máximo de rutas a retornar
     * @return lista de rutas candidatas ordenadas de mejor a peor costo
     */
    public List<Route> findCandidateRoutes(Shipment shipment,
                                            List<Flight> flights,
                                            int maxCandidatas) {
        Map<String, List<Flight>> flightsByOrigin = indexFlightsByOrigin(flights);
        int deadlineMin = getDeadlineMinutes(shipment);
        int maxLlegada  = shipment.getRequestMinute() + deadlineMin;

        PriorityQueue<NodoBusqueda> cola = new PriorityQueue<>(
                Comparator.comparingInt(n -> n.costoMinutos));
        cola.add(new NodoBusqueda(
                shipment.getOriginCode(),
                shipment.getRequestMinute(),
                new ArrayList<>(), 0));

        List<Route> candidatas = new ArrayList<>();
        // Permite múltiples visitas al mismo nodo para hallar rutas alternativas
        Map<String, Integer> visitas = new HashMap<>();

        while (!cola.isEmpty() && candidatas.size() < maxCandidatas) {
            NodoBusqueda actual = cola.poll();

            if (actual.aeropuerto.equals(shipment.getDestCode())) {
                if (actual.vuelos.isEmpty()) continue;
                Route ruta = new Route(
                        shipment.getShipmentId(),
                        shipment.getOriginCode(),
                        shipment.getDestCode(),
                        actual.vuelos,
                        shipment.getSuitcaseCount(),
                        shipment.getRequestMinute());
                if (ruta.isValid()) candidatas.add(ruta);
                continue;
            }

            if (actual.vuelos.size() >= maxEscalas) continue;

            // Agrupar por hora para permitir rutas con distintos horarios
            String claveNodo = actual.aeropuerto + "@" + (actual.minutoDisponible / 60);
            int veces = visitas.getOrDefault(claveNodo, 0);
            if (veces >= 3) continue;
            visitas.put(claveNodo, veces + 1);

            for (Flight f : flightsByOrigin.getOrDefault(actual.aeropuerto, Collections.emptyList())) {
                int salidaAbs  = f.absoluteDepartureMinute();
                int llegadaAbs = f.absoluteArrivalMinute();

                int minimoSalida = actual.minutoDisponible
                        + (actual.vuelos.isEmpty() ? 0 : Route.TRANSIT_TIME_MINUTES);

                if (salidaAbs < minimoSalida) continue;
                if (llegadaAbs > maxLlegada) continue;

                // Para candidatas NO verificamos capacidad actual
                // (cambia con cada iteración ALNS — se verifica en esFeasible)

                int espera     = salidaAbs - actual.minutoDisponible;
                int duracion   = llegadaAbs - salidaAbs;
                int nuevoCosto = actual.costoMinutos + espera + duracion;

                List<Flight> nuevosVuelos = new ArrayList<>(actual.vuelos);
                nuevosVuelos.add(f);

                cola.add(new NodoBusqueda(
                        f.getDestCode(), llegadaAbs, nuevosVuelos, nuevoCosto));
            }
        }
        return candidatas;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  VERIFICACIÓN DE FACTIBILIDAD
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Verifica si una ruta pre-calculada sigue siendo factible en el estado actual.
     *
     * Una ruta es factible si:
     *   1. Todos sus vuelos tienen espacio (hasSpaceFor)
     *   2. Todos los aeropuertos intermedios tienen capacidad (hasCapacityFor)
     *   3. La ruta sigue siendo válida (tiempos coherentes)
     *
     * @param ruta    ruta candidata a verificar
     * @param maletas número de maletas del envío
     * @return true si la ruta puede usarse en el estado actual del sistema
     */
    public boolean esFeasible(Route ruta, int maletas) {
        if (ruta == null || !ruta.isValid()) return false;

        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size(); i++) {
            Flight f = vuelos.get(i);
            if (!f.hasSpaceFor(maletas)) return false;
            // Verificar aeropuerto intermedio (no el destino final)
            if (i < vuelos.size() - 1) {
                Airport apt = airportMap.get(f.getDestCode());
                if (apt != null && !apt.hasCapacityFor(maletas)) return false;
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CÁLCULO DE PLAZO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Plazo máximo de entrega según el enunciado:
     *   - Mismo continente:    1440 min (1 día)
     *   - Distinto continente: 2880 min (2 días)
     */
    public int getDeadlineMinutes(Shipment shipment) {
        Airport orig = airportMap.get(shipment.getOriginCode());
        Airport dest = airportMap.get(shipment.getDestCode());
        String contOrig = orig != null ? orig.getContinent() : "";
        String contDest = dest != null ? dest.getContinent() : "";
        return Shipment.getDeadlineMinutes(contOrig, contDest);
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

    // ════════════════════════════════════════════════════════════════════════
    //  CLASE INTERNA — NODO DE BÚSQUEDA
    // ════════════════════════════════════════════════════════════════════════

    private static class NodoBusqueda {
        final String       aeropuerto;
        final int          minutoDisponible;
        final List<Flight> vuelos;
        final int          costoMinutos;

        NodoBusqueda(String aeropuerto, int minutoDisponible,
                     List<Flight> vuelos, int costoMinutos) {
            this.aeropuerto       = aeropuerto;
            this.minutoDisponible = minutoDisponible;
            this.vuelos           = vuelos;
            this.costoMinutos     = costoMinutos;
        }
    }
}
