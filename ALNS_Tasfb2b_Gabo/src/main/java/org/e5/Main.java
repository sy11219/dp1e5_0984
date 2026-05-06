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

/**
 * Punto de entrada principal del sistema de planificación TASF.B2B.
 *
 * Soporta 3 escenarios según el enunciado:
 *   1. SIMULACION  — período fijo (3, 5 o 7 días). Debe ejecutarse en 30–90 min.
 *   2. TIEMPO_REAL — operaciones día a día (simula 1 día con parámetros ligeros).
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
     * Modifica los parámetros maxIteraciones según el tamaño del problema.
     */
    private static void ejecutarSimulacion(List<Shipment> shipments,
                                            List<Flight> flights,
                                            List<Airport> airports,
                                            Map<String, Airport> airportMap) {
        // Ajustar iteraciones según tamaño: más envíos → más iteraciones
        int iteraciones = Math.min(1000, Math.max(300, shipments.size() * 2));

        ALNS alns = new ALNS(
                iteraciones,  // maxIteraciones
                30,           // segmento
                -1,           // nDestruir: 20% automático
                500.0,        // temperaturaInicial
                0.997,        // alpha (enfriamiento)
                4,            // maxEscalas
                9.0,          // rewardMejora
                3.0,          // rewardAcepta
                0.0,          // rewardNoAcepta
                0.8           // decayPesos
        );

        Map<String, Route> resultado = alns.ejecutar(shipments, flights, airportMap);
        System.out.printf("  Rutas encontradas: %d / %d%n", resultado.size(), shipments.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESCENARIO 2: TIEMPO REAL (1 día, respuesta rápida)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Escenario tiempo real: parámetros ligeros para respuesta inmediata.
     * Simula operaciones del día a día donde el planificador debe responder rápido.
     */
    private static void ejecutarTiempoReal(List<Shipment> shipments,
                                            List<Flight> flights,
                                            List<Airport> airports,
                                            Map<String, Airport> airportMap) {
        ALNS alns = new ALNS(
                50,   // pocas iteraciones para respuesta rápida 
                5,    // segmento pequeño: Cada 5 iteraciones se ajustan pesos
                -1, //No hay número fijo de destrucciones. Se calcula en ALNS
                100.0, // temperatura inicial más baja para converger rápido
                0.99, // Más cerca a 1, enfriamiento más lento para explorar más
                3,     // menos escalas para rutas más directas
                9.0, 3.0, 0.0, 0.8
        );

        Map<String, Route> resultado = alns.ejecutar(shipments, flights, airportMap);
        System.out.printf("  [TIEMPO REAL] Rutas encontradas: %d / %d%n",
                resultado.size(), shipments.size());
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
            for (Flight f : flights) f.resetLoad();
            for (Airport a : airports) a.resetLoad();
            // Resetear estado de planificación de envíos
            for (Shipment s : demandaActual) {
                // Los Shipment son inmutables en su estado original,
                // pero podemos crear nuevas instancias o reusar.
                // Si Shipment tiene reset, usarlo aquí.
            }

            ALNS alns = new ALNS(200, 20, -1, 400.0, 0.997, 4, 9.0, 3.0, 0.0, 0.8);
            Map<String, Route> resultado = alns.ejecutar(demandaActual, flights, airportMap);

            // Contar envíos a tiempo
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
                // Incrementar demanda: duplicar envíos con IDs modificados
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
            System.out.println("  2. TIEMPO_REAL — operaciones del día a día (1 día)");
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
        if (escenario == Escenario.TIEMPO_REAL) return 1; // siempre 1 día
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