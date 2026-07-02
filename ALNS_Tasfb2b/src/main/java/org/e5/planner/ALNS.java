package org.e5.planner;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.util.AirportCapacityTimeline;
import org.e5.util.ALNSRouteFinder;

import java.util.*;

/**
 * Planificador ALNS con fraccionamiento dinámico de maletas.
 *
 * Cuando un envío tiene más maletas de las que caben en cualquier ruta
 * disponible, lo fracciona en sub-envíos (PartialRoute) que viajan
 * en vuelos distintos, maximizando la ocupación de cada vuelo.
 *
 * Los sub-envíos llevan trazabilidad completa (parentShipmentId,
 * splitPartIndex, splitPartCount) para que RouteReportGenerator
 * pueda agruparlos por envío original.
 */
public class ALNS {

    // ── Parámetros ────────────────────────────────────────────────────────────
    private final int    maxIteraciones;
    private final int    segmento;
    private final int    nDestruir;
    private final double temperaturaInicial;
    private final double alpha;
    private final int    maxEscalas;
    private final double rewardMejora;
    private final double rewardAcepta;
    private final double rewardNoAcepta;
    private final double decayPesos;
    private final boolean fastMode;

    // ── Pesos función objetivo ────────────────────────────────────────────────
    private static final double PESO_SIN_RUTA    = 100_000.0;
    private static final double PESO_ENVIO_TARDE = 25_000.0;
    private static final double PESO_FUERA_PLAZO = 50.0;
    private static final double PESO_ESPERA_ORIGEN_PREVENTIVA = 2.0;

    // ── Índices operadores destrucción ────────────────────────────────────────
    private static final int D_ALEATORIO      = 0;
    private static final int D_PEOR_FITNESS   = 1;
    private static final int D_MISMA_RUTA     = 2;
    private static final int NUM_DESTRUCTORES = 3;

    // ── Índices operadores reparación ─────────────────────────────────────────
    private static final int R_GREEDY_URGENCIA = 0;
    private static final int R_GREEDY_COSTO    = 1;
    private static final int R_ALEATORIO       = 2;
    private static final int R_REGRET_K        = 3;
    private static final int NUM_REPARADORES   = 4;
    private static final int REGRET_K          = 2;
    private static final int REGRET_EVAL_LIMIT = 80;
    private static final int REGRET_FAST_EVAL_LIMIT = 25;

    // ── Pesos adaptativos ────────────────────────────────────────────────────
    private double[] pesoDestructor;
    private double[] pesoReparador;
    private double[] scoreDestructor;
    private double[] scoreReparador;
    private int[]    usoDestructor;
    private int[]    usoReparador;

    // ── Métricas ─────────────────────────────────────────────────────────────
    private int    iteracionesEjecutadas;
    private double fitnessMejorInicial;
    private double fitnessMejorFinal;
    private int    mejorasGlobal;
    private int    aceptadasSA;

    private final Random rnd = new Random();
    private AirportCapacityTimeline airportCapacityTimeline;
    private Map<String, Double> prioridadOrigen = Collections.emptyMap();
    private long timeBudgetNanos = 0L;

    // ── Constructores ─────────────────────────────────────────────────────────

    public ALNS() {
        this(600, 30, -1, 500.0, 0.997, 4, 9.0, 3.0, 0.0, 0.8);
    }

    public ALNS(int maxIteraciones, int segmento, int nDestruir,
                double temperaturaInicial, double alpha, int maxEscalas,
                double rewardMejora, double rewardAcepta,
                double rewardNoAcepta, double decayPesos) {
        this(maxIteraciones, segmento, nDestruir, temperaturaInicial, alpha, maxEscalas,
                rewardMejora, rewardAcepta, rewardNoAcepta, decayPesos, false);
    }

    public ALNS(int maxIteraciones, int segmento, int nDestruir,
                double temperaturaInicial, double alpha, int maxEscalas,
                double rewardMejora, double rewardAcepta,
                double rewardNoAcepta, double decayPesos, boolean fastMode) {
        this.maxIteraciones     = maxIteraciones;
        this.segmento           = segmento;
        this.nDestruir          = nDestruir;
        this.temperaturaInicial = temperaturaInicial;
        this.alpha              = alpha;
        this.maxEscalas         = maxEscalas;
        this.rewardMejora       = rewardMejora;
        this.rewardAcepta       = rewardAcepta;
        this.rewardNoAcepta     = rewardNoAcepta;
        this.decayPesos         = decayPesos;
        this.fastMode           = fastMode;
    }

    public ALNS withOriginPriorities(Map<String, Double> prioridadOrigen) {
        this.prioridadOrigen = prioridadOrigen == null || prioridadOrigen.isEmpty()
                ? Collections.emptyMap()
                : new HashMap<>(prioridadOrigen);
        return this;
    }

    public ALNS withTimeBudgetMillis(long timeBudgetMillis) {
        this.timeBudgetNanos = timeBudgetMillis > 0
                ? timeBudgetMillis * 1_000_000L
                : 0L;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PUNTO DE ENTRADA
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Planifica rutas para todos los envíos.
     * Los envíos que no caben en una sola ruta son fraccionados dinámicamente.
     * La lista shipments puede crecer si hay fraccionamientos.
     *
     * @param shipments  lista de envíos (se modifica in-place si hay fraccionamiento)
     * @param flights    vuelos disponibles
     * @param airportMap mapa código ICAO → Airport
     * @return mapa shipmentId → Route con la mejor asignación
     */
    public Map<String, Route> ejecutar(List<Shipment> shipments,
                                        List<Flight> flights,
                                        Map<String, Airport> airportMap) {
        return ejecutar(shipments, flights, airportMap, false);
    }

    /**
     * Planifica nuevos envíos respetando las cargas que ya existen en vuelos y
     * aeropuertos. Se usa en simulación incremental para no perder reservas de
     * lotes anteriores.
     */
    public Map<String, Route> ejecutarIncremental(List<Shipment> shipments,
                                                   List<Flight> flights,
                                                   Map<String, Airport> airportMap) {
        return ejecutar(shipments, flights, airportMap, true, Collections.emptyList());
    }

    public Map<String, Route> ejecutarIncremental(List<Shipment> shipments,
                                                   List<Flight> flights,
                                                   Map<String, Airport> airportMap,
                                                   Collection<Shipment> baseShipments) {
        return ejecutar(shipments, flights, airportMap, true, baseShipments);
    }

    private Map<String, Route> ejecutar(List<Shipment> shipments,
                                        List<Flight> flights,
                                        Map<String, Airport> airportMap,
                                        boolean preservarCapacidadExistente) {
        return ejecutar(shipments, flights, airportMap, preservarCapacidadExistente, Collections.emptyList());
    }

    private Map<String, Route> ejecutar(List<Shipment> shipments,
                                        List<Flight> flights,
                                        Map<String, Airport> airportMap,
                                        boolean preservarCapacidadExistente,
                                        Collection<Shipment> baseShipments) {
        if (shipments.isEmpty()) return new HashMap<>();
        long deadlineNanos = timeBudgetNanos > 0
                ? System.nanoTime() + timeBudgetNanos
                : Long.MAX_VALUE;

        Map<Flight, Integer> cargaBaseVuelos =
                snapshotCargasVuelos(flights, preservarCapacidadExistente);
        Map<String, Integer> cargaBaseAeropuertos =
                snapshotCargasAeropuertos(airportMap, preservarCapacidadExistente);
        airportCapacityTimeline = new AirportCapacityTimeline(airportMap);
        if (preservarCapacidadExistente && baseShipments != null && !baseShipments.isEmpty()) {
            airportCapacityTimeline.seedExistingAssignments(baseShipments);
        } else if (preservarCapacidadExistente) {
            airportCapacityTimeline.seedAggregateLoads();
        }
        reconstruirCapacidad(Collections.emptyMap(), Collections.emptyList(),
                flights, airportMap, cargaBaseVuelos, cargaBaseAeropuertos);
        for (Shipment s : shipments) s.resetPlanningState();

        ALNSRouteFinder finder = new ALNSRouteFinder(airportMap, maxEscalas, airportCapacityTimeline);

        iteracionesEjecutadas = 0;
        mejorasGlobal         = 0;
        aceptadasSA           = 0;
        inicializarPesos();

        int n = nDestruir > 0 ? nDestruir : Math.max(3, shipments.size() / 5);
        if (fastMode) {
            n = Math.max(3, Math.min(n, Math.max(3, shipments.size() / 12)));
        }

        // 1. Solución greedy inicial con fraccionamiento
        List<Shipment> allShipments = new ArrayList<>(shipments);
        Map<String, Route> solActual = construirSolucionGreedy(
                allShipments, flights, finder, airportMap);

        // Propagar sub-envíos generados al fraccionamiento a la lista original
        Map<String, Route> solMejor = new HashMap<>(solActual);
        List<Shipment> shipmentsMejor = new ArrayList<>(allShipments);
        Map<String, Double> contribActual = calcularContribuciones(solActual, allShipments, finder);
        double fitActual = sumarContribuciones(contribActual);
        double fitMejor  = fitActual;
        fitnessMejorInicial = fitMejor;

        System.out.printf("[ALNS] Iniciando | Envíos: %d | Vuelos: %d | Fitness inicial: %.0f%n",
                allShipments.size(), flights.size(), fitMejor);

        double T = temperaturaInicial;
        int iteracionesSinMejora = 0;
        int limiteIteraciones = fastMode
                ? Math.min(maxIteraciones, Math.max(10, maxIteraciones / 3))
                : maxIteraciones;
        int minIteracionesAntesDeCorte = Math.min(limiteIteraciones,
                Math.max(fastMode ? 8 : 30, limiteIteraciones / 5));
        int pacienciaSinMejora = fastMode
                ? Math.max(8, limiteIteraciones / 5)
                : Math.max(40, limiteIteraciones / 4);
        if (fastMode) {
            System.out.printf("[ALNS] Modo rapido activo | Iters: %d/%d | nDestruir: %d%n",
                    limiteIteraciones, maxIteraciones, n);
        }

        // 2. Bucle principal ALNS
        for (int iter = 0; iter < limiteIteraciones; iter++) {
            if (tiempoAgotado(deadlineNanos)) {
                System.out.printf("[ALNS] Corte por presupuesto de tiempo en iter %d%n", iter);
                break;
            }
            iteracionesEjecutadas++;

            int idxD = seleccionarPorRuleta(pesoDestructor);
            int idxR = seleccionarPorRuleta(pesoReparador);

            List<Shipment> candidateShipments = new ArrayList<>(allShipments);
            Map<String, Route> solDestruida = new HashMap<>(solActual);
            List<Shipment> destruidos = destruir(
                    idxD, solDestruida, candidateShipments, airportMap, n);
            agregarSinRutaAReparacion(solDestruida, candidateShipments, destruidos, n);

            reparar(idxR, solDestruida, destruidos, candidateShipments, flights, finder, airportMap);

            Map<String, Double> contribNuevo = calcularContribuciones(solDestruida, candidateShipments, finder);
            double fitNuevo = sumarContribuciones(contribNuevo);
            double delta    = fitNuevo - fitActual;

            double reward;
            if (delta < 0 || rnd.nextDouble() < Math.exp(-delta / Math.max(T, 0.001))) {
                allShipments = candidateShipments;
                solActual = solDestruida;
                fitActual = fitNuevo;
                contribActual = contribNuevo;
                aceptadasSA++;

                if (fitActual < fitMejor) {
                    solMejor = new HashMap<>(solActual);
                    shipmentsMejor = new ArrayList<>(allShipments);
                    fitMejor = fitActual;
                    reward   = rewardMejora;
                    mejorasGlobal++;
                    iteracionesSinMejora = 0;
                    System.out.printf("[ALNS] Iter %d | Mejor: %.0f | D%d R%d%n",
                            iter, fitMejor, idxD, idxR);
                } else {
                    reward = rewardAcepta;
                    iteracionesSinMejora++;
                }
            } else {
                reward = rewardNoAcepta;
                iteracionesSinMejora++;
                reconstruirCapacidad(solActual, allShipments, flights, airportMap,
                        cargaBaseVuelos, cargaBaseAeropuertos);
            }

            scoreDestructor[idxD] += reward;
            scoreReparador[idxR]  += reward;
            usoDestructor[idxD]++;
            usoReparador[idxR]++;

            if ((iter + 1) % segmento == 0) actualizarPesos();
            T *= alpha;

            if ((iter + 1) >= minIteracionesAntesDeCorte
                    && iteracionesSinMejora >= pacienciaSinMejora) {
                System.out.printf("[ALNS] Early stopping en iter %d | %d iteraciones sin mejora%n",
                        iter, iteracionesSinMejora);
                break;
            }
        }

        fitnessMejorFinal = fitMejor;
        reconstruirCapacidad(solMejor, shipmentsMejor, flights, airportMap,
                cargaBaseVuelos, cargaBaseAeropuertos);
        registrarResultados(solMejor, shipmentsMejor, finder);
        sincronizarShipments(shipments, shipmentsMejor);
        imprimirResumen();
        return solMejor;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  REPLANIFICACIÓN ANTE CANCELACIONES
    // ════════════════════════════════════════════════════════════════════════

    public Map<String, Route> replanificar(List<Shipment> afectados,
                                            String flightIdCancelado,
                                            List<Flight> flights,
                                            Map<String, Airport> airportMap) {
        System.out.printf("[ALNS] Replanificando %d envíos por cancelación de %s%n",
                afectados.size(), flightIdCancelado);
        List<Flight> disponibles = new ArrayList<>();
        
        for (Flight f : flights)
            if (!f.getFlightId().equals(flightIdCancelado)) disponibles.add(f);

        for (Flight f : disponibles) f.resetLoad();

        ALNS rapido = new ALNS(
                Math.max(80, maxIteraciones / 5), Math.max(10, segmento / 2),
                afectados.size(), temperaturaInicial * 0.3, 0.99, maxEscalas,
                rewardMejora, rewardAcepta, rewardNoAcepta, decayPesos);
        return rapido.ejecutarIncremental(afectados, disponibles, airportMap);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SOLUCIÓN GREEDY CON FRACCIONAMIENTO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Construye la solución inicial.
     * Para cada envío intenta primero una ruta completa.
     * Si no hay ruta que admita todas las maletas juntas, fracciona
     * el envío en sub-envíos y los agrega a allShipments.
     */
    private Map<String, Route> construirSolucionGreedy(List<Shipment> allShipments,
                                                        List<Flight> flights,
                                                        ALNSRouteFinder finder,
                                                        Map<String, Airport> airportMap) {
        Map<String, Route> solucion = new HashMap<>();

        // Trabajar sobre una copia para poder iterar mientras se agregan partes
        List<Shipment> aRutar = new ArrayList<>(allShipments);
        aRutar.sort(comparadorPlanificacion(finder));

        for (Shipment s : aRutar) {
            if (!allShipments.contains(s)) continue;

            // 1. Intentar ruta completa
            Route rutaCompleta = encontrarMejorRuta(s, flights, finder);
            if (rutaCompleta != null) {
                solucion.put(s.getShipmentId(), rutaCompleta);
                reservarCapacidad(rutaCompleta, s.getSuitcaseCount(), airportMap);
                continue;
            }

            // 2. Fraccionar dinámicamente
            fraccionarYRegistrar(s, solucion, allShipments, flights, finder, airportMap);
            continue;
            // Crear sub-envíos con trazabilidad y agregarlos
            // Registrar la ruta de cada parte en la solución
        }
        return solucion;
    }

    /**
     * Crea objetos Shipment con trazabilidad completa a partir de PartialRoutes.
     * Actualiza splitPartCount ahora que sabemos el total de partes.
     */
    private List<Shipment> crearSubEnvios(Shipment original,
                                           List<ALNSRouteFinder.PartialRoute> parciales) {
        int totalPartes = parciales.size();
        List<Shipment> subEnvios = new ArrayList<>();

        for (int i = 0; i < totalPartes; i++) {
            int lote   = parciales.get(i).maletas;
            String pid = original.getShipmentId() + "_p" + (i + 1);

            subEnvios.add(new Shipment(
                    pid,
                    original.getOriginCode(),
                    original.getDestCode(),
                    original.getRequestMinute(),
                    lote,
                    original.getClientId(),
                    original.getRawDate(),
                    original.getRawHour(),
                    original.getRawMinuteStr(),
                    original.getShipmentId(),   // parentShipmentId
                    i + 1,                       // splitPartIndex (1-based)
                    totalPartes,                 // splitPartCount
                    original.getSuitcaseCount()  // originalSuitcaseCount
            ));
        }
        return subEnvios;
    }

    /**
     * Propaga sub-envíos generados durante el greedy a la lista original
     * para que el bucle SA y el reporte los incluyan.
     */
    private boolean fraccionarYRegistrar(Shipment original,
                                          Map<String, Route> sol,
                                          List<Shipment> allShipments,
                                          List<Flight> flights,
                                          ALNSRouteFinder finder,
                                          Map<String, Airport> airportMap) {
        if (original.isSplitPart()) return false;

        List<ALNSRouteFinder.PartialRoute> parciales =
                finder.findFractionalRoutes(original, flights);
        if (parciales.isEmpty()) return false;

        List<Shipment> partes = crearSubEnvios(original, parciales);
        sol.remove(original.getShipmentId());
        allShipments.remove(original);

        for (int i = 0; i < partes.size(); i++) {
            Shipment parte = partes.get(i);
            Route ruta = parciales.get(i).ruta;
            if (!contieneShipmentId(allShipments, parte.getShipmentId())) {
                allShipments.add(parte);
            }
            sol.put(parte.getShipmentId(), ruta);
        }
        return true;
    }

    private boolean contieneShipmentId(List<Shipment> shipments, String shipmentId) {
        for (Shipment shipment : shipments) {
            if (shipment.getShipmentId().equals(shipmentId)) return true;
        }
        return false;
    }

    private void sincronizarShipments(List<Shipment> original, List<Shipment> actualizado) {
        for (Shipment s : actualizado) {
            if (!contieneShipmentId(original, s.getShipmentId())) original.add(s);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OPERADORES DE DESTRUCCIÓN
    // ════════════════════════════════════════════════════════════════════════

    private List<Shipment> destruir(int idxOp, Map<String, Route> sol,
                                     List<Shipment> shipments,
                                     Map<String, Airport> airportMap, int n) {
        switch (idxOp) {
            case D_ALEATORIO:    return destruirAleatorio(sol, shipments, airportMap, n);
            case D_PEOR_FITNESS: return destruirPeorFitness(sol, shipments, airportMap, n);
            case D_MISMA_RUTA:   return destruirMismaRuta(sol, shipments, airportMap, n);
            default:             return destruirAleatorio(sol, shipments, airportMap, n);
        }
    }

    private List<Shipment> destruirAleatorio(Map<String, Route> sol,
                                              List<Shipment> shipments,
                                              Map<String, Airport> airportMap, int n) {
        List<Shipment> mezclados = new ArrayList<>(shipments);
        Collections.shuffle(mezclados, rnd);
        List<Shipment> destruidos = new ArrayList<>();
        for (int i = 0; i < Math.min(n, mezclados.size()); i++) {
            Shipment s = mezclados.get(i);
            Route ruta = sol.remove(s.getShipmentId());
            if (ruta != null) {
                liberarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
                destruidos.add(s);
            }
        }
        return destruidos;
    }

    private List<Shipment> destruirPeorFitness(Map<String, Route> sol,
                                                List<Shipment> shipments,
                                                Map<String, Airport> airportMap, int n) {
        List<Shipment> ordenados = new ArrayList<>(shipments);
        ordenados.sort((a, b) -> {
            double fa = contribucionFitness(sol.get(a.getShipmentId()), a, airportMap);
            double fb = contribucionFitness(sol.get(b.getShipmentId()), b, airportMap);
            return Double.compare(fb, fa);
        });
        List<Shipment> destruidos = new ArrayList<>();
        for (int i = 0; i < Math.min(n, ordenados.size()); i++) {
            Shipment s = ordenados.get(i);
            Route ruta = sol.remove(s.getShipmentId());
            if (ruta != null) {
                liberarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
                destruidos.add(s);
            }
        }
        return destruidos;
    }

    private List<Shipment> destruirMismaRuta(Map<String, Route> sol,
                                              List<Shipment> shipments,
                                              Map<String, Airport> airportMap, int n) {
        Map<String, List<Shipment>> envioPorVuelo = new HashMap<>();
        for (Shipment s : shipments) {
            Route ruta = sol.get(s.getShipmentId());
            if (ruta == null) continue;
            for (Flight f : ruta.getFlights())
                envioPorVuelo.computeIfAbsent(f.getFlightId(), k -> new ArrayList<>()).add(s);
        }
        if (envioPorVuelo.isEmpty()) return destruirAleatorio(sol, shipments, airportMap, n);

        List<Map.Entry<String, List<Shipment>>> entries = new ArrayList<>(envioPorVuelo.entrySet());
        entries.sort((a, b) -> b.getValue().size() - a.getValue().size());
        List<Shipment> candidatos = entries.get(rnd.nextInt(Math.min(3, entries.size()))).getValue();

        List<Shipment> destruidos = new ArrayList<>();
        for (Shipment s : candidatos) {
            if (destruidos.size() >= n) break;
            Route ruta = sol.remove(s.getShipmentId());
            if (ruta != null) {
                liberarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
                destruidos.add(s);
            }
        }
        return destruidos;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OPERADORES DE REPARACIÓN
    // ════════════════════════════════════════════════════════════════════════

    private void agregarSinRutaAReparacion(Map<String, Route> sol,
                                            List<Shipment> shipments,
                                            List<Shipment> destruidos,
                                            int n) {
        Set<String> yaIncluidos = new HashSet<>();
        for (Shipment shipment : destruidos) {
            yaIncluidos.add(shipment.getShipmentId());
        }

        List<Shipment> sinRuta = new ArrayList<>();
        for (Shipment shipment : shipments) {
            if (sol.containsKey(shipment.getShipmentId())) continue;
            if (yaIncluidos.contains(shipment.getShipmentId())) continue;
            sinRuta.add(shipment);
        }
        sinRuta.sort(Comparator
                .comparingInt(Shipment::getRequestMinute)
                .thenComparing(Comparator.comparingInt(Shipment::getSuitcaseCount).reversed()));

        int limite = Math.max(n, fastMode ? 10 : 25);
        for (Shipment shipment : sinRuta) {
            if (limite-- <= 0) break;
            destruidos.add(shipment);
            yaIncluidos.add(shipment.getShipmentId());
        }
    }

    private void reparar(int idxOp, Map<String, Route> sol,
                          List<Shipment> destruidos, List<Shipment> allShipments,
                          List<Flight> flights,
                          ALNSRouteFinder finder, Map<String, Airport> airportMap) {
        switch (idxOp) {
            case R_GREEDY_URGENCIA:
                repararGreedyUrgencia(sol, destruidos, allShipments, flights, finder, airportMap); break;
            case R_GREEDY_COSTO:
                repararGreedyCosto(sol, destruidos, allShipments, flights, finder, airportMap); break;
            case R_ALEATORIO:
                repararAleatorio(sol, destruidos, allShipments, flights, finder, airportMap); break;
            case R_REGRET_K:
                repararRegretK(sol, destruidos, allShipments, flights, finder, airportMap); break;
            default:
                repararGreedyUrgencia(sol, destruidos, allShipments, flights, finder, airportMap);
        }
    }

    private void repararGreedyUrgencia(Map<String, Route> sol, List<Shipment> destruidos,
                                        List<Shipment> allShipments,
                                        List<Flight> flights, ALNSRouteFinder finder,
                                        Map<String, Airport> airportMap) {
        List<Shipment> ordenados = new ArrayList<>(destruidos);
        ordenados.sort(comparadorPlanificacion(finder));
        asignarRutas(sol, ordenados, allShipments, flights, finder, airportMap);
    }

    private void repararGreedyCosto(Map<String, Route> sol, List<Shipment> destruidos,
                                     List<Shipment> allShipments,
                                     List<Flight> flights, ALNSRouteFinder finder,
                                     Map<String, Airport> airportMap) {
        List<Shipment> ordenados = new ArrayList<>(destruidos);
        ordenados.sort(Comparator.comparingDouble(s -> {
            Airport orig = airportMap.get(s.getOriginCode());
            Airport dest = airportMap.get(s.getDestCode());
            if (orig == null || dest == null) return Double.MAX_VALUE;
                return haversineKm(orig.getLatitude(), orig.getLongitude(),
                               dest.getLatitude(), dest.getLongitude());
        }));
        asignarRutas(sol, ordenados, allShipments, flights, finder, airportMap);
    }

    private void repararAleatorio(Map<String, Route> sol, List<Shipment> destruidos,
                                   List<Shipment> allShipments,
                                   List<Flight> flights, ALNSRouteFinder finder,
                                   Map<String, Airport> airportMap) {
        List<Shipment> mezclados = new ArrayList<>(destruidos);
        Collections.shuffle(mezclados, rnd);
        asignarRutas(sol, mezclados, allShipments, flights, finder, airportMap);
    }

    private void repararRegretK(Map<String, Route> sol, List<Shipment> destruidos,
                                List<Shipment> allShipments,
                                List<Flight> flights, ALNSRouteFinder finder,
                                Map<String, Airport> airportMap) {
        final int k = fastMode ? 2 : REGRET_K;
        final int evalLimit = fastMode ? REGRET_FAST_EVAL_LIMIT : REGRET_EVAL_LIMIT;
        List<Shipment> pendientes = new ArrayList<>(destruidos);
        pendientes.sort(comparadorPlanificacion(finder));

        while (!pendientes.isEmpty()) {
            RegretChoice mejor = null;
            int limite = Math.min(evalLimit, pendientes.size());

            for (int i = 0; i < limite; i++) {
                Shipment s = pendientes.get(i);
                Route mejorRuta = null;
                double mejorCosto = Double.POSITIVE_INFINITY;
                double segundoCosto = Double.POSITIVE_INFINITY;
                for (Route ruta : finder.findCandidateRoutesCached(s, flights, k)) {
                    if (!finder.esFeasible(ruta, s.getSuitcaseCount())) {
                        continue;
                    }
                    double costo = costoRuta(ruta, s, finder);
                    if (costo < mejorCosto) {
                        segundoCosto = mejorCosto;
                        mejorCosto = costo;
                        mejorRuta = ruta;
                    } else if (costo < segundoCosto) {
                        segundoCosto = costo;
                    }
                }
                if (mejorRuta == null) continue;

                double alternativa = Double.isFinite(segundoCosto)
                        ? segundoCosto
                        : mejorCosto + PESO_SIN_RUTA;
                double regret = alternativa - mejorCosto;

                if (mejor == null || regret > mejor.regretScore) {
                    mejor = new RegretChoice(s, mejorRuta, regret);
                }
            }

            if (mejor == null) break;
            sol.put(mejor.shipment.getShipmentId(), mejor.route);
            reservarCapacidad(mejor.route, mejor.shipment.getSuitcaseCount(), airportMap);
            pendientes.remove(mejor.shipment);
        }

        if (!pendientes.isEmpty()) {
            asignarRutas(sol, pendientes, allShipments, flights, finder, airportMap);
        }
    }

    /**
     * Lógica central de reparación con fraccionamiento.
     * Intenta ruta completa primero; si falla, fracciona.
     * Los sub-envíos generados son transparentes para el bucle ALNS
     * porque ya están en allShipments desde el greedy inicial.
     */
    private void asignarRutas(Map<String, Route> sol, List<Shipment> ordenados,
                               List<Shipment> allShipments,
                               List<Flight> flights, ALNSRouteFinder finder,
                               Map<String, Airport> airportMap) {
        for (Shipment s : ordenados) {
            // Si es sub-envío ya creado, buscar ruta directamente para su lote
            Route ruta = encontrarMejorRuta(s, flights, finder);
            if (ruta != null) {
                sol.put(s.getShipmentId(), ruta);
                reservarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
                continue;
            }

            // Solo fraccionar envíos originales (no sub-envíos ya fraccionados)
            fraccionarYRegistrar(s, sol, allShipments, flights, finder, airportMap);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PESOS ADAPTATIVOS
    // ════════════════════════════════════════════════════════════════════════

    private void inicializarPesos() {
        pesoDestructor  = new double[NUM_DESTRUCTORES];
        pesoReparador   = new double[NUM_REPARADORES];
        scoreDestructor = new double[NUM_DESTRUCTORES];
        scoreReparador  = new double[NUM_REPARADORES];
        usoDestructor   = new int[NUM_DESTRUCTORES];
        usoReparador    = new int[NUM_REPARADORES];
        Arrays.fill(pesoDestructor, 1.0);
        Arrays.fill(pesoReparador,  1.0);
    }

    private void actualizarPesos() {
        for (int i = 0; i < NUM_DESTRUCTORES; i++) {
            if (usoDestructor[i] > 0) {
                double rend = scoreDestructor[i] / usoDestructor[i];
                pesoDestructor[i] = decayPesos * pesoDestructor[i] + (1 - decayPesos) * rend;
                pesoDestructor[i] = Math.max(0.01, pesoDestructor[i]);
            }
            scoreDestructor[i] = 0;
            usoDestructor[i]   = 0;
        }
        for (int i = 0; i < NUM_REPARADORES; i++) {
            if (usoReparador[i] > 0) {
                double rend = scoreReparador[i] / usoReparador[i];
                pesoReparador[i] = decayPesos * pesoReparador[i] + (1 - decayPesos) * rend;
                pesoReparador[i] = Math.max(0.01, pesoReparador[i]);
            }
            scoreReparador[i] = 0;
            usoReparador[i]   = 0;
        }
    }

    private int seleccionarPorRuleta(double[] pesos) {
        double suma = Arrays.stream(pesos).sum();
        double r    = rnd.nextDouble() * suma;
        double acum = 0;
        for (int i = 0; i < pesos.length; i++) {
            acum += pesos[i];
            if (r <= acum) return i;
        }
        return pesos.length - 1;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FUNCIÓN OBJETIVO
    // ════════════════════════════════════════════════════════════════════════

    private double calcularFitness(Map<String, Route> sol,
                                    List<Shipment> shipments,
                                    ALNSRouteFinder finder) {
        return sumarContribuciones(calcularContribuciones(sol, shipments, finder));
    }

    private Map<String, Shipment> indexarShipments(List<Shipment> shipments) {
        Map<String, Shipment> index = new HashMap<>(shipments.size() * 2);
        for (Shipment s : shipments) {
            index.put(s.getShipmentId(), s);
        }
        return index;
    }

    private Map<String, Double> calcularContribuciones(Map<String, Route> sol,
                                                       List<Shipment> shipments,
                                                       ALNSRouteFinder finder) {
        Map<String, Double> contribuciones = new HashMap<>(shipments.size() * 2);
        for (Shipment s : shipments) {
            contribuciones.put(s.getShipmentId(),
                    contribucionFitness(sol.get(s.getShipmentId()), s, finder));
        }
        return contribuciones;
    }

    private double sumarContribuciones(Map<String, Double> contribuciones) {
        double total = 0.0;
        for (double valor : contribuciones.values()) {
            total += valor;
        }
        return total;
    }

    private Set<String> idsConCambios(Map<String, Route> anterior,
                                      Map<String, Route> nuevo,
                                      List<Shipment> destruidos) {
        Set<String> ids = new HashSet<>();
        for (Shipment s : destruidos) {
            ids.add(s.getShipmentId());
        }
        for (Map.Entry<String, Route> entry : nuevo.entrySet()) {
            if (anterior.get(entry.getKey()) != entry.getValue()) {
                ids.add(entry.getKey());
            }
        }
        for (String id : anterior.keySet()) {
            if (!nuevo.containsKey(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private double contribucionFitness(Route ruta, Shipment s, ALNSRouteFinder finder) {
        if (ruta == null || !ruta.isValid()) return PESO_SIN_RUTA;
        int llegada  = ruta.calculateArrivalMinute();
        int deadline = s.getRequestMinute() + finder.getDeadlineMinutes(s);
        double contrib = llegada - s.getRequestMinute();
        if (llegada > deadline) {
            contrib += PESO_ENVIO_TARDE + PESO_FUERA_PLAZO * (llegada - deadline);
        }
        return contrib + penalizacionPreventivaOrigen(ruta, s);
    }

    private double contribucionFitness(Route ruta, Shipment s,
                                        Map<String, Airport> airportMap) {
        if (ruta == null || !ruta.isValid()) return PESO_SIN_RUTA;
        Airport orig = airportMap.get(s.getOriginCode());
        Airport dest = airportMap.get(s.getDestCode());
        int deadlineMin = Shipment.getDeadlineMinutes(
                orig != null ? orig.getContinent() : "",
                dest != null ? dest.getContinent() : "");
        int llegada  = ruta.calculateArrivalMinute();
        int deadline = s.getRequestMinute() + deadlineMin;
        double contrib = llegada - s.getRequestMinute();
        if (llegada > deadline) {
            contrib += PESO_ENVIO_TARDE + PESO_FUERA_PLAZO * (llegada - deadline);
        }
        return contrib + penalizacionPreventivaOrigen(ruta, s);
    }

    private double costoRuta(Route ruta, Shipment s, ALNSRouteFinder finder) {
        if (ruta == null || !ruta.isValid()) return PESO_SIN_RUTA;
        int llegada = ruta.calculateArrivalMinute();
        int deadline = s.getRequestMinute() + finder.getDeadlineMinutes(s);
        double costo = llegada - s.getRequestMinute();
        if (llegada > deadline) {
            costo += PESO_ENVIO_TARDE + PESO_FUERA_PLAZO * (llegada - deadline);
        }
        return costo + penalizacionPreventivaOrigen(ruta, s);
    }

    private Route encontrarMejorRuta(Shipment shipment,
                                     List<Flight> flights,
                                     ALNSRouteFinder finder) {
        Route mejor = null;
        double mejorCosto = Double.POSITIVE_INFINITY;
        for (Route ruta : finder.findCandidateRoutesCached(shipment, flights, 5)) {
            if (!finder.esFeasible(ruta, shipment.getSuitcaseCount())) continue;
            double costo = costoRuta(ruta, shipment, finder);
            if (costo < mejorCosto) {
                mejor = ruta;
                mejorCosto = costo;
            }
        }
        return mejor != null ? mejor : finder.findBestRouteCached(shipment, flights);
    }

    private Comparator<Shipment> comparadorPlanificacion(ALNSRouteFinder finder) {
        return Comparator
                .comparingDouble((Shipment s) ->
                        -prioridadOrigen.getOrDefault(s.getOriginCode(), 0.0))
                .thenComparingInt(s -> s.getRequestMinute() + finder.getDeadlineMinutes(s))
                .thenComparing(Comparator.comparingInt(Shipment::getSuitcaseCount));
    }

    private double penalizacionPreventivaOrigen(Route ruta, Shipment shipment) {
        double prioridad = prioridadOrigen.getOrDefault(shipment.getOriginCode(), 0.0);
        if (prioridad <= 0.0 || ruta == null || ruta.getFlights().isEmpty()) return 0.0;
        int salida = ruta.getFlights().get(0).absoluteDepartureMinute();
        int esperaOrigen = Math.max(0, salida - shipment.getRequestMinute());
        return prioridad * esperaOrigen * PESO_ESPERA_ORIGEN_PREVENTIVA;
    }

    private boolean tiempoAgotado(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    private static class RegretChoice {
        final Shipment shipment;
        final Route route;
        final double regretScore;

        RegretChoice(Shipment shipment, Route route, double regretScore) {
            this.shipment = shipment;
            this.route = route;
            this.regretScore = regretScore;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GESTIÓN DE CAPACIDAD
    // ════════════════════════════════════════════════════════════════════════

    private void reservarCapacidad(Route ruta, int maletas,
                                    Map<String, Airport> airportMap) {
        for (Flight f : ruta.getFlights()) f.assignLoad(maletas);
        if (airportCapacityTimeline != null) {
            airportCapacityTimeline.reserve(ruta, maletas);
        }
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size() - 1; i++) {
            Airport apt = airportMap.get(vuelos.get(i).getDestCode());
            if (apt != null) apt.addLoad(maletas);
        }
    }

    private void liberarCapacidad(Route ruta, int maletas,
                                   Map<String, Airport> airportMap) {
        for (Flight f : ruta.getFlights()) f.releaseLoad(maletas);
        if (airportCapacityTimeline != null) {
            airportCapacityTimeline.release(ruta, maletas);
        }
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size() - 1; i++) {
            Airport apt = airportMap.get(vuelos.get(i).getDestCode());
            if (apt != null) apt.removeLoad(maletas);
        }
    }

    private Map<Flight, Integer> snapshotCargasVuelos(List<Flight> flights,
                                                       boolean preservar) {
        Map<Flight, Integer> snapshot = new IdentityHashMap<>();
        for (Flight f : flights) {
            snapshot.put(f, preservar ? f.getAssignedLoad() : 0);
        }
        return snapshot;
    }

    private Map<String, Integer> snapshotCargasAeropuertos(Map<String, Airport> airportMap,
                                                           boolean preservar) {
        Map<String, Integer> snapshot = new HashMap<>();
        for (Airport a : airportMap.values()) {
            snapshot.put(a.getCode(), preservar ? a.getCurrentLoad() : 0);
        }
        return snapshot;
    }

    private void restaurarCapacidadBase(List<Flight> flights,
                                        Map<String, Airport> airportMap,
                                        Map<Flight, Integer> cargaBaseVuelos,
                                        Map<String, Integer> cargaBaseAeropuertos) {
        for (Flight f : flights) {
            f.resetLoad();
            int carga = cargaBaseVuelos.getOrDefault(f, 0);
            if (carga > 0) f.assignLoad(carga);
        }
        for (Airport a : airportMap.values()) {
            a.resetLoad();
            int carga = cargaBaseAeropuertos.getOrDefault(a.getCode(), 0);
            if (carga > 0) a.addLoad(carga);
        }
    }

    private void reconstruirCapacidad(Map<String, Route> sol,
                                      List<Shipment> shipments,
                                      List<Flight> flights,
                                      Map<String, Airport> airportMap,
                                      Map<Flight, Integer> cargaBaseVuelos,
                                      Map<String, Integer> cargaBaseAeropuertos) {
        restaurarCapacidadBase(flights, airportMap, cargaBaseVuelos, cargaBaseAeropuertos);
        if (airportCapacityTimeline != null) {
            airportCapacityTimeline.resetToBase();
        }
        for (Shipment s : shipments) {
            Route ruta = sol.get(s.getShipmentId());
            if (ruta != null && ruta.isValid()) {
                reservarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UTILIDADES
    // ════════════════════════════════════════════════════════════════════════

    private void registrarResultados(Map<String, Route> sol,
                                      List<Shipment> shipments,
                                      ALNSRouteFinder finder) {
        for (Shipment s : shipments) {
            Route ruta = sol.get(s.getShipmentId());
            if (ruta != null && ruta.isValid())
                s.setResult(ruta, ruta.calculateArrivalMinute(),
                        finder.getDeadlineMinutes(s));
        }
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private void imprimirResumen() {
        System.out.printf(
            "[ALNS] Finalizado | Iters: %d | Fitness: %.0f → %.0f | " +
            "Mejoras: %d | Aceptadas SA: %d%n",
            iteracionesEjecutadas, fitnessMejorInicial, fitnessMejorFinal,
            mejorasGlobal, aceptadasSA);
        System.out.printf(
            "[ALNS] Pesos Destructores: D0=%.2f D1=%.2f D2=%.2f%n",
            pesoDestructor[0], pesoDestructor[1], pesoDestructor[2]);
        System.out.printf(
            "[ALNS] Pesos Reparadores:  R0=%.2f R1=%.2f R2=%.2f R3=%.2f%n",
            pesoReparador[0], pesoReparador[1], pesoReparador[2], pesoReparador[3]);
    }

    // ── Getters de métricas ───────────────────────────────────────────────────
    public int    getMaxIteraciones()        { return maxIteraciones; }
    public int    getSegmento()              { return segmento; }
    public int    getNDestruir()             { return nDestruir; }
    public double getTemperaturaInicial()    { return temperaturaInicial; }
    public double getAlpha()                 { return alpha; }
    public int    getIteracionesEjecutadas() { return iteracionesEjecutadas; }
    public double getFitnessMejorInicial()   { return fitnessMejorInicial; }
    public double getFitnessMejorFinal()     { return fitnessMejorFinal; }
    public int    getMejorasGlobal()         { return mejorasGlobal; }
    public int    getAceptadasSA()           { return aceptadasSA; }
    public double[] getPesoDestructor()      { return pesoDestructor.clone(); }
    public double[] getPesoReparador()       { return pesoReparador.clone(); }
}
