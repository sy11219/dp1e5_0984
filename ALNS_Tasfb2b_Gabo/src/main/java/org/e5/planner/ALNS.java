package org.e5.planner;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.util.ALNSRouteFinder;

import java.util.*;

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  ADAPTIVE LARGE NEIGHBORHOOD SEARCH (ALNS) — Planificador TASF.B2B
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Combina:
 *   1. Operadores de DESTRUCCIÓN (3): aleatorio, peor fitness, misma ruta
 *   2. Operadores de REPARACIÓN (3): greedy urgencia, greedy costo, aleatorio
 *   3. PESOS ADAPTATIVOS: actualiza pesos por segmento según rendimiento
 *   4. CRITERIO SA: acepta soluciones peores con probabilidad decreciente
 *
 * Delega la búsqueda de rutas a ALNSRouteFinder (org.e5.util),
 * que encapsula el Dijkstra modificado y la verificación de factibilidad.
 */
public class ALNS {

    // ── Parámetros ────────────────────────────────────────────────────────────
    private final int    maxIteraciones;
    private final int    segmento;
    private final int    nDestruir;
    private final double temperaturaInicial;
    private final double alpha;
    private final int    maxEscalas;

    // ── Parámetros de pesos adaptativos ──────────────────────────────────────
    private final double rewardMejora;
    private final double rewardAcepta;
    private final double rewardNoAcepta;
    private final double decayPesos;

    // ── Pesos de la función objetivo ─────────────────────────────────────────
    private static final double PESO_SIN_RUTA    = 100_000.0;
    private static final double PESO_FUERA_PLAZO = 50.0;

    // ── Índices de operadores de destrucción ─────────────────────────────────
    private static final int D_ALEATORIO      = 0;
    private static final int D_PEOR_FITNESS   = 1;
    private static final int D_MISMA_RUTA     = 2;
    private static final int NUM_DESTRUCTORES = 3;

    // ── Índices de operadores de reparación ──────────────────────────────────
    private static final int R_GREEDY_URGENCIA = 0;
    private static final int R_GREEDY_COSTO    = 1;
    private static final int R_ALEATORIO       = 2;
    private static final int NUM_REPARADORES   = 3;

    // ── Pesos adaptativos ────────────────────────────────────────────────────
    private double[] pesoDestructor;
    private double[] pesoReparador;
    private double[] scoreDestructor;
    private double[] scoreReparador;
    private int[]    usoDestructor;
    private int[]    usoReparador;

    // ── Métricas de ejecución ────────────────────────────────────────────────
    private int    iteracionesEjecutadas;
    private double fitnessMejorInicial;
    private double fitnessMejorFinal;
    private int    mejorasGlobal;
    private int    aceptadasSA;

    private final Random rnd = new Random();

    // ── Constructores ─────────────────────────────────────────────────────────

    public ALNS() {
        this(600, 30, -1, 500.0, 0.997, 4, 9.0, 3.0, 0.0, 0.8);
    }

    public ALNS(int maxIteraciones, int segmento, int nDestruir,
                double temperaturaInicial, double alpha, int maxEscalas,
                double rewardMejora, double rewardAcepta,
                double rewardNoAcepta, double decayPesos) {
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
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PUNTO DE ENTRADA PRINCIPAL
    // ════════════════════════════════════════════════════════════════════════

    public Map<String, Route> ejecutar(List<Shipment> shipments,
                                        List<Flight> flights,
                                        Map<String, Airport> airportMap) {
        if (shipments.isEmpty()) return new HashMap<>();

        // ALNSRouteFinder recibe maxEscalas para que Dijkstra respete el mismo límite
        ALNSRouteFinder finder = new ALNSRouteFinder(airportMap, maxEscalas);

        iteracionesEjecutadas = 0;
        mejorasGlobal         = 0;
        aceptadasSA           = 0;
        inicializarPesos();

        int n = nDestruir > 0 ? nDestruir : Math.max(3, shipments.size() / 5);

        // 1. Solución inicial greedy
        Map<String, Route> solActual = construirSolucionGreedy(shipments, flights, finder, airportMap);
        Map<String, Route> solMejor  = new HashMap<>(solActual);

        double fitActual = calcularFitness(solActual, shipments, finder);
        double fitMejor  = fitActual;
        fitnessMejorInicial = fitMejor;

        System.out.printf("[ALNS] Iniciando | Envíos: %d | Vuelos: %d | Fitness inicial: %.0f%n",
                shipments.size(), flights.size(), fitMejor);

        double T = temperaturaInicial;

        // 2. Bucle principal ALNS
        for (int iter = 0; iter < maxIteraciones; iter++) {
            iteracionesEjecutadas++;

            int idxD = seleccionarPorRuleta(pesoDestructor);
            int idxR = seleccionarPorRuleta(pesoReparador);

            // DESTRUIR
            Map<String, Route> solDestruida = new HashMap<>(solActual);
            List<Shipment> destruidos = destruir(idxD, solDestruida, shipments, airportMap, n);

            // REPARAR
            reparar(idxR, solDestruida, destruidos, flights, finder, airportMap);

            // EVALUAR
            double fitNuevo = calcularFitness(solDestruida, shipments, finder);
            double delta    = fitNuevo - fitActual;

            // CRITERIO SA
            double reward;
            if (delta < 0 || rnd.nextDouble() < Math.exp(-delta / Math.max(T, 0.001))) {
                solActual = solDestruida;
                fitActual = fitNuevo;
                aceptadasSA++;

                if (fitActual < fitMejor) {
                    solMejor = new HashMap<>(solActual);
                    fitMejor = fitActual;
                    reward   = rewardMejora;
                    mejorasGlobal++;
                    System.out.printf("[ALNS] Iter %d | Nuevo mejor: %.0f | D%d R%d%n",
                            iter, fitMejor, idxD, idxR);
                } else {
                    reward = rewardAcepta;
                }
            } else {
                reward = rewardNoAcepta;
            }

            scoreDestructor[idxD] += reward;
            scoreReparador[idxR]  += reward;
            usoDestructor[idxD]++;
            usoReparador[idxR]++;

            if ((iter + 1) % segmento == 0) actualizarPesos();

            T *= alpha;
        }

        fitnessMejorFinal = fitMejor;
        registrarResultados(solMejor, shipments, finder);
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

        // Excluir el vuelo cancelado
        List<Flight> disponibles = new ArrayList<>();
        for (Flight f : flights)
            if (!f.getFlightId().equals(flightIdCancelado)) disponibles.add(f);

        ALNS rapido = new ALNS(
                Math.max(80, maxIteraciones / 5),
                Math.max(10, segmento / 2),
                afectados.size(),
                temperaturaInicial * 0.3,
                0.99,
                maxEscalas,
                rewardMejora, rewardAcepta, rewardNoAcepta, decayPesos
        );
        return rapido.ejecutar(afectados, disponibles, airportMap);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SOLUCIÓN GREEDY INICIAL
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Route> construirSolucionGreedy(List<Shipment> shipments,
                                                        List<Flight> flights,
                                                        ALNSRouteFinder finder,
                                                        Map<String, Airport> airportMap) {
        Map<String, Route> solucion = new HashMap<>();

        List<Shipment> ordenados = new ArrayList<>(shipments);
        // Ordenar por urgencia: menor plazo primero
        ordenados.sort(Comparator.comparingInt(s -> finder.getDeadlineMinutes(s)));

        for (Shipment s : ordenados) {
            Route ruta = finder.findBestRoute(s, flights);
            if (ruta != null) {
                solucion.put(s.getShipmentId(), ruta);
                reservarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
            }
        }
        return solucion;
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

    /** D0: elimina N envíos al azar — alta diversificación. */
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

    /** D1: elimina los N con mayor contribución al fitness — alta intensificación. */
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

    /** D2: elimina envíos que comparten un vuelo — simula cancelación de vuelo. */
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

    private void reparar(int idxOp, Map<String, Route> sol,
                          List<Shipment> destruidos, List<Flight> flights,
                          ALNSRouteFinder finder, Map<String, Airport> airportMap) {
        switch (idxOp) {
            case R_GREEDY_URGENCIA: repararGreedyUrgencia(sol, destruidos, flights, finder, airportMap); break;
            case R_GREEDY_COSTO:    repararGreedyCosto(sol, destruidos, flights, finder, airportMap);    break;
            case R_ALEATORIO:       repararAleatorio(sol, destruidos, flights, finder, airportMap);      break;
            default:                repararGreedyUrgencia(sol, destruidos, flights, finder, airportMap);
        }
    }

    /** R0: repara ordenando por urgencia de plazo — prioriza envíos en riesgo. */
    private void repararGreedyUrgencia(Map<String, Route> sol, List<Shipment> destruidos,
                                        List<Flight> flights, ALNSRouteFinder finder,
                                        Map<String, Airport> airportMap) {
        List<Shipment> ordenados = new ArrayList<>(destruidos);
        ordenados.sort(Comparator.comparingInt(s -> finder.getDeadlineMinutes(s)));
        asignarRutas(sol, ordenados, flights, finder, airportMap);
    }

    /** R1: repara ordenando por distancia Haversine — rutas cortas primero. */
    private void repararGreedyCosto(Map<String, Route> sol, List<Shipment> destruidos,
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
        asignarRutas(sol, ordenados, flights, finder, airportMap);
    }

    /** R2: repara en orden aleatorio — mayor diversidad. */
    private void repararAleatorio(Map<String, Route> sol, List<Shipment> destruidos,
                                   List<Flight> flights, ALNSRouteFinder finder,
                                   Map<String, Airport> airportMap) {
        List<Shipment> mezclados = new ArrayList<>(destruidos);
        Collections.shuffle(mezclados, rnd);
        asignarRutas(sol, mezclados, flights, finder, airportMap);
    }

    /** Lógica central de reparación — delega búsqueda de ruta al finder. */
    private void asignarRutas(Map<String, Route> sol, List<Shipment> ordenados,
                               List<Flight> flights, ALNSRouteFinder finder,
                               Map<String, Airport> airportMap) {
        for (Shipment s : ordenados) {
            Route ruta = finder.findBestRoute(s, flights);
            if (ruta != null) {
                sol.put(s.getShipmentId(), ruta);
                reservarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
            }
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

    /** Fórmula: nuevo_peso = decay * peso + (1 - decay) * (score/uso). */
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
        double fitness = 0.0;
        for (Shipment s : shipments) {
            Route ruta = sol.get(s.getShipmentId());
            if (ruta == null || !ruta.isValid()) {
                fitness += PESO_SIN_RUTA;
                continue;
            }
            int llegada  = ruta.calculateArrivalMinute();
            int deadline = s.getRequestMinute() + finder.getDeadlineMinutes(s);
            if (llegada > deadline)
                fitness += PESO_FUERA_PLAZO * (llegada - deadline);
            fitness += llegada - s.getRequestMinute();
        }
        return fitness;
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
        if (llegada > deadline) contrib += PESO_FUERA_PLAZO * (llegada - deadline);
        return contrib;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GESTIÓN DE CAPACIDAD
    // ════════════════════════════════════════════════════════════════════════

    private void reservarCapacidad(Route ruta, int maletas, Map<String, Airport> airportMap) {
        for (Flight f : ruta.getFlights()) f.assignLoad(maletas);
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size() - 1; i++) {
            Airport apt = airportMap.get(vuelos.get(i).getDestCode());
            if (apt != null) apt.addLoad(maletas);
        }
    }

    private void liberarCapacidad(Route ruta, int maletas, Map<String, Airport> airportMap) {
        for (Flight f : ruta.getFlights()) f.releaseLoad(maletas);
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size() - 1; i++) {
            Airport apt = airportMap.get(vuelos.get(i).getDestCode());
            if (apt != null) apt.removeLoad(maletas);
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
            "[ALNS] Finalizado | Iteraciones: %d | Fitness: %.0f → %.0f | " +
            "Mejoras: %d | Aceptadas SA: %d%n",
            iteracionesEjecutadas, fitnessMejorInicial, fitnessMejorFinal,
            mejorasGlobal, aceptadasSA);
        System.out.printf(
            "[ALNS] Pesos Destructores: D0(aleatorio)=%.2f D1(peorFit)=%.2f D2(mismaRuta)=%.2f%n",
            pesoDestructor[0], pesoDestructor[1], pesoDestructor[2]);
        System.out.printf(
            "[ALNS] Pesos Reparadores:  R0(urgencia)=%.2f R1(costo)=%.2f R2(aleatorio)=%.2f%n",
            pesoReparador[0], pesoReparador[1], pesoReparador[2]);
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