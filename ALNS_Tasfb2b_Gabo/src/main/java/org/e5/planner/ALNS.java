package org.e5.planner;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
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

    // ── Pesos función objetivo ────────────────────────────────────────────────
    private static final double PESO_SIN_RUTA    = 100_000.0;
    private static final double PESO_FUERA_PLAZO = 50.0;

    // ── Índices operadores destrucción ────────────────────────────────────────
    private static final int D_ALEATORIO      = 0;
    private static final int D_PEOR_FITNESS   = 1;
    private static final int D_MISMA_RUTA     = 2;
    private static final int NUM_DESTRUCTORES = 3;

    // ── Índices operadores reparación ─────────────────────────────────────────
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

    // ── Métricas ─────────────────────────────────────────────────────────────
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
        if (shipments.isEmpty()) return new HashMap<>();

        for (Flight f : flights)  f.resetLoad();
        for (Airport a : airportMap.values()) a.resetLoad();

        ALNSRouteFinder finder = new ALNSRouteFinder(airportMap, maxEscalas);

        iteracionesEjecutadas = 0;
        mejorasGlobal         = 0;
        aceptadasSA           = 0;
        inicializarPesos();

        int n = nDestruir > 0 ? nDestruir : Math.max(3, shipments.size() / 5);

        // 1. Solución greedy inicial con fraccionamiento
        List<Shipment> allShipments = new ArrayList<>(shipments);
        Map<String, Route> solActual = construirSolucionGreedy(
                allShipments, flights, finder, airportMap);

        // Propagar sub-envíos generados al fraccionamiento a la lista original
        sincronizarShipments(shipments, allShipments);

        Map<String, Route> solMejor = new HashMap<>(solActual);
        double fitActual = calcularFitness(solActual, allShipments, finder);
        double fitMejor  = fitActual;
        fitnessMejorInicial = fitMejor;

        System.out.printf("[ALNS] Iniciando | Envíos: %d | Vuelos: %d | Fitness inicial: %.0f%n",
                allShipments.size(), flights.size(), fitMejor);

        double T = temperaturaInicial;

        // 2. Bucle principal ALNS
        for (int iter = 0; iter < maxIteraciones; iter++) {
            iteracionesEjecutadas++;

            int idxD = seleccionarPorRuleta(pesoDestructor);
            int idxR = seleccionarPorRuleta(pesoReparador);

            Map<String, Route> solDestruida = new HashMap<>(solActual);
            List<Shipment> destruidos = destruir(
                    idxD, solDestruida, allShipments, airportMap, n);

            reparar(idxR, solDestruida, destruidos, flights, finder, airportMap);

            double fitNuevo = calcularFitness(solDestruida, allShipments, finder);
            double delta    = fitNuevo - fitActual;

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
                    System.out.printf("[ALNS] Iter %d | Mejor: %.0f | D%d R%d%n",
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
        registrarResultados(solMejor, allShipments, finder);
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
        return rapido.ejecutar(afectados, disponibles, airportMap);
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
        aRutar.sort(Comparator.comparingInt(s -> finder.getDeadlineMinutes(s)));

        for (Shipment s : aRutar) {
            // 1. Intentar ruta completa
            Route rutaCompleta = finder.findBestRoute(s, flights);
            if (rutaCompleta != null) {
                solucion.put(s.getShipmentId(), rutaCompleta);
                reservarCapacidad(rutaCompleta, s.getSuitcaseCount(), airportMap);
                continue;
            }

            // 2. Fraccionar dinámicamente
            List<ALNSRouteFinder.PartialRoute> parciales =
                    finder.findFractionalRoutes(s, flights);

            if (parciales.isEmpty()) continue; // sin ruta posible

            // Crear sub-envíos con trazabilidad y agregarlos
            List<Shipment> partes = crearSubEnvios(s, parciales);
            allShipments.addAll(partes);

            // Registrar la ruta de cada parte en la solución
            for (int i = 0; i < partes.size(); i++) {
                Shipment parte = partes.get(i);
                Route ruta = parciales.get(i).ruta;
                solucion.put(parte.getShipmentId(), ruta);
            }
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
    private void sincronizarShipments(List<Shipment> original, List<Shipment> actualizado) {
        for (Shipment s : actualizado) {
            if (!original.contains(s)) original.add(s);
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

    private void reparar(int idxOp, Map<String, Route> sol,
                          List<Shipment> destruidos, List<Flight> flights,
                          ALNSRouteFinder finder, Map<String, Airport> airportMap) {
        switch (idxOp) {
            case R_GREEDY_URGENCIA:
                repararGreedyUrgencia(sol, destruidos, flights, finder, airportMap); break;
            case R_GREEDY_COSTO:
                repararGreedyCosto(sol, destruidos, flights, finder, airportMap); break;
            case R_ALEATORIO:
                repararAleatorio(sol, destruidos, flights, finder, airportMap); break;
            default:
                repararGreedyUrgencia(sol, destruidos, flights, finder, airportMap);
        }
    }

    private void repararGreedyUrgencia(Map<String, Route> sol, List<Shipment> destruidos,
                                        List<Flight> flights, ALNSRouteFinder finder,
                                        Map<String, Airport> airportMap) {
        List<Shipment> ordenados = new ArrayList<>(destruidos);
        ordenados.sort(Comparator.comparingInt(s -> finder.getDeadlineMinutes(s)));
        asignarRutas(sol, ordenados, flights, finder, airportMap);
    }

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

    private void repararAleatorio(Map<String, Route> sol, List<Shipment> destruidos,
                                   List<Flight> flights, ALNSRouteFinder finder,
                                   Map<String, Airport> airportMap) {
        List<Shipment> mezclados = new ArrayList<>(destruidos);
        Collections.shuffle(mezclados, rnd);
        asignarRutas(sol, mezclados, flights, finder, airportMap);
    }

    /**
     * Lógica central de reparación con fraccionamiento.
     * Intenta ruta completa primero; si falla, fracciona.
     * Los sub-envíos generados son transparentes para el bucle ALNS
     * porque ya están en allShipments desde el greedy inicial.
     */
    private void asignarRutas(Map<String, Route> sol, List<Shipment> ordenados,
                               List<Flight> flights, ALNSRouteFinder finder,
                               Map<String, Airport> airportMap) {
        for (Shipment s : ordenados) {
            // Si es sub-envío ya creado, buscar ruta directamente para su lote
            Route ruta = finder.findBestRoute(s, flights);
            if (ruta != null) {
                sol.put(s.getShipmentId(), ruta);
                reservarCapacidad(ruta, s.getSuitcaseCount(), airportMap);
                continue;
            }

            // Solo fraccionar envíos originales (no sub-envíos ya fraccionados)
            if (!s.isSplitPart()) {
                List<ALNSRouteFinder.PartialRoute> parciales =
                        finder.findFractionalRoutes(s, flights);
                for (ALNSRouteFinder.PartialRoute pr : parciales) {
                    sol.put(pr.ruta.getShipmentId(), pr.ruta);
                }
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

    private void reservarCapacidad(Route ruta, int maletas,
                                    Map<String, Airport> airportMap) {
        for (Flight f : ruta.getFlights()) f.assignLoad(maletas);
        List<Flight> vuelos = ruta.getFlights();
        for (int i = 0; i < vuelos.size() - 1; i++) {
            Airport apt = airportMap.get(vuelos.get(i).getDestCode());
            if (apt != null) apt.addLoad(maletas);
        }
    }

    private void liberarCapacidad(Route ruta, int maletas,
                                   Map<String, Airport> airportMap) {
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
            "[ALNS] Finalizado | Iters: %d | Fitness: %.0f → %.0f | " +
            "Mejoras: %d | Aceptadas SA: %d%n",
            iteracionesEjecutadas, fitnessMejorInicial, fitnessMejorFinal,
            mejorasGlobal, aceptadasSA);
        System.out.printf(
            "[ALNS] Pesos Destructores: D0=%.2f D1=%.2f D2=%.2f%n",
            pesoDestructor[0], pesoDestructor[1], pesoDestructor[2]);
        System.out.printf(
            "[ALNS] Pesos Reparadores:  R0=%.2f R1=%.2f R2=%.2f%n",
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