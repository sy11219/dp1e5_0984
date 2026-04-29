package org.e5.planner;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.util.RouteFinder;

import java.util.*;

/**
 * Planificador de rutas de maletas usando Simulated Annealing (SA).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DESCRIPCIÓN DEL ALGORITMO
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Simulated Annealing es una metaheurística inspirada en el proceso de
 * enfriamiento del metal. Permite escapar de óptimos locales aceptando
 * soluciones peores con cierta probabilidad que decrece con la "temperatura".
 *
 * En este contexto:
 * - SOLUCIÓN: Asignación de una ruta a cada envío (qué vuelos toma cada maleta)
 * - FUNCIÓN OBJETIVO: Minimizar retrasos totales + penalizar rutas sin solución
 * - VECINO: Cambiar la ruta de un envío al azar entre sus rutas candidatas
 * - TEMPERATURA: Controla la probabilidad de aceptar soluciones peores
 *
 * ═══════════════════════════════════════════════════════════════════════
 * RESTRICCIONES DEL PROBLEMA
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 1. Capacidad de vuelos: Un vuelo no puede llevar más maletas de su límite.
 * 2. Capacidad de aeropuertos: Un aeropuerto no puede almacenar más maletas
 *    de su capacidad (considera llegadas y salidas en el tiempo).
 * 3. Plazos de entrega:
 *    - Mismo continente: 12 horas (720 min) desde solicitud
 *    - Distinto continente: 24 horas (1440 min) desde solicitud
 * 4. Tiempo de conexión: 10 minutos mínimos entre llegada y siguiente vuelo
 *    en aeropuertos intermedios.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PARÁMETROS DEL SA (ajustables)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * - INITIAL_TEMPERATURE: Temperatura inicial (alta → acepta peores soluciones)
 * - COOLING_RATE: Factor de enfriamiento por iteración (0.99 → enfría lentamente)
 * - MIN_TEMPERATURE: Temperatura mínima (detiene el algoritmo)
 * - ITERATIONS_PER_TEMP: Iteraciones por nivel de temperatura
 */
public class SimulatedAnnealingPlanner {

    // ── Parámetros del SA ────────────────────────────────────────────────────
    private static final double INITIAL_TEMPERATURE   = 10000.0;
    private static final double COOLING_RATE          = 0.98;
    private static final double MIN_TEMPERATURE       = 0.1;
    private static final int    ITERATIONS_PER_TEMP   = 50;

    // ════════════════════════════════════════════════════════════════════════
    // PENALIZACIONES NORMALIZADAS (CORREGIDAS)
    // ════════════════════════════════════════════════════════════════════════
    // Penalización base por envío sin ruta posible (escala normalizada 0-1000)
    private static final double PENALTY_NO_ROUTE_NORMALIZED = 500.0;
    // Penalización máxima por retraso (100 puntos = retraso del 100% del deadline)
    private static final double MAX_PENALTY_DELAY_NORMALIZED = 100.0;
    // Penalización máxima por sobrecarga de vuelo (100 puntos = 100% de exceso)
    private static final double MAX_PENALTY_FLIGHT_OVERLOAD = 100.0;
    // Penalización máxima por sobrecarga de aeropuerto (100 puntos = 100% de exceso)
    private static final double MAX_PENALTY_AIRPORT_OVERLOAD = 100.0;
    // Factor de escala para convertir penalizaciones normalizadas a costo final
    private static final double COST_SCALE_FACTOR = 1.0;

    // ════════════════════════════════════════════════════════════════════════
    // CACHES PARA EVALUACIÓN INCREMENTAL (CORREGIDOS)
    // ════════════════════════════════════════════════════════════════════════
    // Cache de cargas de vuelos: flightId → carga actual proyectada
    private Map<String, Integer> cachedFlightLoads;

    // Cache de eventos por aeropuerto: airportCode → {minute → deltaNeto}
    // Usamos Map<Integer,Integer> en lugar de List<Event> para evitar acumulación infinita
    private Map<String, Map<Integer, Integer>> cachedAirportEvents;

    // Cache del costo base (sin penalizaciones de capacidad)
    private double cachedBaseCost;

    // ── Dependencias ──────────────────────────────────────────────────────────
    private final Map<String, Airport> airportMap;   // Código ICAO → Airport
    private final List<Flight>         flights;       // Todos los vuelos disponibles
    private final RouteFinder          routeFinder;   // Módulo de búsqueda de rutas candidatas
    private final Random               random;

    /**
     * Constructor del planificador.
     *
     * @param airports Lista de todos los aeropuertos
     * @param flights  Lista de todos los vuelos disponibles (todos los días)
     */
    public SimulatedAnnealingPlanner(List<Airport> airports, List<Flight> flights) {
        this.flights    = flights;
        this.routeFinder = new RouteFinder(flights);
        this.random     = new Random(42); // Semilla fija para reproducibilidad

        // Construir mapa de aeropuertos por código ICAO
        this.airportMap = new HashMap<>();
        for (Airport a : airports) {
            airportMap.put(a.getCode(), a);
        }
    }

    /**
     * Ejecuta la planificación de rutas para todos los envíos.
     *
     * PROCESO PRINCIPAL:
     * 1. Para cada envío, genera rutas candidatas usando BFS (RouteFinder)
     * 2. Inicializa una solución tomando la mejor ruta candidata para cada envío
     * 3. Ejecuta el ciclo de Simulated Annealing para mejorar la solución
     * 4. Al final, aplica la mejor solución encontrada y registra los resultados
     *
     * @param shipments Lista de todos los envíos a planificar
     * @return Lista de envíos con sus rutas asignadas (modificados in-place)
     */
    public List<Shipment> plan(List<Shipment> shipments) {
        System.out.println("\n[SA Planner] Iniciando planificacion con Simulated Annealing...");
        System.out.printf("[SA Planner] Envios a planificar: %d | Vuelos disponibles: %d%n",
                shipments.size(), flights.size());

        // ── Paso 1: Generar rutas candidatas para cada envío ─────────────────
        System.out.println("[SA Planner] Generando rutas candidatas (BFS)...");
        Map<String, List<Route>> candidatesMap = new HashMap<>();
        int noRoutesCount = 0;

        for (Shipment s : shipments) {
            List<Route> candidates = routeFinder.findCandidateRoutes(
                    s.getOriginCode(), s.getDestCode(),
                    s.getRequestMinute(), s.getSuitcaseCount(),
                    s.getShipmentId()
            );
            candidatesMap.put(s.getShipmentId(), candidates);

            if (candidates.isEmpty()) {
                noRoutesCount++;
                System.out.printf("  [AVISO] Sin rutas candidatas para envio %s (%s→%s)%n",
                        s.getShipmentId(), s.getOriginCode(), s.getDestCode());
            } else {
                System.out.printf("  Envio %s: %d rutas candidatas encontradas%n",
                        s.getShipmentId(), candidates.size());
            }
        }

        if (noRoutesCount > 0) {
            System.out.printf("[SA Planner] AVISO: %d envios sin rutas candidatas.%n", noRoutesCount);
        }

        // ── Paso 2: Solución inicial ──────────────────────────────────────────
        int[] currentSolution = initializeSolutionSmart(shipments, candidatesMap);

        // Usar versión con cache para inicialización
        double currentCost    = evaluateSolutionWithCache(shipments, candidatesMap, currentSolution);

        int[]  bestSolution   = Arrays.copyOf(currentSolution, currentSolution.length);
        double bestCost       = currentCost;

        System.out.printf("[SA Planner] Costo inicial: %.2f%n", currentCost);

        // ── Paso 3: Ciclo de Simulated Annealing ─────────────────────────────
        double temperature = INITIAL_TEMPERATURE;
        int    totalIter   = 0, improvements = 0, accepted    = 0;

        while (temperature > MIN_TEMPERATURE) {
            for (int iter = 0; iter < ITERATIONS_PER_TEMP; iter++) {
                totalIter++;

                // Generar una solución vecina: cambiar la ruta de un envío al azar
                int[] neighbor = generateNeighbor(currentSolution, shipments, candidatesMap);
                if (neighbor == null) continue;

                // Encontrar qué índice cambió (para evaluación incremental)
                int changedIdx = -1;
                for (int i = 0; i < currentSolution.length; i++) {
                    if (currentSolution[i] != neighbor[i]) {
                        changedIdx = i;
                        break;
                    }
                }
                if (changedIdx == -1) continue;

                // EVALUACIÓN INCREMENTAL: calcular solo el delta de costo
                double deltaCost = evaluateSolutionIncremental(
                        shipments, candidatesMap, currentSolution, neighbor, changedIdx);
                double neighborCost = currentCost + deltaCost;

                double delta = neighborCost - currentCost;

                // Criterio de aceptación SA:
                // Si la solución vecina es mejor (delta < 0), aceptar siempre.
                // Si es peor (delta > 0), aceptar con probabilidad e^(-delta/T).
                if (delta < 0 || random.nextDouble() < Math.exp(-delta / temperature)) {
                    currentSolution = neighbor;
                    currentCost     = neighborCost;
                    accepted++;

                    if (currentCost < bestCost) {
                        bestSolution = Arrays.copyOf(currentSolution, currentSolution.length);
                        bestCost     = currentCost;
                        improvements++;
                    }
                }
            }

            temperature *= COOLING_RATE;

            // Logging para depuración
            if (totalIter % 100 == 0) {
                System.out.printf("[DEBUG] Iter=%d | Temp=%.2f | Cost=%.0f | Best=%.0f%n",
                        totalIter, temperature, currentCost, bestCost);
            }
        }

        System.out.printf("[SA Planner] SA completado: %d iteraciones | %d aceptadas | %d mejoras%n",
                totalIter, accepted, improvements);
        System.out.printf("[SA Planner] Costo final: %.2f%n", bestCost);

        // ── Paso 4: Aplicar la mejor solución encontrada ──────────────────────
        applySolution(shipments, candidatesMap, bestSolution);

        return shipments;
    }

    // ════════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES DEL SA
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Inicializa la solución seleccionando la primera ruta candidata para cada envío.
     * Para envíos sin rutas, asigna -1 (sin ruta).
     *
     * @param shipments     Lista de envíos
     * @param candidatesMap Mapa de rutas candidatas por ID de envío
     * @return Array de índices de ruta seleccionada por envío (paralelo a shipments)
     */
    private int[] initializeSolutionSmart(List<Shipment> shipments,
                                          Map<String, List<Route>> candidatesMap) {
        int[] solution = new int[shipments.size()];

        // Mapa temporal de carga proyectada
        Map<String, Integer> projectedFlightLoad = new HashMap<>();

        // Ordenar envíos por "urgencia": primero los con menos alternativas o deadlines más cortos
        List<Integer> shipmentOrder = new ArrayList<>();
        for (int i = 0; i < shipments.size(); i++) {
            shipmentOrder.add(i);
        }
        shipmentOrder.sort(Comparator.comparingInt(i -> {
            Shipment s = shipments.get(i);
            List<Route> cands = candidatesMap.get(s.getShipmentId());
            int numRoutes = (cands != null) ? cands.size() : 0;
            // Priorizar: menos rutas → más urgente; luego deadline más cercano
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest = airportMap.get(s.getDestCode());
            int deadlineMin = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent()) : 1440;
            return numRoutes * 10000 + (s.getRequestMinute() + deadlineMin);
        }));

        // Asignar rutas una por una, eligiendo la que menos restrinja el futuro
        for (int idx : shipmentOrder) {
            Shipment s = shipments.get(idx);
            List<Route> cands = candidatesMap.get(s.getShipmentId());

            if (cands == null || cands.isEmpty()) {
                solution[idx] = -1;
                continue;
            }

            int bestRouteIdx = 0;
            double bestScore = Double.MAX_VALUE;

            for (int r = 0; r < cands.size(); r++) {
                Route route = cands.get(r);
                double score = 0.0;

                // Score normalizado por carga de vuelos (0-100 por vuelo)
                for (Flight f : route.getFlights()) {
                    int currentLoad = projectedFlightLoad.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                    int newLoad = currentLoad + s.getSuitcaseCount();
                    score += computeNormalizedFlightOverloadPenalty(newLoad, f.getMaxCapacity());
                }

                // Score normalizado por retraso (0-100)
                int arrival = route.calculateArrivalMinute();
                Airport origin = airportMap.get(s.getOriginCode());
                Airport dest = airportMap.get(s.getDestCode());
                int deadlineMin = (origin != null && dest != null)
                        ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent()) : 1440;
                int deadline = s.getRequestMinute() + deadlineMin;
                int delay = Math.max(0, arrival - deadline);

                score += computeNormalizedDelayPenalty(delay, deadlineMin) * 0.5;  // Peso menor en inicialización

                if (score < bestScore) {
                    bestScore = score;
                    bestRouteIdx = r;
                }
            }

            solution[idx] = bestRouteIdx;

            // Actualizar carga proyectada para siguientes asignaciones
            for (Flight f : cands.get(bestRouteIdx).getFlights()) {
                int current = projectedFlightLoad.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                projectedFlightLoad.put(f.getFlightId(), current + s.getSuitcaseCount());
            }
        }

        return solution;
    }

    /**
     * Evalúa solución Y actualiza caches para evaluación incremental.
     * Usar SOLO en inicialización o cuando se reinicia el estado.
     *
     * CORRECCIÓN: Inicializa correctamente los caches con formato Map<Integer,Integer>
     */
    private double evaluateSolutionWithCache(List<Shipment> shipments,
                                             Map<String, List<Route>> candidatesMap,
                                             int[] solution) {
        // Resetear caches
        cachedFlightLoads = new HashMap<>();
        cachedAirportEvents = new HashMap<>();  // ← Map<String, Map<Integer,Integer>>
        cachedBaseCost = 0.0;

        // Primero: calcular cargas base de vuelos (sin los envíos de esta solución)
        for (Flight f : flights) {
            cachedFlightLoads.put(f.getFlightId(), f.getAssignedLoad());
        }

        double totalCost = 0.0;

        for (int i = 0; i < shipments.size(); i++) {
            Shipment s = shipments.get(i);
            int routeIdx = solution[i];

            if (routeIdx == -1) {
                // Penalización normalizada por envío sin ruta
                totalCost += PENALTY_NO_ROUTE_NORMALIZED;
                continue;
            }

            List<Route> candidates = candidatesMap.get(s.getShipmentId());
            if (candidates == null || routeIdx >= candidates.size()) {
                totalCost += PENALTY_NO_ROUTE_NORMALIZED;
                continue;
            }

            Route route = candidates.get(routeIdx);

            // Calcular penalización normalizada y actualizar caches
            Set<String> dummyAffected = new HashSet<>();
            double shipmentCost = computeShipmentDeltaCost(s, route, +1, dummyAffected);
            totalCost += shipmentCost;
        }

        // Calcular penalizaciones de capacidad de aeropuertos (normalizadas)
        totalCost += computeAirportCapacityPenalties();

        cachedBaseCost = totalCost;
        return totalCost;
    }

    /**
     * Evalúa SOLO el cambio de costo al modificar los envíos en changedIndices.
     * REQUIERE que evaluateSolutionWithCache() se haya llamado antes.
     *
     * CORRECCIÓN: Ahora calcula delta completo incluyendo aeropuertos afectados
     */
    private double evaluateSolutionIncremental(List<Shipment> shipments,
                                               Map<String, List<Route>> candidatesMap,
                                               int[] oldSolution,
                                               int[] newSolution,
                                               int changedIndex) {
        Shipment s = shipments.get(changedIndex);
        Set<String> affectedAirports = new HashSet<>();

        // Identificar aeropuertos afectados por la ruta anterior y la nueva
        if (oldSolution[changedIndex] != -1) {
            List<Route> oldCands = candidatesMap.get(s.getShipmentId());
            if (oldCands != null && oldSolution[changedIndex] < oldCands.size()) {
                collectAffectedAirports(oldCands.get(oldSolution[changedIndex]), affectedAirports);
            }
        }
        if (newSolution[changedIndex] != -1) {
            List<Route> newCands = candidatesMap.get(s.getShipmentId());
            if (newCands != null && newSolution[changedIndex] < newCands.size()) {
                collectAffectedAirports(newCands.get(newSolution[changedIndex]), affectedAirports);
            }
        }

        // Calcular penalización de aeropuertos ANTES del cambio
        double oldAirportPenalty = computeAirportPenaltyForAirports(affectedAirports);

        double deltaCost = 0.0;

        // Remover contribución de la ruta anterior
        if (oldSolution[changedIndex] != -1) {
            List<Route> oldCandidates = candidatesMap.get(s.getShipmentId());
            if (oldCandidates != null && oldSolution[changedIndex] < oldCandidates.size()) {
                Route oldRoute = oldCandidates.get(oldSolution[changedIndex]);
                deltaCost -= computeShipmentDeltaCost(s, oldRoute, -1, new HashSet<>());
            }
        }

        // Agregar contribución de la nueva ruta
        if (newSolution[changedIndex] != -1) {
            List<Route> newCandidates = candidatesMap.get(s.getShipmentId());
            if (newCandidates != null && newSolution[changedIndex] < newCandidates.size()) {
                Route newRoute = newCandidates.get(newSolution[changedIndex]);
                deltaCost += computeShipmentDeltaCost(s, newRoute, +1, new HashSet<>());
            }
        }

        // Calcular penalización de aeropuertos DESPUÉS del cambio
        double newAirportPenalty = computeAirportPenaltyForAirports(affectedAirports);
        deltaCost += (newAirportPenalty - oldAirportPenalty);

        return deltaCost * COST_SCALE_FACTOR;
    }

    /**
     * Calcula el cambio de costo al agregar/remover un envío de una ruta.
     * VERSIÓN NORMALIZADA: usa penalizaciones en escala 0-100 en lugar de valores absolutos.
     *
     * @param s Envío a procesar
     * @param route Ruta candidata
     * @param delta +1 para agregar, -1 para remover
     * @param affectedAirports Set para trackear aeropuertos modificados (output)
     * @return delta de costo normalizado (puede ser negativo si se quita una penalización)
     */
    private double computeShipmentDeltaCost(Shipment s, Route route, int delta,
                                            Set<String> affectedAirports) {
        double deltaCost = 0.0;
        int suitcaseCount = s.getSuitcaseCount();

        // === 1. Penalización por retraso (NORMALIZADA) ===
        int arrivalMinute = route.calculateArrivalMinute();
        Airport origin = airportMap.get(s.getOriginCode());
        Airport dest = airportMap.get(s.getDestCode());
        int deadlineMinutes = (origin != null && dest != null)
                ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                : 1440;
        int deadline = s.getRequestMinute() + deadlineMinutes;
        int delayMinutes = Math.max(0, arrivalMinute - deadline);

        // Calcular penalización normalizada por retraso
        double delayPenalty = computeNormalizedDelayPenalty(delayMinutes, deadlineMinutes);
        deltaCost += delayPenalty * delta;  // delta: +1 para agregar, -1 para remover

        // === 2. Actualizar cargas de vuelos y calcular penalización por capacidad (NORMALIZADA) ===
        for (Flight f : route.getFlights()) {
            String flightId = f.getFlightId();
            int currentLoad = cachedFlightLoads.getOrDefault(flightId, f.getAssignedLoad());
            int newLoad = currentLoad + (suitcaseCount * delta);

            // Calcular penalización NORMALIZADA ANTES y DESPUÉS del cambio
            double penaltyBefore = computeNormalizedFlightOverloadPenalty(currentLoad, f.getMaxCapacity());
            double penaltyAfter = computeNormalizedFlightOverloadPenalty(newLoad, f.getMaxCapacity());

            // Delta de penalización
            deltaCost += (penaltyAfter - penaltyBefore);

            // Actualizar cache
            cachedFlightLoads.put(flightId, newLoad);
        }

        // === 3. Actualizar eventos de aeropuertos (CORREGIDO: usa Map para evitar acumulación) ===
        updateAirportEventsCache(route, suitcaseCount, delta, affectedAirports);

        return deltaCost * COST_SCALE_FACTOR;
    }

    /**
     * Penalización por capacidad de vuelo (función auxiliar reutilizable)
     */
    private double computeFlightCapacityPenalty(int load, int maxCapacity) {
        if (load <= maxCapacity) return 0.0;
        return (load - maxCapacity) * 500.0;  // Tu penalización original
    }

    /**
     * Actualiza el cache de eventos de aeropuertos para un envío.
     *
     * CORRECCIÓN CRÍTICA: Usa Map<Integer,Integer> para acumular deltas por minuto,
     * evitando que la lista de eventos crezca infinitamente con cada iteración.
     *
     * @param route Ruta del envío
     * @param suitcaseCount Cantidad de maletas
     * @param delta +1 para agregar evento, -1 para remover
     * @param affectedAirports Set para trackear aeropuertos modificados
     */
    private void updateAirportEventsCache(Route route, int suitcaseCount, int delta,
                                          Set<String> affectedAirports) {
        List<Flight> flightsInRoute = route.getFlights();

        for (int leg = 0; leg < flightsInRoute.size(); leg++) {
            Flight f = flightsInRoute.get(leg);
            String apCode = f.getDestCode();
            Airport ap = airportMap.get(apCode);
            if (ap == null) continue;

            int arrivalMin = f.getArrivalMinute();

            // Trackear aeropuerto como afectado
            affectedAirports.add(apCode);

            // Obtener o crear mapa de eventos para este aeropuerto
            // Formato: minute → deltaNeto (acumulado)
            Map<Integer, Integer> events = cachedAirportEvents
                    .computeIfAbsent(apCode, k -> new HashMap<>());

            // Evento de llegada: sumar delta al acumulado de ese minuto
            // merge() maneja automáticamente la suma si la clave ya existe
            events.merge(arrivalMin, suitcaseCount * delta, Integer::sum);

            // Evento de salida
            int departureMin;
            if (apCode.equals(route.getFinalDestCode())) {
                // Destino final → salida 10 min después de llegada
                departureMin = arrivalMin + 10;
            } else if (leg + 1 < flightsInRoute.size()) {
                // Aeropuerto intermedio → salida en el próximo vuelo
                departureMin = flightsInRoute.get(leg + 1).getDepartureMinute();
            } else {
                continue;  // No hay evento de salida válido
            }

            // Evento de salida: restar maletas (delta negativo para salida)
            events.merge(departureMin, -suitcaseCount * delta, Integer::sum);
        }
    }

    /**
     * Calcula penalizaciones de capacidad de aeropuertos desde el cache.
     * VERSIÓN NORMALIZADA: usa penalizaciones en escala 0-100.
     */
    private double computeAirportCapacityPenalties() {
        double totalPenalty = 0.0;

        for (Map.Entry<String, Map<Integer, Integer>> entry : cachedAirportEvents.entrySet()) {
            String apCode = entry.getKey();
            Airport ap = airportMap.get(apCode);
            if (ap == null) continue;

            Map<Integer, Integer> events = entry.getValue();
            if (events.isEmpty()) continue;

            // Obtener solo los minutos con eventos y ordenarlos
            List<Integer> sortedMinutes = new ArrayList<>(events.keySet());
            Collections.sort(sortedMinutes);

            int currentLoad = ap.getCurrentLoad();
            int maxCapacity = ap.getMaxCapacity();

            for (int minute : sortedMinutes) {
                currentLoad += events.get(minute);
                // Penalización NORMALIZADA por sobrecarga
                if (currentLoad > maxCapacity) {
                    totalPenalty += computeNormalizedAirportOverloadPenalty(currentLoad, maxCapacity);
                }
            }
        }

        return totalPenalty * COST_SCALE_FACTOR;
    }

    /**
     * Calcula penalización de aeropuertos SOLO para un conjunto específico de aeropuertos.
     * Se usa para calcular deltas incrementales sin recorrer todo el mapa.
     */
    private double computeAirportPenaltyForAirports(Set<String> airportCodes) {
        double totalPenalty = 0.0;
        for (String apCode : airportCodes) {
            Airport ap = airportMap.get(apCode);
            if (ap == null) continue;

            Map<Integer, Integer> events = cachedAirportEvents.get(apCode);
            if (events == null || events.isEmpty()) continue;

            List<Integer> sortedMinutes = new ArrayList<>(events.keySet());
            Collections.sort(sortedMinutes);

            int currentLoad = ap.getCurrentLoad();
            int maxCapacity = ap.getMaxCapacity();
            for (int minute : sortedMinutes) {
                currentLoad += events.get(minute);
                if (currentLoad > maxCapacity) {
                    totalPenalty += computeNormalizedAirportOverloadPenalty(currentLoad, maxCapacity);
                }
            }
        }
        return totalPenalty * COST_SCALE_FACTOR;
    }

    /**
     * Identifica todos los aeropuertos por los que pasa una ruta.
     */
    private void collectAffectedAirports(Route route, Set<String> affectedAirports) {
        if (route == null) return;
        for (Flight f : route.getFlights()) {
            affectedAirports.add(f.getDestCode());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MÉTODOS DE PENALIZACIÓN NORMALIZADA (NUEVOS)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Calcula penalización normalizada por retraso de un envío.
     * Fórmula: penalty = min(MAX_PENALTY_DELAY, (delayMinutes / deadlineMinutes) * 100)
     */
    private double computeNormalizedDelayPenalty(int delayMinutes, int deadlineMinutes) {
        if (delayMinutes <= 0) return 0.0;
        double delayRatio = (double) delayMinutes / Math.max(1, deadlineMinutes);
        return Math.min(MAX_PENALTY_DELAY_NORMALIZED, delayRatio * 100.0);
    }

    /**
     * Calcula penalización normalizada por sobrecarga de vuelo.
     * Fórmula: penalty = min(MAX_PENALTY_FLIGHT_OVERLOAD, (excess / maxCapacity) * 100)
     */
    private double computeNormalizedFlightOverloadPenalty(int currentLoad, int maxCapacity) {
        if (currentLoad <= maxCapacity) return 0.0;
        int excess = currentLoad - maxCapacity;
        double excessRatio = (double) excess / Math.max(1, maxCapacity);
        return Math.min(MAX_PENALTY_FLIGHT_OVERLOAD, excessRatio * 100.0);
    }

    /**
     * Calcula penalización normalizada por sobrecarga de aeropuerto.
     * Fórmula: penalty = min(MAX_PENALTY_AIRPORT_OVERLOAD, (excess / maxCapacity) * 100)
     */
    private double computeNormalizedAirportOverloadPenalty(int currentLoad, int maxCapacity) {
        if (currentLoad <= maxCapacity) return 0.0;
        int excess = currentLoad - maxCapacity;
        double excessRatio = (double) excess / Math.max(1, maxCapacity);
        return Math.min(MAX_PENALTY_AIRPORT_OVERLOAD, excessRatio * 100.0);
    }

    /**
     * Evalúa el costo de una solución completa.
     *
     * Función objetivo (a MINIMIZAR):
     * Para cada envío:
     *   - Si no tiene ruta: +PENALTY_NO_ROUTE
     *   - Si tiene ruta y llega a tiempo: +0
     *   - Si tiene ruta y llega tarde: +PENALTY_PER_DELAY_MIN * minutos_de_retraso
     *
     * Adicionalmente, se penaliza si un vuelo supera su capacidad o un aeropuerto
     * supera la suya (restricciones hard, penalización muy alta).
     *
     * Usar evaluateSolutionWithCache() + evaluateSolutionIncremental() para mejor rendimiento.
     *
     * @param shipments     Lista de envíos
     * @param candidatesMap Mapa de rutas candidatas
     * @param solution      Indices de rutas seleccionadas
     * @return Costo total (menor es mejor)
     */
    private double evaluateSolution(List<Shipment> shipments,
                                    Map<String, List<Route>> candidatesMap,
                                    int[] solution) {
        // Contadores de carga para vuelos en esta evaluación
        Map<String, Integer> flightLoad    = new HashMap<>();
        Map<String, List<Event>> airportEvents = new HashMap<>();
        double totalCost = 0.0;

        for (int i = 0; i < shipments.size(); i++) {
            Shipment s = shipments.get(i);
            int routeIdx = solution[i];

            if (routeIdx == -1) {
                // Sin ruta posible
                totalCost += PENALTY_NO_ROUTE_NORMALIZED; // Actualizado a normalizado
                continue;
            }

            List<Route> candidates = candidatesMap.get(s.getShipmentId());
            if (candidates == null || routeIdx >= candidates.size()) {
                totalCost += PENALTY_NO_ROUTE_NORMALIZED;
                continue;
            }

            Route route = candidates.get(routeIdx);
            int arrivalMinute = route.calculateArrivalMinute();

            // Calcular el plazo según continentes
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest   = airportMap.get(s.getDestCode());
            int deadlineMinutes = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                    : 1440;
            int deadline = s.getRequestMinute() + deadlineMinutes;

            // Penalización por retraso (NORMALIZADA)
            int delay = Math.max(0, arrivalMinute - deadline);
            totalCost += computeNormalizedDelayPenalty(delay, deadlineMinutes);

            // Verificar capacidad de vuelos en esta ruta (NORMALIZADA)
            for (Flight f : route.getFlights()) {
                int load = flightLoad.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                load += s.getSuitcaseCount();
                if (load > f.getMaxCapacity()) {
                    totalCost += computeNormalizedFlightOverloadPenalty(load, f.getMaxCapacity());
                }
                flightLoad.put(f.getFlightId(), load);
            }

            // Registrar eventos en aeropuertos
            List<Flight> flightsInRoute = route.getFlights();
            for (int leg = 0; leg < flightsInRoute.size(); leg++) {
                Flight f = flightsInRoute.get(leg);
                String apCode = f.getDestCode();
                Airport ap = airportMap.get(apCode);
                if (ap == null) continue;

                int suitcaseCount = s.getSuitcaseCount();
                int arrivalMin = f.getArrivalMinute();

                // Evento de llegada
                airportEvents.computeIfAbsent(apCode, k -> new ArrayList<>())
                        .add(new Event(arrivalMin, +suitcaseCount));

                // Evento de salida
                if (apCode.equals(s.getDestCode())) {
                    // Destino final → salida 10 min después
                    airportEvents.get(apCode)
                            .add(new Event(arrivalMin + 10, -suitcaseCount));
                } else {
                    // Aeropuerto intermedio → salida en el próximo vuelo
                    if (leg + 1 < flightsInRoute.size()) {
                        Flight nextFlight = flightsInRoute.get(leg + 1);
                        airportEvents.get(apCode)
                                .add(new Event(nextFlight.getDepartureMinute(), -suitcaseCount));
                    }
                }
            }
        }
        // Evaluar capacidad de aeropuertos con timeline de eventos (NORMALIZADA)
        for (Map.Entry<String, List<Event>> entry : airportEvents.entrySet()) {
            String apCode = entry.getKey();
            Airport ap = airportMap.get(apCode);
            List<Event> events = entry.getValue();
            events.sort(Comparator.comparingInt(e -> e.minute));

            int currentLoad = ap.getCurrentLoad();
            for (Event e : events) {
                currentLoad += e.delta;
                if (currentLoad > ap.getMaxCapacity()) {
                    totalCost += computeNormalizedAirportOverloadPenalty(currentLoad, ap.getMaxCapacity());
                }
            }
        }
        return totalCost * COST_SCALE_FACTOR;
    }

    // Clase auxiliar para eventos
    private static class Event {
        final int minute;
        final int delta; // +maletas al llegar, -maletas al salir
        Event(int minute, int delta) {
            this.minute = minute;
            this.delta = delta;
        }
    }

    /**
     * Genera una solución vecina cambiando la ruta de un envío seleccionado al azar.
     *
     * Estrategias de generación de vecinos (elegidas aleatoriamente):
     * - SWAP: Cambiar la ruta del envío i a otra de sus rutas candidatas
     * - SWAP_PAIR: Intercambiar las rutas entre dos envíos (si ambos tienen candidatas comunes)
     *
     * @param current       Solución actual (array de índices)
     * @param shipments     Lista de envíos
     * @param candidatesMap Mapa de rutas candidatas
     * @return Nueva solución vecina, o null si no se pudo generar
     */
    private int[] generateNeighbor(int[] current, List<Shipment> shipments,
                                   Map<String, List<Route>> candidatesMap) {
        int[] neighbor = Arrays.copyOf(current, current.length);

        // Elegir un envío al azar que tenga múltiples rutas candidatas
        int attempts = 0;
        while (attempts < 20) {
            int i = random.nextInt(shipments.size());
            Shipment s = shipments.get(i);
            List<Route> candidates = candidatesMap.get(s.getShipmentId());

            if (candidates != null && candidates.size() > 1) {
                // Cambiar a una ruta diferente al azar
                int newRouteIdx;
                do {
                    newRouteIdx = random.nextInt(candidates.size());
                } while (newRouteIdx == current[i]);

                neighbor[i] = newRouteIdx;
                return neighbor;
            }
            attempts++;
        }

        return null; // No se encontró un vecino válido
    }

    // NUEVO MÉTODO (Muy lento, dejado por mientras)
    private int[] generateSmartNeighbor(int[] current, List<Shipment> shipments,
                                        Map<String, List<Route>> candidatesMap) {
        // Calcular peso por envío: mayor peso = más probable de ser seleccionado
        double[] weights = new double[shipments.size()];
        double sumWeights = 0.0;

        for (int i = 0; i < shipments.size(); i++) {
            Shipment s = shipments.get(i);
            List<Route> cands = candidatesMap.get(s.getShipmentId());
            int routeIdx = current[i];

            // Peso base: 1.0 para todos
            double weight = 1.0;

            // + Peso si no tiene ruta
            if (routeIdx == -1 || cands == null || cands.isEmpty()) {
                weight += 10.0;
            }
            // + Peso si la ruta actual llega tarde
            else if (routeIdx < cands.size()) {
                Route route = cands.get(routeIdx);
                int arrival = route.calculateArrivalMinute();
                Airport origin = airportMap.get(s.getOriginCode());
                Airport dest = airportMap.get(s.getDestCode());
                int deadlineMin = (origin != null && dest != null)
                        ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent()) : 1440;
                int deadline = s.getRequestMinute() + deadlineMin;

                if (arrival > deadline) {
                    // Peso proporcional al retraso
                    weight += 1.0 + (arrival - deadline) / 60.0; // +1 por cada hora de retraso
                }
            }

            // + Peso si la ruta usa vuelos muy cargados (>80% capacidad)
            if (routeIdx != -1 && cands != null && routeIdx < cands.size()) {
                for (Flight f : cands.get(routeIdx).getFlights()) {
                    double loadRatio = (double) f.getAssignedLoad() / f.getMaxCapacity();
                    if (loadRatio > 0.8) {
                        weight += (loadRatio - 0.8) * 5.0;
                    }
                }
            }

            weights[i] = weight;
            sumWeights += weight;
        }

        // Selección ponderada (roulette wheel)
        double r = random.nextDouble() * sumWeights;
        double cumulative = 0.0;
        int selectedIdx = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative >= r) { selectedIdx = i; break; }
        }

        // Cambiar ruta del seleccionado
        int[] neighbor = Arrays.copyOf(current, current.length);
        List<Route> cands = candidatesMap.get(shipments.get(selectedIdx).getShipmentId());
        if (cands == null || cands.size() <= 1) return null;

        int newIdx;
        do { newIdx = random.nextInt(cands.size()); } while (newIdx == current[selectedIdx]);
        neighbor[selectedIdx] = newIdx;
        return neighbor;
    }

    /**
     * Aplica la mejor solución encontrada por el SA a los envíos.
     *
     * Para cada envío, asigna la ruta seleccionada y llama a
     * shipment.setResult() con los parámetros de llegada y plazo.
     *
     * También actualiza la carga real de vuelos y aeropuertos.
     *
     * @param shipments     Lista de envíos
     * @param candidatesMap Mapa de rutas candidatas
     * @param solution      Indices de rutas seleccionadas (la mejor solución del SA)
     */
    private void applySolution(List<Shipment> shipments,
                               Map<String, List<Route>> candidatesMap,
                               int[] solution) {
        // Resetear cargas de vuelos para recalcular desde cero
        for (Flight f : flights) f.resetLoad();

        int onTimeCount = 0;
        int lateCount   = 0;
        int noRouteCount = 0;

        for (int i = 0; i < shipments.size(); i++) {
            Shipment s       = shipments.get(i);
            int routeIdx     = solution[i];

            if (routeIdx == -1) {
                noRouteCount++;
                System.out.printf("  [SIN RUTA] Envio %s (%s→%s) no tiene ruta posible%n",
                        s.getShipmentId(), s.getOriginCode(), s.getDestCode());
                continue;
            }

            List<Route> candidates = candidatesMap.get(s.getShipmentId());
            if (candidates == null || routeIdx >= candidates.size()) {
                noRouteCount++;
                continue;
            }

            Route route = candidates.get(routeIdx);

            // Asignar carga en los vuelos de esta ruta
            for (Flight f : route.getFlights()) {
                f.assignLoad(s.getSuitcaseCount());
            }

            // Calcular plazo según continentes
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest   = airportMap.get(s.getDestCode());
            int deadlineMinutes = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                    : 1440;

            int arrivalMinute = route.calculateArrivalMinute();
            s.setResult(route, arrivalMinute, deadlineMinutes);

            if (s.isOnTime()) onTimeCount++;
            else              lateCount++;
        }

        System.out.printf("%n[SA Planner] Resultados finales:%n");
        System.out.printf("  A tiempo    : %d envios%n", onTimeCount);
        System.out.printf("  Con retraso : %d envios%n", lateCount);
        System.out.printf("  Sin ruta    : %d envios%n", noRouteCount);
        System.out.printf("  Total       : %d envios%n", shipments.size());
    }
}