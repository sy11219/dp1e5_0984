package org.e5.parser;

import org.e5.model.Flight;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser del archivo planes_vuelo.txt.
 *
 * Lee los vuelos programados y los replica para los días de simulación requeridos.
 *
 * Formato de cada línea de vuelo:
 *   ORIG-DEST-HO:MO-HD:MD-####
 *
 * Donde:
 *   ORIG = código ICAO origen (4 letras)
 *   DEST = código ICAO destino (4 letras)
 *   HO   = hora de salida (2 dígitos)
 *   MO   = minuto de salida (2 dígitos)
 *   HD   = hora de llegada (2 dígitos)
 *   MD   = minuto de llegada (2 dígitos)
 *   #### = capacidad máxima de maletas (4 dígitos, con ceros a la izquierda)
 *
 * Ejemplo:
 *   SKBO-SEQM-03:34-04:21-0300
 *
 * El parser genera un objeto Flight por cada vuelo por cada día del período
 * de simulación, así el planificador puede asignar envíos a vuelos de cualquier día.
 *
 * MANEJO DE MEDIANOCHE:
 * Si la hora de llegada (en minutos) es menor que la de salida, asumimos que el vuelo
 * llega al día siguiente y se suma 1440 minutos a la llegada.
 */
public class FlightPlanParser {

    private static final String DEFAULT_PATH = "data/planes_vuelo.txt";

    // Patrón: SKBO-SEQM-03:34-04:21-0300
    private static final Pattern FLIGHT_PATTERN = Pattern.compile(
            "^([A-Z]{4})-([A-Z]{4})-(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})-(\\d{4})\\s*$"
    );

    /**
     * Carga vuelos desde la ruta por defecto para N días de simulación.
     *
     * @param simulationDays Número de días que durará la simulación
     * @return Lista de vuelos con dayOffset 0..simulationDays-1
     * @throws IOException Si el archivo no puede leerse
     */
    public List<Flight> parse(int simulationDays) throws IOException {
        return parse(DEFAULT_PATH, simulationDays);
    }

    /**
     * Carga vuelos desde una ruta de archivo específica para N días de simulación.
     *
     * Algoritmo:
     * 1. Lee todas las líneas del archivo planes_vuelo.txt
     * 2. Parsea cada línea válida como un "vuelo base" (sin día asignado)
     * 3. Para cada día de simulación (0..simulationDays-1), clona cada vuelo base
     *    asignando el dayOffset correspondiente
     * 4. Retorna la lista completa
     *
     * Esto modela que los vuelos son recurrentes (se repiten cada día),
     * lo cual es consistente con el enunciado ("vuelos se realizan una o más veces al día").
     *
     * @param filePath       Ruta al archivo planes_vuelo.txt
     * @param simulationDays Número de días de simulación
     * @return Lista de todos los vuelos (para todos los días)
     * @throws IOException Si el archivo no puede leerse
     */
    public List<Flight> parse(String filePath, int simulationDays) throws IOException {
        List<int[]> rawFlights = new ArrayList<>(); // [depMinute, arrMinute, capacity]
        List<String[]> rawCodes = new ArrayList<>(); // [origin, dest]

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;

                Matcher m = FLIGHT_PATTERN.matcher(line);
                if (!m.matches()) {
                    // Intento con formato alterno sin segundos separados (HD-MD en lugar de HD:MD)
                    // Intentar parseo flexible
                    Flight alt = tryAlternativeParse(line, 0);
                    if (alt != null) {
                        rawCodes.add(new String[]{alt.getOriginCode(), alt.getDestCode()});
                        rawFlights.add(new int[]{alt.getDepartureMinute(), alt.getArrivalMinute(), alt.getMaxCapacity()});
                    }
                    continue;
                }

                String orig     = m.group(1);
                String dest     = m.group(2);
                int    depHour  = Integer.parseInt(m.group(3));
                int    depMin   = Integer.parseInt(m.group(4));
                int    arrHour  = Integer.parseInt(m.group(5));
                int    arrMin   = Integer.parseInt(m.group(6));
                int    capacity = Integer.parseInt(m.group(7));

                int depMinutes = depHour * 60 + depMin;
                int arrMinutes = arrHour * 60 + arrMin;

                // Si llegada < salida, el vuelo cruza medianoche → suma un día
                if (arrMinutes <= depMinutes) {
                    arrMinutes += 1440;
                }

                rawCodes.add(new String[]{orig, dest});
                rawFlights.add(new int[]{depMinutes, arrMinutes, capacity});
            }
        }

        // Generar vuelos para cada día de simulación
        List<Flight> allFlights = new ArrayList<>();
        for (int day = 0; day < simulationDays; day++) {
            for (int i = 0; i < rawFlights.size(); i++) {
                int[]    times = rawFlights.get(i);
                String[] codes = rawCodes.get(i);
                allFlights.add(new Flight(
                        codes[0], codes[1],
                        times[0], times[1],
                        times[2], day
                ));
            }
        }

        System.out.printf("[FlightPlanParser] Cargados %d vuelos base, %d vuelos totales para %d dia(s).%n",
                rawFlights.size(), allFlights.size(), simulationDays);
        return allFlights;
    }

    /**
     * Intenta parsear formatos alternativos del archivo de vuelos.
     * Maneja el caso donde el separador entre hora y minuto de llegada
     * podría ser diferente, o donde hay espacios adicionales.
     *
     * Formato alternativo soportado:
     *   ORIG-DEST-HH:MM-HH:MM ####   (capacidad separada por espacio)
     *
     * @param line      Línea a parsear
     * @param dayOffset Día de simulación
     * @return Flight si se puede parsear, null en caso contrario
     */
    private Flight tryAlternativeParse(String line, int dayOffset) {
        try {
            // Formato: SKBO-SEQM-03:34-04:21 0300  (capacidad separada por espacio en lugar de -)
            Pattern alt = Pattern.compile(
                    "^([A-Z]{4})-([A-Z]{4})-(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})\\s+(\\d+)\\s*$"
            );
            Matcher m = alt.matcher(line);
            if (m.matches()) {
                int depMin = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
                int arrMin = Integer.parseInt(m.group(5)) * 60 + Integer.parseInt(m.group(6));
                int cap    = Integer.parseInt(m.group(7));
                if (arrMin <= depMin) arrMin += 1440;
                return new Flight(m.group(1), m.group(2), depMin, arrMin, cap, dayOffset);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Carga solo los vuelos de los primeros N días (batch inicial).
     * El planificador puede solicitar más días si los vuelos del batch no alcanzan.
     *
     * @param filePath       Ruta al archivo
     * @param fromDay        Día de inicio (inclusive)
     * @param toDay          Día de fin (inclusive)
     * @return Lista de vuelos del rango de días solicitado
     * @throws IOException Si el archivo no puede leerse
     */
    public List<Flight> parseDayRange(String filePath, int fromDay, int toDay) throws IOException {
        List<Flight> all = parse(filePath, toDay + 1);
        List<Flight> range = new ArrayList<>();
        for (Flight f : all) {
            if (f.getDayOffset() >= fromDay && f.getDayOffset() <= toDay) {
                range.add(f);
            }
        }
        return range;
    }
}
