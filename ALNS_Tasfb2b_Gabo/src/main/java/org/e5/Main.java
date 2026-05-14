package org.e5;

import org.e5.planner.ALNS;
import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;
import org.e5.parser.ShipmentParser;
import org.e5.report.RouteReportGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Punto de entrada principal del sistema de planificación TASF.B2B.
 *
 * Soporta 3 escenarios según el enunciado:
 *   1. SIMULACION  — período fijo (3, 5 o 7 días). Debe ejecutarse en 30–90 min.
 *   2. TIEMPO_REAL — ejecución continua tick a tick con replanificación ante cancelaciones.
 *   3. COLAPSO     — incrementa demanda hasta que el sistema falle.
 */
public class Main {

    /** Escenarios disponibles según el enunciado. */
    enum Escenario { SIMULACION, TIEMPO_REAL, COLAPSO }

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     TASF.B2B - SISTEMA DE PLANIFICACION DE EQUIPAJE      ║");
        System.out.println("║         Algoritmo: ALNS (Adaptive Large Neighborhood)    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        // ── Selección de escenario ────────────────────────────────────────────
        Escenario escenario = leerEscenario(scanner);
        String simulationStartDate = leerFecha(scanner);
        int simulationDays = leerDias(scanner, escenario);

        System.out.printf("%n>> Escenario: %s | Inicio: %s | Duración: %d día(s)%n%n",
                escenario, simulationStartDate, simulationDays);

        try {
            // ── Paso 1: Cargar aeropuertos ────────────────────────────────────
            System.out.println("[1/4] Cargando aeropuertos...");
            AirportParser airportParser = new AirportParser();
            List<Airport> airports = airportParser.parse();

            Map<String, Airport> airportMap = new HashMap<>();
            for (Airport a : airports) {
                airportMap.put(a.getCode(), a);
                System.out.printf("  -> %s | %s, %s | Continente: %s | Cap: %d%n",
                        a.getCode(), a.getCity(), a.getCountry(),
                        a.getContinent(), a.getMaxCapacity());
            }
            System.out.printf("  Total: %d aeropuertos cargados.%n%n", airports.size());

            // ── Paso 2: Cargar vuelos ─────────────────────────────────────────
            int batchDays = simulationDays + 2;
            System.out.printf("[2/4] Cargando plan de vuelos (%d días)...%n", batchDays);
            FlightPlanParser flightParser = new FlightPlanParser();
            List<Flight> flights = flightParser.parse(batchDays, airportMap);
            System.out.printf("  Total: %d vuelos cargados.%n%n", flights.size());

            // ── Paso 3: Cargar envíos ─────────────────────────────────────────
            System.out.println("[3/4] Cargando envíos...");
            ShipmentParser shipmentParser = new ShipmentParser(airportMap);
            List<Shipment> shipments = shipmentParser.parseAll(simulationStartDate, simulationDays);
            System.out.printf("  Total: %d envíos cargados.%n%n", shipments.size());

            if (shipments.isEmpty()) {
                System.out.println("  No hay envíos para planificar.");
                return;
            }

            // ── Paso 4: Ejecutar ALNS según escenario ─────────────────────────
            System.out.printf("[4/4] Ejecutando ALNS — Escenario: %s...%n", escenario);
            long startTime = System.currentTimeMillis();

            switch (escenario) {
                case SIMULACION:
                    ejecutarSimulacion(shipments, flights, airports, airportMap);
                    break;
                case TIEMPO_REAL:
                    ejecutarTiempoReal(shipments, flights, airports, airportMap);
                    break;
                case COLAPSO:
                    ejecutarColapso(shipments, flights, airports, airportMap, simulationStartDate, simulationDays);
                    break;
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // ── Reporte ───────────────────────────────────────────────────────
            System.out.println("\n[Reporte] Generando reporte de rutas...");
            RouteReportGenerator reporter = new RouteReportGenerator(airportMap);
            reporter.generate(shipments, simulationStartDate, simulationDays);
            System.out.printf("%n[ALNS] Tiempo de ejecución total: %.2f segundos%n",
                    elapsed / 1000.0);

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nFin de la simulación.");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESCENARIO 1: SIMULACIÓN DE PERÍODO (30–90 minutos de ejecución)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Escenario estándar: planifica todos los envíos del período.
     * Parámetros ALNS ajustados para ejecutarse entre 30 y 90 minutos.
     */
    private static void ejecutarSimulacion(List<Shipment> shipments,
                                            List<Flight> flights,
                                            List<Airport> airports,
                                            Map<String, Airport> airportMap) {
        ALNS alns = new ALNS(
                500,   // maxIteraciones
                25,    // segmento
                250,    // nDestruir
                250.0, // temperaturaInicial
                0.995,  // alpha (enfriamiento)
                2,     // maxEscalas
                9.0,   // rewardMejora
                3.0,   // rewardAcepta
                0.0,   // rewardNoAcepta
                0.8    // decayPesos
        );

        Map<String, Route> resultado = alns.ejecutar(shipments, flights, airportMap);
        System.out.printf("  Rutas encontradas: %d / %d%n", resultado.size(), shipments.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESCENARIO 2: TIEMPO REAL — ejecución continua con cancelaciones
    // ════════════════════════════════════════════════════════════════════════

    /**
 * Escenario tiempo real: avanza tick a tick procesando envíos a medida que
 * llegan y replanificando inmediatamente ante cancelaciones de vuelos.
 *
 * El ALNS se construye en cada planificación con parámetros dinámicos
 * ajustados al tamaño del lote actual:
 *   - Lotes pequeños (< 10 envíos)  → pocas iteraciones, converge rápido
 *   - Lotes medianos (10–20 envíos) → iteraciones moderadas
 *   - Lotes grandes  (> 20 envíos)  → más iteraciones, mejor exploración
 *
 * Controles en consola (hilo paralelo):
 *   - Escribir un flightId → cancela ese vuelo y replanifica afectados
 *   - Escribir "stop"      → detiene la ejecución limpiamente
 *
 * Parámetros clave del loop:
 *   intervaloTick    — granularidad del avance de tiempo (minutos)
 *   intervaloReplan  — cada cuántos minutos de simulación se llama al ALNS
 *   umbralColaReplan — tamaño de cola que dispara planificación anticipada
 */
private static void ejecutarTiempoReal(List<Shipment> shipments,
                                        List<Flight> flights,
                                        List<Airport> airports,
                                        Map<String, Airport> airportMap) {

    // ── Parámetros del loop ───────────────────────────────────────────────
    final int intervaloTick    = 1;   // minutos por tick
    final int intervaloReplan  = 10;  // planificar cola cada N minutos de simulación
    final int umbralColaReplan = 20;  // o si la cola supera este tamaño

    // ── Cola thread-safe para cancelaciones ───────────────────────────────
    final ConcurrentLinkedQueue<String> cancelaciones = new ConcurrentLinkedQueue<>();
    final boolean[] corriendo = {true};

    // Hilo listener: recibe IDs de vuelos a cancelar o "stop" desde consola.
    // Para integración con frontend/API: reemplazar este hilo por el listener
    // de tu endpoint y hacer cancelaciones.add(flightId) desde ahí.
    Thread cancelListener = new Thread(() -> {
        Scanner sc = new Scanner(System.in);
        System.out.println("  [TIEMPO REAL] Ingrese ID de vuelo a cancelar, o 'stop' para detener:");
        while (corriendo[0]) {
            try {
                if (sc.hasNextLine()) {
                    String input = sc.nextLine().trim();
                    if (input.equalsIgnoreCase("stop")) {
                        corriendo[0] = false;
                        System.out.println("  [TIEMPO REAL] Señal de parada recibida.");
                    } else if (!input.isEmpty()) {
                        cancelaciones.add(input);
                        System.out.printf("  [Cancelación registrada] Vuelo: %s%n", input);
                    }
                }
            } catch (Exception ignored) {}
        }
    });
    cancelListener.setDaemon(true);
    cancelListener.start();

    // ── Indexar envíos por minuto de solicitud para acceso O(1) ──────────
    Map<Integer, List<Shipment>> enviosPorMinuto = new HashMap<>();
    for (Shipment s : shipments) {
        enviosPorMinuto
            .computeIfAbsent(s.getRequestMinute(), k -> new ArrayList<>())
            .add(s);
    }

    int tickMaximo = flights.stream()
            .mapToInt(Flight::absoluteArrivalMinute)
            .max().orElse(1440);

    int tickActual = 0;
    List<Shipment> colaActual        = new ArrayList<>();
    List<Shipment> todosPlanificados = new ArrayList<>();

    System.out.println("  [TIEMPO REAL] Iniciando ejecución continua...");
    System.out.printf("  [TIEMPO REAL] Tick máximo: %d minutos (%d días)%n%n",
            tickMaximo, tickMaximo / 1440);

    // ════════════════════════════════════════════════════════════════════
    //  LOOP PRINCIPAL
    // ════════════════════════════════════════════════════════════════════
    while (corriendo[0] && tickActual <= tickMaximo) {

        // ── 0. Procesar cancelaciones pendientes ──────────────────────────
        while (!cancelaciones.isEmpty()) {
            String flightIdCancelado = cancelaciones.poll();

            Flight vueloCancelado = null;
            for (Flight f : flights) {
                if (f.getFlightId().equals(flightIdCancelado)) {
                    vueloCancelado = f;
                    break;
                }
            }

            if (vueloCancelado == null) {
                System.out.printf("  [Cancelación] Vuelo '%s' no encontrado — ignorado.%n",
                        flightIdCancelado);
                continue;
            }

            // Identificar envíos cuya ruta usa ese vuelo
            List<Shipment> afectados = new ArrayList<>();
            for (Shipment s : todosPlanificados) {
                Route ruta = s.getAssignedRoute();
                if (ruta == null) continue;
                for (Flight f : ruta.getFlights()) {
                    if (f.getFlightId().equals(flightIdCancelado)) {
                        afectados.add(s);
                        s.resetPlanningState();
                        break;
                    }
                }
            }

            System.out.printf("  [Tick %d] Cancelación vuelo %s | %d envíos afectados%n",
                    tickActual, flightIdCancelado, afectados.size());

            if (!afectados.isEmpty()) {
                // ALNS dinámico para replanificación: ajustado al número de afectados
                int itersReplan   = Math.max(20, Math.min(80, afectados.size() * 3));
                int segReplan     = Math.max(5, itersReplan / 5);
                ALNS alnsReplan   = new ALNS(
                        itersReplan, segReplan, afectados.size(),
                        80.0, 0.96, 2,
                        9.0, 3.0, 0.0, 0.8);

                Map<String, Route> replan = alnsReplan.replanificar(
                        afectados, flightIdCancelado, flights, airportMap);
                System.out.printf("  [Tick %d] Replanificados: %d / %d%n",
                        tickActual, replan.size(), afectados.size());
            }
        }

        // ── 1. Recoger envíos que llegan en este tick ─────────────────────
        List<Shipment> nuevos = enviosPorMinuto.getOrDefault(
                tickActual, Collections.emptyList());
        if (!nuevos.isEmpty()) {
            colaActual.addAll(nuevos);
            System.out.printf("  [Tick %d] +%d envíos nuevos (cola: %d pendientes)%n",
                    tickActual, nuevos.size(), colaActual.size());
        }

        // ── 2. Actualizar aeropuertos: vuelos que aterrizan en este tick ──
        for (Flight f : flights) {
            if (f.absoluteArrivalMinute() == tickActual && f.getAssignedLoad() > 0) {
                Airport dest = airportMap.get(f.getDestCode());
                if (dest != null) dest.addLoad(f.getAssignedLoad());
            }
        }

        // ── 3. Planificar la cola si toca ─────────────────────────────────
        if (!colaActual.isEmpty()
                && (tickActual % intervaloReplan == 0
                    || colaActual.size() >= umbralColaReplan)) {

            // ALNS dinámico: parámetros ajustados al tamaño del lote actual
            int iters   = Math.max(20, Math.min(80, colaActual.size() * 3));
            int segmento = Math.max(5, iters / 5);
            ALNS alns   = new ALNS(
                    iters,    // maxIteraciones: entre 20 y 80 según lote
                    segmento, // ~5 actualizaciones de pesos adaptativos
                    -1,       // nDestruir automático (20% del lote)
                    80.0,     // temperaturaInicial baja para converger rápido
                    0.96,     // alpha agresivo: T final ≈ 80 × 0.96^iters
                    2,        // maxEscalas reducido: espacio más pequeño
                    9.0, 3.0, 0.0, 0.8);

            List<Flight> vuelosDisponibles = filtrarVuelosDisponibles(flights, tickActual);
            Map<String, Route> resultado   = alns.ejecutar(
                    colaActual, vuelosDisponibles, airportMap);

            System.out.printf("  [Tick %d] Planificados: %d / %d | iters ALNS: %d%n",
                    tickActual, resultado.size(), colaActual.size(), iters);

            todosPlanificados.addAll(colaActual);
            colaActual.clear();
        }

        tickActual += intervaloTick;
    }

    // ── Planificar lo que quedó en cola al terminar el loop ───────────────
    if (!colaActual.isEmpty()) {
        System.out.printf("  [TIEMPO REAL] Planificando %d envíos restantes en cola...%n",
                colaActual.size());
        int iters    = Math.max(20, Math.min(80, colaActual.size() * 3));
        int segmento = Math.max(5, iters / 5);
        ALNS alns    = new ALNS(iters, segmento, -1, 80.0, 0.96, 2, 9.0, 3.0, 0.0, 0.8);
        List<Flight> vuelosDisponibles = filtrarVuelosDisponibles(flights, tickActual);
        alns.ejecutar(colaActual, vuelosDisponibles, airportMap);
        todosPlanificados.addAll(colaActual);
        colaActual.clear();
    }

    // Propagar a la lista original para que el reporte los incluya
    for (Shipment s : todosPlanificados) {
        if (!shipments.contains(s)) shipments.add(s);
    }

    System.out.printf("%n  [TIEMPO REAL] Fin. Total envíos procesados: %d%n",
            todosPlanificados.size());
}

    /**
     * Filtra vuelos que aún no han despegado en el tick actual.
     * Solo estos son candidatos válidos para nuevas asignaciones.
     */
    private static List<Flight> filtrarVuelosDisponibles(List<Flight> flights, int tickActual) {
        List<Flight> disponibles = new ArrayList<>();
        for (Flight f : flights)
            if (f.absoluteDepartureMinute() >= tickActual) disponibles.add(f);
        return disponibles;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESCENARIO 3: COLAPSO (incrementa demanda hasta fallo del sistema)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Escenario colapso: duplica los envíos progresivamente hasta que el sistema
     * no pueda planificar más del 50% de los envíos a tiempo.
     *
     * Reporta en qué multiplicador colapsó el sistema.
     */
    private static void ejecutarColapso(List<Shipment> shipments,
                                         List<Flight> flights,
                                         List<Airport> airports,
                                         Map<String, Airport> airportMap,
                                         String startDate, int dias) {
        System.out.println("[COLAPSO] Iniciando simulación de colapso...");
        System.out.println("[COLAPSO] Se incrementará la demanda hasta que el sistema falle.");

        List<Shipment> demandaActual = new ArrayList<>(shipments);
        int multiplicador = 1;
        boolean colapso = false;

        while (!colapso && multiplicador <= 20) {
            System.out.printf("%n[COLAPSO] Multiplicador de demanda: x%d (%d envíos)%n",
                    multiplicador, demandaActual.size());

            // Resetear cargas de vuelos y aeropuertos para cada intento
            for (Flight f : flights)   f.resetLoad();
            for (Airport a : airports) a.resetLoad();
            for (Shipment s : demandaActual) s.resetPlanningState();

            ALNS alns = new ALNS(200, 20, -1, 400.0, 0.997, 4, 9.0, 3.0, 0.0, 0.8);
            Map<String, Route> resultado = alns.ejecutar(demandaActual, flights, airportMap);

            // Contar envíos con ruta válida
            long aTime = resultado.values().stream()
                    .filter(r -> r != null && r.isValid())
                    .count();
            double pctExito = 100.0 * aTime / demandaActual.size();

            System.out.printf("[COLAPSO] Rutas válidas: %d / %d (%.1f%%)%n",
                    aTime, demandaActual.size(), pctExito);

            if (pctExito < 50.0) {
                System.out.printf("%n[COLAPSO] *** SISTEMA COLAPSADO en multiplicador x%d ***%n",
                        multiplicador);
                System.out.printf("[COLAPSO] Solo %.1f%% de envíos pudieron ser planificados.%n",
                        pctExito);
                colapso = true;
            } else {
                multiplicador++;
                List<Shipment> extras = new ArrayList<>();
                for (Shipment s : shipments) {
                    extras.add(new Shipment(
                            s.getShipmentId() + "_x" + multiplicador,
                            s.getOriginCode(), s.getDestCode(),
                            s.getRequestMinute(), s.getSuitcaseCount(),
                            s.getClientId(), s.getRawDate(),
                            s.getRawHour(), s.getRawMinuteStr()
                    ));
                }
                demandaActual.addAll(extras);
            }
        }

        if (!colapso) {
            System.out.println("[COLAPSO] El sistema no colapsó hasta x20 de demanda.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LECTURA DE PARÁMETROS
    // ════════════════════════════════════════════════════════════════════════

    private static Escenario leerEscenario(Scanner scanner) {
        while (true) {
            System.out.println("Seleccione el escenario:");
            System.out.println("  1. SIMULACION  — período fijo (3, 5 o 7 días)");
            System.out.println("  2. TIEMPO_REAL — ejecución continua con cancelaciones");
            System.out.println("  3. COLAPSO     — incrementa demanda hasta fallo");
            System.out.print("Opción (1/2/3): ");
            String input = scanner.nextLine().trim();
            switch (input) {
                case "1": return Escenario.SIMULACION;
                case "2": return Escenario.TIEMPO_REAL;
                case "3": return Escenario.COLAPSO;
                default:  System.out.println("  Opción inválida. Ingrese 1, 2 o 3.");
            }
        }
    }

    private static String leerFecha(Scanner scanner) {
        while (true) {
            System.out.print("Ingrese la fecha de inicio (aaaammdd, ej: 20260102): ");
            String input = scanner.nextLine().trim();
            if (input.matches("\\d{8}")) {
                int month = Integer.parseInt(input.substring(4, 6));
                int day   = Integer.parseInt(input.substring(6, 8));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) return input;
            }
            System.out.println("  Formato incorrecto. Use aaaammdd.");
        }
    }

    private static int leerDias(Scanner scanner, Escenario escenario) {
        if (escenario == Escenario.TIEMPO_REAL) {
            System.out.print("Ingrese días de datos a cargar para tiempo real (1–7): ");
            try {
                int d = Integer.parseInt(scanner.nextLine().trim());
                if (d >= 1 && d <= 7) return d;
            } catch (NumberFormatException ignored) {}
            return 3; // default
        }
        if (escenario == Escenario.COLAPSO) {
            System.out.print("Ingrese días base para colapso (1–7): ");
            try {
                int d = Integer.parseInt(scanner.nextLine().trim());
                if (d >= 1 && d <= 7) return d;
            } catch (NumberFormatException ignored) {}
            return 3; // default
        }
        while (true) {
            System.out.print("Ingrese días a simular (3, 5 o 7): ");
            String input = scanner.nextLine().trim();
            try {
                int days = Integer.parseInt(input);
                if (days == 3 || days == 5 || days == 7) return days;
                System.out.println("  Para SIMULACION use 3, 5 o 7 días.");
            } catch (NumberFormatException e) {
                System.out.println("  Ingrese un número válido.");
            }
        }
    }
}