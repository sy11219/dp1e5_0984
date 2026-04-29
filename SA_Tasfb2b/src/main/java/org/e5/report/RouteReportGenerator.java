package org.e5.report;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.model.Route;
import org.e5.model.Shipment;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generador de reportes de planificación de TASF.B2B.
 *
 * Produce dos tipos de salida:
 * 1. CONSOLA: Muestra el resumen y las rutas de cada envío en tiempo real
 * 2. ARCHIVO TXT: Escribe un reporte detallado en "data/reporte_rutas.txt"
 *
 * El reporte incluye por cada envío:
 * - ID del envío, cliente, origen, destino
 * - Número de maletas
 * - Fecha y hora de solicitud
 * - Ruta asignada (secuencia de vuelos con horarios y escalas)
 * - Tiempo total de tránsito
 * - Estado: A TIEMPO / RETRASADO (con minutos de retraso)
 * - Plazo máximo aplicable (24h o 48h según continentes)
 *
 * Al final del reporte incluye un resumen estadístico global.
 */
public class RouteReportGenerator {

    private static final String REPORT_FILE = "data/reporte_rutas.txt";
    private static final String SEPARATOR   = "=".repeat(100);
    private static final String THIN_SEP    = "-".repeat(100);

    private final Map<String, Airport> airportMap;

    /**
     * Constructor.
     *
     * @param airportMap Mapa de aeropuertos (código ICAO → Airport)
     *                   Necesario para obtener nombres legibles y continentes
     */
    public RouteReportGenerator(Map<String, Airport> airportMap) {
        this.airportMap = airportMap;
    }

    /**
     * Genera el reporte completo: imprime en consola y escribe en archivo.
     *
     * @param shipments           Lista de envíos ya planificados
     * @param simulationStartDate Fecha de inicio de simulación (para encabezado)
     * @param simulationDays      Número de días simulados
     */
    public void generate(List<Shipment> shipments, String simulationStartDate, int simulationDays) {
        // Imprimir en consola
        printToConsole(shipments, simulationStartDate, simulationDays);

        // Escribir en archivo
        try {
            writeToFile(shipments, simulationStartDate, simulationDays);
            System.out.printf("%n[Reporte] Reporte guardado en: %s%n", REPORT_FILE);
        } catch (IOException e) {
            System.err.printf("[Reporte] ERROR al escribir archivo: %s%n", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SALIDA EN CONSOLA
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Imprime el reporte completo en la consola estándar.
     *
     * @param shipments           Lista de envíos planificados
     * @param simulationStartDate Fecha de inicio
     * @param simulationDays      Días de simulación
     */
    private void printToConsole(List<Shipment> shipments, String simulationStartDate, int simulationDays) {
        printReport(new PrintWriter(System.out, true), shipments, simulationStartDate, simulationDays);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ESCRITURA EN ARCHIVO TXT
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Escribe el reporte en el archivo data/reporte_rutas.txt.
     *
     * @param shipments           Lista de envíos planificados
     * @param simulationStartDate Fecha de inicio
     * @param simulationDays      Días de simulación
     * @throws IOException Si no se puede escribir el archivo
     */
    private void writeToFile(List<Shipment> shipments, String simulationStartDate,
                              int simulationDays) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(REPORT_FILE))) {
            printReport(pw, shipments, simulationStartDate, simulationDays);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LÓGICA DE REPORTE (reutilizada para consola y archivo)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Escribe el reporte completo en el PrintWriter dado.
     * Centraliza el formato para que consola y archivo sean idénticos.
     *
     * @param pw                  Destino de escritura
     * @param shipments           Lista de envíos planificados
     * @param simulationStartDate Fecha de inicio
     * @param simulationDays      Días de simulación
     */
    private void printReport(PrintWriter pw, List<Shipment> shipments,
                              String simulationStartDate, int simulationDays) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        pw.println(SEPARATOR);
        pw.println("  TASF.B2B - REPORTE DE PLANIFICACION DE RUTAS DE MALETAS");
        pw.println(SEPARATOR);
        pw.printf("  Fecha de generacion  : %s%n", timestamp);
        pw.printf("  Inicio de simulacion : %s%n", formatDate(simulationStartDate));
        pw.printf("  Dias simulados       : %d%n", simulationDays);
        pw.printf("  Total de envios      : %d%n", shipments.size());
        pw.println(SEPARATOR);
        pw.println();

        // Contadores para resumen
        int onTime    = 0;
        int late      = 0;
        int noRoute   = 0;
        long totalDelayMin = 0;

        // ── Detalle por envío ─────────────────────────────────────────────────
        for (int idx = 0; idx < shipments.size(); idx++) {
            Shipment s = shipments.get(idx);

            pw.printf("  ENVIO #%d%n", idx + 1);
            pw.println(THIN_SEP);
            pw.printf("  ID Envio    : %s%n", s.getShipmentId());
            pw.printf("  Cliente     : %s%n", s.getClientId());
            pw.printf("  Maletas     : %d%n", s.getSuitcaseCount());
            pw.printf("  Origen      : %s (%s)%n",
                    s.getOriginCode(), getAirportLabel(s.getOriginCode()));
            pw.printf("  Destino     : %s (%s)%n",
                    s.getDestCode(), getAirportLabel(s.getDestCode()));
            pw.printf("  Solicitado  : %s %s:%s%n",
                    formatDate(s.getRawDate()), s.getRawHour(), s.getRawMinuteStr());
            pw.printf("  Min absoluto inicio: %d%n", s.getRequestMinute());

            // Plazo máximo
            Airport origin = airportMap.get(s.getOriginCode());
            Airport dest   = airportMap.get(s.getDestCode());
            int deadlineMin = (origin != null && dest != null)
                    ? Shipment.getDeadlineMinutes(origin.getContinent(), dest.getContinent())
                    : 1440;
            pw.printf("  Plazo max   : %s (%d minutos)%n",
                    deadlineMin == 720 ? "12 horas (mismo continente)" : "24 horas (distinto continente)",
                    deadlineMin);

            if (!s.isPlanned()) {
                pw.println("  *** SIN RUTA POSIBLE - No se encontraron vuelos disponibles ***");
                noRoute++;
            } else {
                Route route = s.getAssignedRoute();
                pw.printf("  Escalas     : %d%n", route.getLayoverCount());
                pw.printf("  Llegada est : minuto %d (%s, Dia %d)%n",
                        s.getEstimatedArrival(),
                        Flight.minutesToHHMM(s.getEstimatedArrival() % 1440),
                        s.getEstimatedArrival() / 1440);

                int transitTime = s.getEstimatedArrival() - s.getRequestMinute();
                pw.printf("  Transito    : %d minutos (%.1f horas)%n",
                        transitTime, transitTime / 60.0);

                // Estado: a tiempo o retrasado
                if (s.isOnTime()) {
                    pw.println("  Estado      : *** A TIEMPO ***");
                    onTime++;
                } else {
                    pw.printf("  Estado      : !!! RETRASADO !!! (+%d minutos sobre el plazo)%n",
                            s.getDelayMinutes());
                    late++;
                    totalDelayMin += s.getDelayMinutes();
                }

                // Detalle de cada vuelo en la ruta
                pw.println("  Ruta detallada:");
                List<Flight> routeFlights = route.getFlights();
                int readyAt = s.getRequestMinute();
                for (int fi = 0; fi < routeFlights.size(); fi++) {
                    Flight f = routeFlights.get(fi);
                    pw.printf("    Vuelo %d: %s → %s | Dia %d | Salida: %s (min abs %d) | Llegada: %s (min abs %d) | Capacidad usada: %d/%d%n",
                            fi + 1,
                            f.getOriginCode(), f.getDestCode(),
                            f.getDayOffset(),
                            Flight.minutesToHHMM(f.getDepartureMinute()), f.absoluteDepartureMinute(),
                            Flight.minutesToHHMM(f.getArrivalMinute()),   f.absoluteArrivalMinute(),
                            f.getAssignedLoad(), f.getMaxCapacity());
                    if (fi < routeFlights.size() - 1) {
                        pw.printf("    [Escala %d en %s: +%d minutos de carga/descarga]%n",
                                fi + 1, f.getDestCode(), Route.TRANSIT_TIME_MINUTES);
                    }
                }
            }

            pw.println();
        }

        // ── Resumen estadístico global ────────────────────────────────────────
        pw.println(SEPARATOR);
        pw.println("  RESUMEN GLOBAL");
        pw.println(SEPARATOR);
        pw.printf("  Total envios planificados : %d%n", shipments.size());
        pw.printf("  A tiempo                  : %d (%.1f%%)%n",
                onTime, pct(onTime, shipments.size()));
        pw.printf("  Retrasados                : %d (%.1f%%)%n",
                late, pct(late, shipments.size()));
        pw.printf("  Sin ruta posible          : %d (%.1f%%)%n",
                noRoute, pct(noRoute, shipments.size()));
        if (late > 0) {
            pw.printf("  Retraso total acumulado   : %d minutos (%.1f horas promedio/retrasado)%n",
                    totalDelayMin, (double)totalDelayMin / late / 60.0);
        }
        pw.println(SEPARATOR);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Obtiene la etiqueta legible de un aeropuerto: "Ciudad, País".
     * Si el aeropuerto no está en el mapa, devuelve "Desconocido".
     *
     * @param code Código ICAO
     * @return Etiqueta legible
     */
    private String getAirportLabel(String code) {
        Airport a = airportMap.get(code);
        return a != null ? a.getCity() + ", " + a.getCountry() : "Desconocido";
    }

    /**
     * Formatea una fecha en formato aaaammdd a dd/mm/aaaa para mayor legibilidad.
     *
     * @param rawDate Fecha en formato aaaammdd
     * @return Fecha en formato dd/mm/aaaa
     */
    private String formatDate(String rawDate) {
        if (rawDate == null || rawDate.length() != 8) return rawDate;
        return rawDate.substring(6, 8) + "/" + rawDate.substring(4, 6) + "/" + rawDate.substring(0, 4);
    }

    /**
     * Calcula el porcentaje de 'part' sobre 'total' de forma segura (evita división por 0).
     *
     * @param part  Numerador
     * @param total Denominador
     * @return Porcentaje (0.0 si total == 0)
     */
    private double pct(int part, int total) {
        return total == 0 ? 0.0 : (100.0 * part / total);
    }
}
