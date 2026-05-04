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
 *    - Mismo continente: 24 horas (1440 min) desde solicitud
 *    - Distinto continente: 48 horas (2880 min) desde solicitud
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
    private static final double INITIAL_TEMPERATURE   = 500.0;    // Se recalibra dinamicamente antes de correr SA
    private static final double COOLING_RATE          = 0.950;    // Enfriamiento moderado para no vagar demasiado
    private static final double MIN_TEMPERATURE       = 1.0;
    private static final int    ITERATIONS_PER_TEMP   = 60;
    private static final int    NUM_RESTART_ATTEMPTS  = 4;
    private static final int    TEMPERATURE_SAMPLES   = 60;
    private static final double TARGET_INITIAL_ACCEPTANCE = 0.80;
    private static final int    STAGNATION_RESTART_LEVELS = 8;
    private static final double RESTART_COST_FACTOR   = 1.30;
    private static final int    MAX_ITERATIONS_PER_ATTEMPT = 18000;
    private static final double MAX_CALIBRATED_TEMPERATURE = 5000.0;
 
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
     * VERSIÓN MEJORADA CON MULTI-START SA:
     * - Intenta el SA múltiples veces con diferentes soluciones iniciales
     * - Busca escapar del óptimo local inicial
     * - Usa perturbaciones más agresivas
     *
     * @param shipments Lista de todos los envíos a planificar
     * @return Lista de envíos con sus rutas asignadas (modificados in-place)
     */
    public List<Shipment> plan(List<Shipment> shipments) {
        System.out.println("\n[SA Planner] Iniciando planificacion con Simulated Annealing (MULTI-START)...");
        System.out.printf("[SA Planner] Envios a planificar: %d | Vuelos disponibles: %d%n",
                shipments.size(), flights.size());

        // ── Paso 1: Generar rutas candidatas para cada envío ─────────────────
        System.out.println("[SA Planner] Generando rutas candidatas (BFS)...");
        Map<String, List<Route>> candidatesMap = new HashMap<>();
        int noRoutesCount = 0;
        int noOnTimeCandidateCount = 0;

        for (Shipment s : shipments) {
            List<Route> candidates = routeFinder.findCandidateRoutes(
                    s.getOriginCode(), s.getDestCode(),
                    s.getRequestMinute() + Route.TRANSIT_TIME_MINUTES,
                    s.getSuitcaseCount(),
                    s.getShipmentId()
            );
            candidatesMap.put(shipmentKey(s), candidates);

            if (candidates.isEmpty()) {
                noRoutesCount++;
            } else if (!hasOnTimeCandidate(s, candidates)) {
                noOnTimeCandidateCount++;
                logBestLateCandidate(s, candidates);
            }
        }

        if (noRoutesCount > 0) {
            System.out.printf("[SA Planner] AVISO: %d envios sin rutas candidatas.%n", noRoutesCount);
        }
        if (noOnTimeCandidateCount > 0) {
            System.out.printf("[SA Planner] AVISO: %d envios no tienen ninguna ruta candidata a tiempo.%n",
                    noOnTimeCandidateCount);
        }

        // ── MULTI-START: Intentar el SA varias veces ─────────────────────────
        int[] bestOverallSolution = null;
        double bestOverallCost = Double.MAX_VALUE;

        for (int attempt = 1; attempt <= NUM_RESTART_ATTEMPTS; attempt++) {
            System.out.printf("%n[SA Planner] ========== INTENTO %d/%d ==========\n", attempt, NUM_RESTART_ATTEMPTS);
            
            int[] attemptSolution = runSAIteration(shipments, candidatesMap, attempt);
            double attemptCost = evaluateSolutionWithCache(shipments, candidatesMap, attemptSolution);
            
            System.out.printf("[SA Planner] Intento %d: Costo final = %.2f%n", attempt, attemptCost);
            
            if (attemptCost < bestOverallCost) {
                bestOverallCost = attemptCost;
                bestOverallSolution = attemptSolution;
                System.out.printf("[SA Planner] *** NUEVO MEJOR: %.2f%n", bestOverallCost);
            }

            if (bestOverallCost <= 0.0) {
                System.out.println("[SA Planner] Solucion optima encontrada (costo 0). Se omiten intentos restantes.");
                break;
            }
        }

        System.out.printf("%n[SA Planner] Mejor solución encontrada en los %d intentos: %.2f%n", 
                NUM_RESTART_ATTEMPTS, bestOverallCost);

        // ── Aplicar la mejor solución encontrada ──────────────────────────
        applySolution(shipments, candidatesMap, bestOverallSolution);

        return shipments;
    }

    /**
     * Ejecuta una iteración del Simulated Annealing.
     * @param attempt número del intento (para diversificación)
     */
    private int[] runSAIteration(List<Shipment> shipments, 
                                  Map<String, List<Route>> candidatesMap,
                                  int attempt) {
        // Generar solución inicial (distinta según el intento)
        int[] currentSolution = initializeSolutionSmart(shipments, candidatesMap, attempt);
        double currentCost = evaluateSolutionWithCache(shipments, candidatesMap, currentSolution);

        int[] bestSolution = Arrays.copyOf(currentSolution, currentSolution.length);
        double bestCost = currentCost;

        System.out.printf("[SA Planner-Att%d] Costo inicial: %.2f%n", attempt, currentCost);
        if (currentCost <= 0.0) {
            System.out.printf("[SA-Att%d] Costo 0 desde la inicializacion; no se requiere annealing.%n", attempt);
            return bestSolution;
        }

        double temperature = calibrateInitialTemperature(shipments, candidatesMap, currentSolution, currentCost);
        System.out.printf("[SA Planner-Att%d] Temperatura inicial calibrada: %.2f%n", attempt, temperature);

        // Ciclo de Simulated Annealing
        int totalIter = 0, improvements = 0, accepted = 0;
        int levelsWithoutImprovement = 0;

        while (temperature > MIN_TEMPERATURE && totalIter < MAX_ITERATIONS_PER_ATTEMPT) {
            boolean improvedThisLevel = false;
            for (int iter = 0; iter < ITERATIONS_PER_TEMP; iter++) {
                totalIter++;

                int[] neighbor = generateNeighbor(currentSolution, shipments, candidatesMap);
                if (neighbor == null) continue;

                int[] changedIndexes = getChangedIndexes(currentSolution, neighbor);
                if (changedIndexes.length == 0) continue;

                double neighborCost;
                if (changedIndexes.length == 1) {
                    double deltaCost = evaluateSolutionIncremental(
                            shipments, candidatesMap, currentSolution, neighbor, changedIndexes[0]);
                    neighborCost = currentCost + deltaCost;
                } else {
                    neighborCost = evaluateSolution(shipments, candidatesMap, neighbor);
                }

                double delta = neighborCost - currentCost;

                if (delta < 0 || random.nextDouble() < Math.exp(-delta / temperature)) {
                    if (changedIndexes.length == 1) {
                        applyMoveToCache(shipments, candidatesMap, currentSolution, neighbor, changedIndexes[0]);
                    } else {
                        evaluateSolutionWithCache(shipments, candidatesMap, neighbor);
                    }

                    currentSolution = neighbor;
                    currentCost = neighborCost;
                    accepted++;

                    if (currentCost < bestCost) {
                        bestSolution = Arrays.copyOf(currentSolution, currentSolution.length);
                        bestCost = currentCost;
                        improvements++;
                        improvedThisLevel = true;
                        System.out.printf("[SA-Att%d] MEJORA: Iter=%d | Temp=%.2f | Cost=%.0f%n",
                                attempt, totalIter, temperature, bestCost);
                        if (bestCost <= 0.0) {
                            System.out.printf("[SA-Att%d] Costo 0 alcanzado; se detiene el annealing.%n", attempt);
                            return bestSolution;
                        }
                    }
                }
            }

            temperature *= COOLING_RATE;
            levelsWithoutImprovement = improvedThisLevel ? 0 : levelsWithoutImprovement + 1;

            if (levelsWithoutImprovement >= STAGNATION_RESTART_LEVELS
                    && currentCost > bestCost * RESTART_COST_FACTOR) {
                currentSolution = Arrays.copyOf(bestSolution, bestSolution.length);
                currentCost = evaluateSolutionWithCache(shipments, candidatesMap, currentSolution);
                temperature = Math.max(temperature, INITIAL_TEMPERATURE);
                levelsWithoutImprovement = 0;
                System.out.printf("[SA-Att%d] Restart desde best | Iter=%d | Temp=%.2f | Best=%.0f%n",
                        attempt, totalIter, temperature, bestCost);
            }

            // Logging cada 100 iteraciones
            if (totalIter % 100 == 0) {
                System.out.printf("[SA-Att%d-DEBUG] Iter=%d | Temp=%.2f | Cost=%.0f | Best=%.0f | Mejoras=%d%n",
                        attempt, totalIter, temperature, currentCost, bestCost, improvements);
            }
        }

        if (bestCost > 0.0) {
            bestSolution = improveSolutionByLocalSearch(shipments, candidatesMap, bestSolution, attempt);
        }

        System.out.printf("[SA-Att%d] Completado: %d iteraciones | %d aceptadas | %d mejoras%n",
                attempt, totalIter, accepted, improvements);

        return bestSolution;
    }
    // ════════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES DEL SA
    // ════════════════════════════════════════════════════════════════════════
 
    private double calibrateInitialTemperature(List<Shipment> shipments,
                                               Map<String, List<Route>> candidatesMap,
                                               int[] solution,
                                               double solutionCost) {
        double positiveDeltaSum = 0.0;
        int positiveMoves = 0;

        for (int sample = 0; sample < TEMPERATURE_SAMPLES; sample++) {
            int[] neighbor = generateNeighbor(solution, shipments, candidatesMap);
            if (neighbor == null) continue;

            double neighborCost = evaluateSolution(shipments, candidatesMap, neighbor);
            double delta = neighborCost - solutionCost;
            if (delta > 0.0 && Double.isFinite(delta)) {
                positiveDeltaSum += delta;
                positiveMoves++;
            }
        }

        if (positiveMoves == 0) {
            return INITIAL_TEMPERATURE;
        }

        double averagePositiveDelta = positiveDeltaSum / positiveMoves;
        double calibrated = -averagePositiveDelta / Math.log(TARGET_INITIAL_ACCEPTANCE);
        return Math.max(1.0, Math.min(MAX_CALIBRATED_TEMPERATURE, calibrated));
    }

    private String shipmentKey(Shipment s) {
        return s.getOriginCode() + "#" + s.getShipmentId();
    }

    private boolean hasOnTimeCandidate(Shipment s, List<Route> candidates) {
        Airport origin = airportMap.get(s.getOriginCode());
        Airport dest = airportMap.get(s.getDestCode());
        int deadlineMinutes = (origin != null && dest != null)
                ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                : 1440;
        int deadline = s.getRequestMinute() + deadlineMinutes;

        for (Route route : candidates) {
            if (route.calculateArrivalMinute() <= deadline) {
                return true;
            }
        }
        return false;
    }

    private void logBestLateCandidate(Shipment s, List<Route> candidates) {
        Airport origin = airportMap.get(s.getOriginCode());
        Airport dest = airportMap.get(s.getDestCode());
        int deadlineMinutes = (origin != null && dest != null)
                ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                : 1440;
        int deadline = s.getRequestMinute() + deadlineMinutes;

        int bestArrival = Integer.MAX_VALUE;
        int bestFlights = 0;
        for (Route route : candidates) {
            int arrival = route.calculateArrivalMinute();
            if (arrival < bestArrival) {
                bestArrival = arrival;
                bestFlights = route.getFlights().size();
            }
        }

        System.out.printf("[SA Planner] Sin candidata a tiempo: %s %s->%s req=%d deadline=%d bestArrival=%d delay=%d vuelos=%d candidatas=%d%n",
                s.getShipmentId(), s.getOriginCode(), s.getDestCode(), s.getRequestMinute(), deadline,
                bestArrival, Math.max(0, bestArrival - deadline), bestFlights, candidates.size());
    }

    private int[] getChangedIndexes(int[] oldSolution, int[] newSolution) {
        int count = 0;
        for (int i = 0; i < oldSolution.length; i++) {
            if (oldSolution[i] != newSolution[i]) count++;
        }

        int[] indexes = new int[count];
        int pos = 0;
        for (int i = 0; i < oldSolution.length; i++) {
            if (oldSolution[i] != newSolution[i]) {
                indexes[pos++] = i;
            }
        }
        return indexes;
    }

    private int[] improveSolutionByLocalSearch(List<Shipment> shipments,
                                               Map<String, List<Route>> candidatesMap,
                                               int[] initialSolution,
                                               int attempt) {
        int[] solution = Arrays.copyOf(initialSolution, initialSolution.length);
        double cost = evaluateSolutionWithCache(shipments, candidatesMap, solution);
        int improvements = 0;

        for (int pass = 1; pass <= 3; pass++) {
            boolean improved = false;
            List<Integer> order = buildLocalSearchOrder(shipments, candidatesMap, solution);

            for (int idx : order) {
                Shipment s = shipments.get(idx);
                List<Route> candidates = candidatesMap.get(shipmentKey(s));
                if (candidates == null || candidates.size() <= 1) continue;

                int currentIdx = solution[idx];
                int bestRouteIdx = currentIdx;
                double bestDelta = 0.0;

                for (int routeIdx = 0; routeIdx < candidates.size(); routeIdx++) {
                    if (routeIdx == currentIdx) continue;
                    int[] trial = Arrays.copyOf(solution, solution.length);
                    trial[idx] = routeIdx;

                    double delta = evaluateSolutionIncremental(
                            shipments, candidatesMap, solution, trial, idx);
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestRouteIdx = routeIdx;
                    }
                }

                if (bestRouteIdx != currentIdx) {
                    int[] next = Arrays.copyOf(solution, solution.length);
                    next[idx] = bestRouteIdx;
                    applyMoveToCache(shipments, candidatesMap, solution, next, idx);
                    solution = next;
                    cost += bestDelta;
                    improved = true;
                    improvements++;
                }
            }

            if (!improved) break;
        }

        System.out.printf("[SA-Att%d] Reparacion local: %d mejoras | Cost=%.0f%n",
                attempt, improvements, cost);
        return solution;
    }

    private List<Integer> buildLocalSearchOrder(List<Shipment> shipments,
                                                Map<String, List<Route>> candidatesMap,
                                                int[] solution) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < shipments.size(); i++) {
            order.add(i);
        }

        order.sort((a, b) -> Double.compare(
                currentShipmentPenalty(shipments.get(b), candidatesMap, solution[b]),
                currentShipmentPenalty(shipments.get(a), candidatesMap, solution[a])));
        return order;
    }

    private double currentShipmentPenalty(Shipment s, Map<String, List<Route>> candidatesMap, int routeIdx) {
        List<Route> candidates = candidatesMap.get(shipmentKey(s));
        if (candidates == null || routeIdx < 0 || routeIdx >= candidates.size()) {
            return PENALTY_NO_ROUTE_NORMALIZED;
        }
        return computeShipmentCostReadOnly(s, candidates.get(routeIdx));
    }

    /**
     * Inicializa la solución seleccionando rutas candidatas para cada envío.
     * MEJORADO: Genera diferentes soluciones iniciales según el intento.
     *
     * - Intento 1: Greedy estándar (mejor ruta por envío)
     * - Intento 2: Greedy aleatorio (elige entre 3 mejores)
     * - Intento 3: Aleatorio (elige ruta al azar)
     *
     * @param shipments Lista de envíos
     * @param candidatesMap Mapa de rutas candidatas
     * @param attempt número del intento (1, 2, 3...)
     * @return Array de índices de ruta seleccionada por envío
     */
    private int[] initializeSolutionSmart(List<Shipment> shipments,
                                          Map<String, List<Route>> candidatesMap,
                                          int attempt) {
        int[] solution = new int[shipments.size()];
        Map<String, Integer> projectedFlightLoad = new HashMap<>();

        List<Integer> shipmentOrder = new ArrayList<>();
        for (int i = 0; i < shipments.size(); i++) {
            shipmentOrder.add(i);
        }
        shipmentOrder.sort(Comparator.comparingInt(i -> {
            Shipment s = shipments.get(i);
            List<Route> cands = candidatesMap.get(shipmentKey(s));
            int numRoutes = (cands != null) ? cands.size() : 0;
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest = airportMap.get(s.getDestCode());
            int deadlineMin = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent()) : 1440;
            return numRoutes * 10000 + (s.getRequestMinute() + deadlineMin);
        }));

        for (int idx : shipmentOrder) {
            Shipment s = shipments.get(idx);
            List<Route> cands = candidatesMap.get(shipmentKey(s));

            if (cands == null || cands.isEmpty()) {
                solution[idx] = -1;
                continue;
            }

            int selectedRouteIdx;
            
            if (attempt == 1) {
                selectedRouteIdx = selectBestRoute(s, cands, projectedFlightLoad, 5);
            } else if (attempt == 2) {
                selectedRouteIdx = selectBestRoute(s, cands, projectedFlightLoad, 2);
            } else if (attempt == 3) {
                selectedRouteIdx = selectBestRoute(s, cands, projectedFlightLoad, 10);
            } else {
                selectedRouteIdx = random.nextInt(cands.size());
            }

            solution[idx] = selectedRouteIdx;
            for (Flight f : cands.get(selectedRouteIdx).getFlights()) {
                int current = projectedFlightLoad.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                projectedFlightLoad.put(f.getFlightId(), current + s.getSuitcaseCount());
            }
        }

        return solution;
    }

    /**
     * Selecciona la mejor ruta para un envío, con opción de aleatoriedad.
     * @param topK si > 0, elige aleatoriamente entre las topK mejores; si 0, elige la mejor
     */
    private int selectBestRoute(Shipment s, List<Route> cands, 
                                Map<String, Integer> projectedFlightLoad, int topK) {
        double[] scores = new double[cands.size()];
        
        for (int r = 0; r < cands.size(); r++) {
            Route route = cands.get(r);
            double score = 0.0;

            for (Flight f : route.getFlights()) {
                int load = projectedFlightLoad.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                int newLoad = load + s.getSuitcaseCount();
                score += computeNormalizedFlightOverloadPenalty(newLoad, f.getMaxCapacity());
            }

            int arrival = route.calculateArrivalMinute();
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest = airportMap.get(s.getDestCode());
            int deadlineMin = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent()) : 1440;
            int deadline = s.getRequestMinute() + deadlineMin;
            int delay = Math.max(0, arrival - deadline);

            score += computeNormalizedDelayPenalty(delay, deadlineMin) * 0.5;
            scores[r] = score;
        }

        if (topK <= 0) {
            // Retornar índice de mejor score
            int best = 0;
            for (int i = 1; i < scores.length; i++) {
                if (scores[i] < scores[best]) best = i;
            }
            return best;
        } else {
            // Retornar al azar entre las topK mejores
            Integer[] indices = new Integer[cands.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            Arrays.sort(indices, Comparator.comparingDouble(i -> scores[i]));
            int k = Math.min(topK + 1, indices.length);
            return indices[random.nextInt(k)];
        }
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
 
            List<Route> candidates = candidatesMap.get(shipmentKey(s));
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
     * Evalúa SOLO el cambio de costo al modificar el envío en changedIndex.
     * REQUIERE que evaluateSolutionWithCache() se haya llamado antes.
     *
     * [BUG 1 FIX] Esta función ya NO muta el cache.
     * [BUG 3 FIX] CRÍTICO: Ahora incluye el delta de penalización de AEROPUERTOS.
     *
     * PROBLEMA ORIGINAL (BUG 1): computeShipmentDeltaCost() mutaba cachedFlightLoads y
     * cachedAirportEvents durante el cálculo. Si el movimiento era RECHAZADO, el
     * cache quedaba corrompido. FIX: calcular delta de forma read-only.
     *
     * PROBLEMA ORIGINAL (BUG 3): No se calculaba el delta de capacidad de aeropuertos.
     * Resultado: el delta era INCOMPLETO e INCORRECTO → SA nunca mejoraba.
     * FIX: calcular airportDelta simulando sobre el cache sin escribirlo.
     */
    private double evaluateSolutionIncremental(List<Shipment> shipments,
                                               Map<String, List<Route>> candidatesMap,
                                               int[] oldSolution,
                                               int[] newSolution,
                                               int changedIndex) {
        Shipment s = shipments.get(changedIndex);

        Route oldRoute = null;
        if (oldSolution[changedIndex] != -1) {
            List<Route> oldCands = candidatesMap.get(shipmentKey(s));
            if (oldCands != null && oldSolution[changedIndex] < oldCands.size()) {
                oldRoute = oldCands.get(oldSolution[changedIndex]);
            }
        }

        Route newRoute = null;
        if (newSolution[changedIndex] != -1) {
            List<Route> newCands = candidatesMap.get(shipmentKey(s));
            if (newCands != null && newSolution[changedIndex] < newCands.size()) {
                newRoute = newCands.get(newSolution[changedIndex]);
            }
        }

        // Costo de la ruta anterior (solo lectura, sin mutar el cache)
        double costOld = (oldRoute != null)
                ? computeShipmentCostReadOnly(s, oldRoute)
                : PENALTY_NO_ROUTE_NORMALIZED;

        // Costo de la ruta nueva (solo lectura, sin mutar el cache)
        double costNew = (newRoute != null)
                ? computeShipmentCostReadOnly(s, newRoute)
                : PENALTY_NO_ROUTE_NORMALIZED;

        // Delta de penalización por vuelos (simular sobre el cache sin escribirlo)
        double flightDelta = 0.0;
        int n = s.getSuitcaseCount();

        if (oldRoute != null) {
            for (Flight f : oldRoute.getFlights()) {
                int load = cachedFlightLoads.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                flightDelta -= computeNormalizedFlightOverloadPenalty(load, f.getMaxCapacity());
                flightDelta += computeNormalizedFlightOverloadPenalty(load - n, f.getMaxCapacity());
            }
        }
        if (newRoute != null) {
            for (Flight f : newRoute.getFlights()) {
                int load = cachedFlightLoads.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                flightDelta -= computeNormalizedFlightOverloadPenalty(load, f.getMaxCapacity());
                flightDelta += computeNormalizedFlightOverloadPenalty(load + n, f.getMaxCapacity());
            }
        }

        // [BUG 3 FIX] Delta de penalización por capacidad de AEROPUERTOS
        // Simular los cambios sobre el cache sin escribirlo
        double airportDelta = computeAirportDeltaReadOnly(s, oldRoute, newRoute, -1, +1);

        return (costNew - costOld) + flightDelta + airportDelta;
    }

    /**
     * [BUG 3 FIX - NUEVO MÉTODO] Calcula el delta de penalización de AEROPUERTOS
     * SIN mutar el cache. Solo simula los cambios de forma read-only.
     *
     * @param s Envío que se está moviendo
     * @param oldRoute Ruta vieja (null si es nueva asignación)
     * @param newRoute Ruta nueva (null si se está quitando)
     * @param oldDelta Delta para oldRoute (-1 para quitar)
     * @param newDelta Delta para newRoute (+1 para agregar)
     * @return Delta total de penalización de aeropuertos (puede ser positivo o negativo)
     */
    private double computeAirportDeltaReadOnly(Shipment s, Route oldRoute, Route newRoute,
                                               int oldDelta, int newDelta) {
        Map<String, Map<Integer, Integer>> simulatedEvents = new HashMap<>();

        // Copiar los eventos actuales del cache para simulación
        for (Map.Entry<String, Map<Integer, Integer>> entry : cachedAirportEvents.entrySet()) {
            simulatedEvents.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        int suitcaseCount = s.getSuitcaseCount();

        // Simular cambios de ruta vieja (quitar envío)
        if (oldRoute != null) {
            updateAirportEventsSimulated(oldRoute, suitcaseCount, oldDelta, simulatedEvents);
        }

        // Simular cambios de ruta nueva (agregar envío)
        if (newRoute != null) {
            updateAirportEventsSimulated(newRoute, suitcaseCount, newDelta, simulatedEvents);
        }

        // Calcular penalizaciones ANTES y DESPUÉS de los cambios
        double penaltyBefore = computeAirportPenaltiesFromSimulatedEvents(cachedAirportEvents);
        double penaltyAfter = computeAirportPenaltiesFromSimulatedEvents(simulatedEvents);

        return (penaltyAfter - penaltyBefore) * COST_SCALE_FACTOR;
    }

    /**
     * Actualiza el mapa simulado de eventos de aeropuertos (sin mutar el cache real).
     */
    private void updateAirportEventsSimulated(Route route, int suitcaseCount, int delta,
                                              Map<String, Map<Integer, Integer>> simulatedEvents) {
        List<Flight> flightsInRoute = route.getFlights();

        for (int leg = 0; leg < flightsInRoute.size(); leg++) {
            Flight f = flightsInRoute.get(leg);
            String apCode = f.getDestCode();

            Map<Integer, Integer> events = simulatedEvents
                    .computeIfAbsent(apCode, k -> new HashMap<>());

            int arrivalMin = f.getArrivalMinute();
            events.merge(arrivalMin, suitcaseCount * delta, Integer::sum);

            // Evento de salida
            int departureMin;
            if (apCode.equals(route.getFinalDestCode())) {
                departureMin = arrivalMin + 10;
            } else if (leg + 1 < flightsInRoute.size()) {
                departureMin = flightsInRoute.get(leg + 1).getDepartureMinute();
            } else {
                continue;
            }

            events.merge(departureMin, -suitcaseCount * delta, Integer::sum);
        }
    }

    /**
     * Calcula penalizaciones de aeropuertos desde un mapa de eventos simulado.
     */
    private double computeAirportPenaltiesFromSimulatedEvents(Map<String, Map<Integer, Integer>> events) {
        double totalPenalty = 0.0;

        for (Map.Entry<String, Map<Integer, Integer>> entry : events.entrySet()) {
            String apCode = entry.getKey();
            Airport ap = airportMap.get(apCode);
            if (ap == null) continue;

            Map<Integer, Integer> eventMap = entry.getValue();
            if (eventMap.isEmpty()) continue;

            List<Integer> sortedMinutes = new ArrayList<>(eventMap.keySet());
            Collections.sort(sortedMinutes);

            int currentLoad = ap.getCurrentLoad();
            for (int minute : sortedMinutes) {
                currentLoad += eventMap.get(minute);
                if (currentLoad > ap.getMaxCapacity()) {
                    totalPenalty += computeNormalizedAirportOverloadPenalty(currentLoad, ap.getMaxCapacity());
                }
            }
        }

        return totalPenalty * COST_SCALE_FACTOR;
    }

    /**
     * [BUG 1 FIX - NUEVO MÉTODO] Calcula el costo de un envío con una ruta dada
     * SIN tocar ningún cache. Solo usa los valores actuales del cache para leer.
     */
    private double computeShipmentCostReadOnly(Shipment s, Route route) {
        int arrivalMinute = route.calculateArrivalMinute();
        Airport origin = airportMap.get(s.getOriginCode());
        Airport dest   = airportMap.get(s.getDestCode());
        int deadlineMinutes = (origin != null && dest != null)
                ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                : 1440;
        int deadline    = s.getRequestMinute() + deadlineMinutes;
        int delayMinutes = Math.max(0, arrivalMinute - deadline);
        return computeNormalizedDelayPenalty(delayMinutes, deadlineMinutes);
    }
 
    /**
     * [BUG 1 FIX - NUEVO MÉTODO] Aplica el cambio de ruta AL CACHE.
     * Solo llamar cuando el movimiento es ACEPTADO en el loop SA.
     */
    private void applyMoveToCache(List<Shipment> shipments,
                                   Map<String, List<Route>> candidatesMap,
                                   int[] oldSolution, int[] newSolution,
                                   int changedIndex) {
        Shipment s = shipments.get(changedIndex);
        Set<String> dummy = new HashSet<>();
 
        // Quitar ruta anterior del cache
        if (oldSolution[changedIndex] != -1) {
            List<Route> oldCands = candidatesMap.get(shipmentKey(s));
            if (oldCands != null && oldSolution[changedIndex] < oldCands.size()) {
                computeShipmentDeltaCost(s, oldCands.get(oldSolution[changedIndex]), -1, dummy);
            }
        }
 
        // Agregar ruta nueva al cache
        if (newSolution[changedIndex] != -1) {
            List<Route> newCands = candidatesMap.get(shipmentKey(s));
            if (newCands != null && newSolution[changedIndex] < newCands.size()) {
                computeShipmentDeltaCost(s, newCands.get(newSolution[changedIndex]), +1, dummy);
            }
        }
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
     * [BUG 2 FIX] Penalización por retraso en escala REAL (minutos).
     *
     * PROBLEMA ORIGINAL: la fórmula normalizaba el retraso a 0-100 sin importar
     * cuántos minutos eran. Con 986 envíos todos retrasados, el delta entre
     * cualquier par de rutas era ≈ 0.0 → el SA no tenía gradiente y no mejoraba.
     *
     * FIX: penalización directamente proporcional a los minutos de retraso.
     * Un envío que llega 600 min tarde → 6000 pts. Uno a tiempo → 0 pts.
     * El SA ahora distingue claramente entre rutas mejores y peores.
     */
    private double computeNormalizedDelayPenalty(int delayMinutes, int deadlineMinutes) {
        if (delayMinutes <= 0) return 0.0;
        return 1_000_000.0 + delayMinutes * 1_000.0;
    }
 
    /**
     * [BUG 2 FIX] Penalización por sobrecarga de vuelo en escala REAL (maletas en exceso).
     */
    private double computeNormalizedFlightOverloadPenalty(int currentLoad, int maxCapacity) {
        if (currentLoad <= maxCapacity) return 0.0;
        return (currentLoad - maxCapacity) * 100_000.0;
    }
 
    /**
     * [BUG 2 FIX] Penalización por sobrecarga de aeropuerto en escala REAL.
     */
    private double computeNormalizedAirportOverloadPenalty(int currentLoad, int maxCapacity) {
        if (currentLoad <= maxCapacity) return 0.0;
        return (currentLoad - maxCapacity) * 100_000.0;
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
 
            List<Route> candidates = candidatesMap.get(shipmentKey(s));
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
 
        double roll = random.nextDouble();
        int changes;
        if (roll < 0.55) {
            changes = 1;
        } else if (roll < 0.85) {
            changes = 2 + random.nextInt(3);
        } else {
            changes = 5 + random.nextInt(4);
        }

        int applied = 0;
        int attempts = 0;
        Set<Integer> touched = new HashSet<>();

        while (applied < changes && attempts < changes * 40) {
            attempts++;
            int i = selectShipmentForNeighbor(current, shipments, candidatesMap, touched);
            if (i < 0) break;
            if (!touched.add(i)) continue;

            Shipment s = shipments.get(i);
            List<Route> candidates = candidatesMap.get(shipmentKey(s));
 
            if (candidates != null && candidates.size() > 1) {
                int newRouteIdx = selectNeighborRoute(s, candidates, current[i]);
 
                neighbor[i] = newRouteIdx;
                applied++;
            }
        }
 
        return applied > 0 ? neighbor : null;
    }

    private int selectShipmentForNeighbor(int[] current, List<Shipment> shipments,
                                          Map<String, List<Route>> candidatesMap,
                                          Set<Integer> excluded) {
        double totalWeight = 0.0;
        double[] weights = new double[shipments.size()];

        for (int i = 0; i < shipments.size(); i++) {
            if (excluded.contains(i)) continue;

            Shipment s = shipments.get(i);
            List<Route> cands = candidatesMap.get(shipmentKey(s));
            if (cands == null || cands.size() <= 1) continue;

            double weight = 1.0;
            int routeIdx = current[i];
            if (routeIdx >= 0 && routeIdx < cands.size()) {
                Route route = cands.get(routeIdx);
                double delayCost = computeShipmentCostReadOnly(s, route);
                weight += delayCost / 250.0;
                for (Flight f : route.getFlights()) {
                    int load = cachedFlightLoads.getOrDefault(f.getFlightId(), f.getAssignedLoad());
                    int overload = Math.max(0, load - f.getMaxCapacity());
                    weight += overload * 2.0;
                }
            } else {
                weight += 20.0;
            }

            weights[i] = weight;
            totalWeight += weight;
        }

        if (totalWeight <= 0.0) return -1;

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative >= r) return i;
        }

        return -1;
    }

    private int selectNeighborRoute(Shipment s, List<Route> candidates, int currentIdx) {
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (i != currentIdx) ranked.add(i);
        }

        ranked.sort(Comparator.comparingDouble(i -> scoreRouteForCurrentCache(s, candidates.get(i))));
        int topK = Math.min(5, ranked.size());
        if (topK == 0) return currentIdx;
        return ranked.get(random.nextInt(topK));
    }

    private double scoreRouteForCurrentCache(Shipment s, Route route) {
        double score = computeShipmentCostReadOnly(s, route);
        for (Flight f : route.getFlights()) {
            int load = cachedFlightLoads.getOrDefault(f.getFlightId(), f.getAssignedLoad());
            score += computeNormalizedFlightOverloadPenalty(load + s.getSuitcaseCount(), f.getMaxCapacity());
        }
        return score;
    }
 
    // NUEVO MÉTODO (Muy lento, dejado por mientras)
    private int[] generateSmartNeighbor(int[] current, List<Shipment> shipments,
                                        Map<String, List<Route>> candidatesMap) {
        // Calcular peso por envío: mayor peso = más probable de ser seleccionado
        double[] weights = new double[shipments.size()];
        double sumWeights = 0.0;
 
        for (int i = 0; i < shipments.size(); i++) {
            Shipment s = shipments.get(i);
            List<Route> cands = candidatesMap.get(shipmentKey(s));
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
        List<Route> cands = candidatesMap.get(shipmentKey(shipments.get(selectedIdx)));
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
        int onTimeSuitcases = 0;
        int lateSuitcases = 0;
        int noRouteSuitcases = 0;
 
        for (int i = 0; i < shipments.size(); i++) {
            Shipment s       = shipments.get(i);
            int routeIdx     = solution[i];
 
            if (routeIdx == -1) {
                noRouteCount++;
                noRouteSuitcases += s.getSuitcaseCount();
                System.out.printf("  [SIN RUTA] Envio %s (%s→%s) no tiene ruta posible%n",
                        s.getShipmentId(), s.getOriginCode(), s.getDestCode());
                continue;
            }
 
            List<Route> candidates = candidatesMap.get(shipmentKey(s));
            if (candidates == null || routeIdx >= candidates.size()) {
                noRouteCount++;
                noRouteSuitcases += s.getSuitcaseCount();
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
 
            if (s.isOnTime()) {
                onTimeCount++;
                onTimeSuitcases += s.getSuitcaseCount();
            } else {
                lateCount++;
                lateSuitcases += s.getSuitcaseCount();
            }
        }
 
        System.out.printf("%n[SA Planner] Resultados finales:%n");
        System.out.printf("  A tiempo    : %d envios | %d maletas%n", onTimeCount, onTimeSuitcases);
        System.out.printf("  Con retraso : %d envios | %d maletas%n", lateCount, lateSuitcases);
        System.out.printf("  Sin ruta    : %d envios | %d maletas%n", noRouteCount, noRouteSuitcases);
        System.out.printf("  Total       : %d envios | %d maletas%n",
                shipments.size(), onTimeSuitcases + lateSuitcases + noRouteSuitcases);
    }
}
