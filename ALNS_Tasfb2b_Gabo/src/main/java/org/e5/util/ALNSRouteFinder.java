package org.e5.util;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;

import java.util.*;

/**
 * Buscador de rutas para el planificador ALNS de TASF.B2B.
 *
 * Responsabilidades:
 *   - findBestRoute()           → mejor ruta completa (todas las maletas juntas)
 *   - findCandidateRoutes()     → múltiples candidatas pre-calculadas
 *   - findFractionalRoutes()    → fraccionamiento dinámico cuando no caben juntas
 *   - esFeasible()              → verifica factibilidad en el estado actual
 *   - getDeadlineMinutes()      → plazo máximo según continentes
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
     * Verifica capacidad actual de vuelos y aeropuertos intermedios.
     *
     * @param shipment envío a rutar
     * @param flights  vuelos disponibles con estado de carga actual
     * @return mejor Route, o null si no hay ruta válida en plazo
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
    //  FRACCIONAMIENTO DINÁMICO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fracciona dinámicamente las maletas de un envío entre los vuelos
     * disponibles cuando no caben todas juntas en ninguna ruta.
     *
     * Estrategia:
     *   1. Ordena los vuelos desde el origen por salida más temprana.
     *   2. Para cada vuelo con espacio disponible, asigna un lote del
     *      tamaño min(maletasPendientes, espacioDisponible).
     *   3. Si el vuelo es directo al destino → ruta de un solo vuelo.
     *   4. Si no es directo → ejecuta Dijkstra para ese lote desde ese vuelo.
     *   5. Repite hasta asignar todas las maletas o agotar vuelos.
     *
     * @param shipment  envío original con todas sus maletas
     * @param flights   vuelos disponibles con estado de carga actual
     * @return lista de PartialRoute, cada una con su lote de maletas asignado
     */
    public List<PartialRoute> findFractionalRoutes(Shipment shipment,
                                                    List<Flight> flights) {
        List<PartialRoute> resultado = new ArrayList<>();
        int pendientes = shipment.getSuitcaseCount();
        if (pendientes <= 0) return resultado;

        int deadlineMin = getDeadlineMinutes(shipment);
        int maxLlegada  = shipment.getRequestMinute() + deadlineMin;

        // Candidatos: vuelos desde el origen dentro del plazo, con espacio
        List<Flight> candidatos = new ArrayList<>();
        for (Flight f : flights) {
            if (!f.getOriginCode().equals(shipment.getOriginCode())) continue;
            if (f.absoluteDepartureMinute() < shipment.getRequestMinute()) continue;
            if (f.absoluteArrivalMinute() > maxLlegada) continue;
            if (f.availableSpace() <= 0) continue;
            candidatos.add(f);
        }
        // Ordenar por salida más temprana para respetar urgencia de plazo
        candidatos.sort(Comparator.comparingInt(Flight::absoluteDepartureMinute));

        int partIndex = 1;
        for (Flight f : candidatos) {
            if (pendientes <= 0) break;

            int lote = Math.min(pendientes, f.availableSpace());
            if (lote <= 0) continue;

            String partId = shipment.getShipmentId() + "_p" + partIndex;

            Route ruta;
            if (f.getDestCode().equals(shipment.getDestCode())) {
                // Vuelo directo — construir ruta de un solo vuelo
                List<Flight> vuelos = new ArrayList<>();
                vuelos.add(f);
                ruta = new Route(
                        partId,
                        shipment.getOriginCode(),
                        shipment.getDestCode(),
                        vuelos,
                        lote,
                        shipment.getRequestMinute());
            } else {
                // Vuelo con escala — Dijkstra para el lote desde este vuelo
                // Creamos un Shipment temporal con el tamaño del lote
                Shipment loteShipment = new Shipment(
                        partId,
                        shipment.getOriginCode(),
                        shipment.getDestCode(),
                        shipment.getRequestMinute(),
                        lote,
                        shipment.getClientId(),
                        shipment.getRawDate(),
                        shipment.getRawHour(),
                        shipment.getRawMinuteStr(),
                        shipment.getShipmentId(),
                        partIndex,
                        0, // totalParts desconocido aún
                        shipment.getSuitcaseCount()
                );
                ruta = findBestRoute(loteShipment, flights);
            }

            if (ruta != null && ruta.isValid()) {
                // Reservar en vuelos
                for (Flight vf : ruta.getFlights()) vf.assignLoad(lote);
                // Reservar en aeropuertos intermedios
                List<Flight> rutaVuelos = ruta.getFlights();
                for (int i = 0; i < rutaVuelos.size() - 1; i++) {
                    Airport apt = airportMap.get(rutaVuelos.get(i).getDestCode());
                    if (apt != null) apt.addLoad(lote);
                }

                resultado.add(new PartialRoute(ruta, lote));
                pendientes -= lote;
                partIndex++;
            }
        }

        return resultado;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PRE-CÁLCULO DE RUTAS CANDIDATAS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Pre-calcula hasta maxCandidatas rutas para un envío.
     * NO verifica capacidad actual — se usa esFeasible() en tiempo real.
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
     * Verifica si una ruta pre-calculada sigue siendo factible:
     * todos los vuelos tienen espacio y aeropuertos intermedios tienen capacidad.
     */
    public boolean esFeasible(Route ruta, int maletas) {
        if (ruta == null || !ruta.isValid()) return false;
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size(); i++) {
            Flight f = vuelos.get(i);
            if (!f.hasSpaceFor(maletas)) return false;
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
    //  CLASE: PartialRoute
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Ruta asignada a un lote parcial de maletas de un envío fraccionado.
     * Múltiples PartialRoute pueden corresponder al mismo Shipment original.
     */
    public static class PartialRoute {
        public final Route ruta;
        public final int   maletas;

        public PartialRoute(Route ruta, int maletas) {
            this.ruta    = ruta;
            this.maletas = maletas;
        }

        @Override
        public String toString() {
            return String.format("PartialRoute[maletas=%d | %s]", maletas, ruta);
        }
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
