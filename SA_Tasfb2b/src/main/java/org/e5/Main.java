package org.e5;


import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;
import org.e5.parser.ShipmentParser;
import org.e5.planner.SimulatedAnnealingPlanner;
import org.e5.report.RouteReportGenerator;
//import org.e5.planner.SimulatedAnnealingPlanner;
//import org.e5.report.RouteReportGenerator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Punto de entrada principal del sistema de planificación TASF.B2B.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLUJO PRINCIPAL DE EJECUCIÓN
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 1. Solicitar al usuario:
 *    - Fecha de inicio de simulación (aaaammdd)
 *    - Número de días a simular
 *
 * 2. Cargar datos de entrada:
 *    a) aeropuertos.txt → Lista de aeropuertos con capacidades y continentes
 *    b) planes_vuelo.txt → Plan de vuelos replicado para N días
 *    c) data/envios/_envios_XXXX_.txt → Envíos de cada aeropuerto
 *
 * 3. Ejecutar el planificador Simulated Annealing:
 *    - Genera rutas candidatas para cada envío (BFS)
 *    - Optimiza la asignación de rutas minimizando retrasos
 *    - Respeta restricciones de capacidad de vuelos y aeropuertos
 *
 * 4. Generar reporte:
 *    - Imprime rutas por envío en consola
 *    - Escribe reporte detallado en data/reporte_rutas.txt
 *
 * ═══════════════════════════════════════════════════════════════════════
 * USO
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Compilar:
 *   javac -d out -sourcepath src/main/java src/main/java/com/tasfb2b/Main.java
 *   (o con Maven: mvn compile)
 *
 * Ejecutar:
 *   java -cp out com.tasfb2b.Main
 *
 * Luego ingresar cuando se pida:
 *   Fecha inicio (aaaammdd): 20260102
 *   Dias de simulacion: 3
 *
 * ═══════════════════════════════════════════════════════════════════════
 * ESTRUCTURA DE ARCHIVOS ESPERADA
 * ═══════════════════════════════════════════════════════════════════════
 *
 * data/
 * ├── aeropuertos.txt
 * ├── planes_vuelo.txt
 * └── envios/
 *     ├── _envios_SKBO_.txt
 *     ├── _envios_SEQM_.txt
 *     ├── _envios_SVMI_.txt
 *     └── ...
 */
public class Main {

    /**
     * Método principal.
     * Orquesta todo el flujo: lectura → planificación → reporte.
     *
     * @param args Argumentos de línea de comandos (no se usan; entrada interactiva)
     */
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     TASF.B2B - SISTEMA DE PLANIFICACION DE EQUIPAJE      ║");
        System.out.println("║         Algoritmo: Simulated Annealing (SA)               ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Lectura de parámetros de simulación ──────────────────────────────
        Scanner scanner = new Scanner(System.in);

        String simulationStartDate = readSimulationDate(scanner);
        int    simulationDays      = readSimulationDays(scanner);

        System.out.println();
        System.out.printf(">> Simulacion desde: %s | Duracion: %d dia(s)%n",
                simulationStartDate, simulationDays);
        System.out.println();

        try {
            // ── Paso 1: Cargar aeropuertos ────────────────────────────────────
            System.out.println("[1/4] Cargando aeropuertos...");
            AirportParser airportParser = new AirportParser();
            List<Airport> airports = airportParser.parse();

            // Construir mapa de aeropuertos para acceso rápido por código ICAO
            Map<String, Airport> airportMap = new HashMap<>();
            for (Airport a : airports) {
                airportMap.put(a.getCode(), a);
                System.out.printf("  -> %s | %s, %s | Continente: %s | Cap: %d%n",
                        a.getCode(), a.getCity(), a.getCountry(),
                        a.getContinent(), a.getMaxCapacity());
            }
            System.out.printf("  Total: %d aeropuertos cargados.%n%n", airports.size());

            // ── Paso 2: Cargar plan de vuelos ─────────────────────────────────
            // Se carga un batch inicial para los días de simulación
            // Si el planificador necesita más días, puede solicitar más
            int batchDays = simulationDays + 2; // margen extra de 2 días
            System.out.printf("[2/4] Cargando plan de vuelos (%d dias)...%n", batchDays);
            FlightPlanParser flightParser = new FlightPlanParser();
            List<Flight> flights = flightParser.parse(batchDays);
            System.out.printf("  Total: %d vuelos cargados.%n%n", flights.size());

            // ── Paso 3: Cargar envíos de todos los aeropuertos ────────────────
            System.out.println("[3/4] Cargando envios de todos los aeropuertos...");
            ShipmentParser shipmentParser = new ShipmentParser(airportMap);
            List<Shipment> shipments = shipmentParser.parseAll(simulationStartDate, simulationDays);

            // Filtrar envíos que no tienen aeropuerto origen o destino conocido
            long unknownOrigin = shipments.stream()
                    .filter(s -> !airportMap.containsKey(s.getOriginCode()))
                    .count();
            long unknownDest = shipments.stream()
                    .filter(s -> !airportMap.containsKey(s.getDestCode()))
                    .count();
            if (unknownOrigin > 0 || unknownDest > 0) {
                System.out.printf("  AVISO: %d envios con origen desconocido, %d con destino desconocido%n",
                        unknownOrigin, unknownDest);
            }
            System.out.printf("  Total: %d envios cargados.%n%n", shipments.size());

            if (shipments.isEmpty()) {
                System.out.println("  No hay envios para planificar. Verifique la carpeta data/envios/");
                System.out.println("  y que los archivos tengan el formato _envios_XXXX_.txt");
                return;
            }
//            System.out.println("IMPRIMIENDO ENVÍOS");
//            for (Shipment shipment : shipments) {
//                System.out.println(shipment);
//            }

            // ── Paso 4: Ejecutar planificador Simulated Annealing ─────────────
            System.out.println("[4/4] Ejecutando Simulated Annealing...");
            long startTime = System.currentTimeMillis();

            SimulatedAnnealingPlanner planner = new SimulatedAnnealingPlanner(airports, flights);
            planner.plan(shipments);

            long elapsed = System.currentTimeMillis() - startTime;


            // ── Generación de reportes ────────────────────────────────────────
            System.out.println("\n[Reporte] Generando reporte de rutas...");
            RouteReportGenerator reporter = new RouteReportGenerator(airportMap);
            reporter.generate(shipments, simulationStartDate, simulationDays);
            System.out.printf("%n[SA] Tiempo de ejecucion: %.2f segundos%n", elapsed / 1000.0);

        } catch (Exception e) {
            System.err.println("[ERROR] Error durante la ejecucion: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nFin de la simulacion.");
    }

    /**
     * Solicita y valida la fecha de inicio de simulación al usuario.
     *
     * Formato esperado: aaaammdd (ej: 20260102)
     * Se valida que:
     * - Tenga exactamente 8 caracteres numéricos
     * - El mes sea 01-12
     * - El día sea 01-31
     *
     * @param scanner Scanner de entrada estándar
     * @return Fecha válida en formato aaaammdd
     */
    private static String readSimulationDate(Scanner scanner) {
        while (true) {
            System.out.print("Ingrese la fecha de inicio de simulacion (aaaammdd, ej: 20260102): ");
            String input = scanner.nextLine().trim();

            if (input.matches("\\d{8}")) {
                int month = Integer.parseInt(input.substring(4, 6));
                int day   = Integer.parseInt(input.substring(6, 8));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return input;
                }
            }
            System.out.println("  Formato incorrecto. Use aaaammdd (ej: 20260102)");
        }
    }

    /**
     * Solicita y valida el número de días de simulación al usuario.
     *
     * Rango válido: 1 a 30 días.
     *
     * @param scanner Scanner de entrada estándar
     * @return Número de días a simular
     */
    private static int readSimulationDays(Scanner scanner) {
        while (true) {
            System.out.print("Ingrese la cantidad de dias a simular (1-30): ");
            String input = scanner.nextLine().trim();
            try {
                int days = Integer.parseInt(input);
                if (days >= 1 && days <= 30) {
                    return days;
                }
                System.out.println("  Debe ser un numero entre 1 y 30.");
            } catch (NumberFormatException e) {
                System.out.println("  Ingrese un numero valido.");
            }
        }
    }
}
