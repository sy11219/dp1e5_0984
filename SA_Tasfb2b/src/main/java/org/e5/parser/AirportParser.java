package org.e5.parser;

import org.e5.model.Airport;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser del archivo aeropuertos.txt.
 *
 * Lee y transforma las líneas del archivo en objetos Airport.
 *
 * Formato esperado de cada línea de aeropuerto:
 *   NN   CODE   Ciudad   País   alias   GMT   CAPACIDAD   Latitude: ...   Longitude: ...
 *
 * Ejemplo:
 *   01   SKBO   Bogota   Colombia   bogo   -5   430   Latitude: 04° 42' 05" N   Longitude:  74° 08' 49" W
 *
 * El parser:
 * - Descarta líneas de cabecera y separadores (líneas con ****...)
 * - Detecta el continente actual según las líneas de sección ("America del Sur", "Europa", "Asia")
 * - Extrae coordenadas en formato grados/minutos/segundos y las convierte a decimal
 * - Extrae el GMT offset (con signo)
 */
public class AirportParser {

    // Archivo por defecto dentro de la carpeta data/
    private static final String DEFAULT_PATH = "data/aeropuertos.txt";

    // Regex para detectar una línea de aeropuerto válida:
    // Número de índice + código ICAO de 4 letras + resto de campos
    // Ejemplo: "01   SKBO   Bogota   Colombia   bogo   -5   430   Latitude: ..."
    private static final Pattern AIRPORT_LINE = Pattern.compile(
            "^\\s*\\d+\\s+([A-Z]{4})\\s+(\\S.*?)\\s{2,}(\\S+)\\s{2,}(\\w+)\\s+([+-]?\\d+)\\s+(\\d+)\\s+.*$"
    );

    // Regex para extraer latitud y longitud
    // Formato: "Latitude: 04° 42' 05" N"  /  "Longitude:  74° 08' 49" W"
    private static final Pattern LAT_PATTERN = Pattern.compile(
            "Latitude:\\s*(\\d+)[°º]\\s*(\\d+)'\\s*([\\d.]+)\"\\s*([NS])"
    );
    private static final Pattern LON_PATTERN = Pattern.compile(
            "Longitude:\\s*(\\d+)[°º]\\s*(\\d+)'\\s*([\\d.]+)\"\\s*([EW])"
    );

    /**
     * Carga aeropuertos desde la ruta por defecto (data/aeropuertos.txt).
     *
     * @return Lista de aeropuertos parseados
     * @throws IOException Si el archivo no puede leerse
     */
    public List<Airport> parse() throws IOException {
        return parse(DEFAULT_PATH);
    }

    /**
     * Carga aeropuertos desde una ruta de archivo específica.
     *
     * Algoritmo:
     * 1. Lee línea por línea
     * 2. Detecta cambios de continente por palabras clave en cabeceras de sección
     * 3. Salta líneas de separador (****) y líneas de cabecera sin código ICAO
     * 4. Para cada línea de aeropuerto extrae todos los campos y crea un Airport
     *
     * @param filePath Ruta al archivo aeropuertos.txt
     * @return Lista de aeropuertos parseados
     * @throws IOException Si el archivo no puede leerse
     */
    public List<Airport> parse(String filePath) throws IOException {
        List<Airport> airports = new ArrayList<>();
        String currentContinent = "Desconocido";

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_16))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();

                // Saltar líneas vacías
                if (trimmed.isEmpty()) continue;

                // Saltar líneas de separador de asteriscos
                if (trimmed.startsWith("*")) continue;

                // Saltar línea de cabecera del archivo (empieza con PDDS)
                if (trimmed.startsWith("PDDS")) continue;

                // Detectar cambio de continente
                // Las líneas de sección son como: "       America del Sur."  o "       Europa"
                String upperTrimmed = trimmed.toUpperCase();
                if (upperTrimmed.contains("AMERICA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "America";
                    continue;
                }
                if (upperTrimmed.contains("EUROPA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "Europa";
                    continue;
                }
                if (upperTrimmed.contains("ASIA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "Asia";
                    continue;
                }

                // Intentar parsear como línea de aeropuerto
                Airport airport = parseAirportLine(trimmed, currentContinent, line);
                if (airport != null) {
                    airports.add(airport);
                }
            }
        }

        System.out.printf("[AirportParser] Cargados %d aeropuertos.%n", airports.size());
        return airports;
    }

    /**
     * Verifica heurísticamente si una línea parece ser una entrada de aeropuerto
     * (contiene un código ICAO de 4 letras mayúsculas después del número de índice).
     *
     * @param line Línea a evaluar
     * @return true si parece una línea de aeropuerto
     */
    private boolean looksLikeAirportLine(String line) {
        return line.matches("^\\s*\\d+\\s+[A-Z]{4}\\s+.*");
    }

    /**
     * Parsea una sola línea de aeropuerto y retorna un objeto Airport.
     *
     * Extrae:
     * - Código ICAO (posición 2 del split)
     * - Nombre de ciudad y país (campos variables)
     * - GMT offset (campo antes de la capacidad)
     * - Capacidad (campo numérico entero)
     * - Latitud y longitud (de subcadenas con "Latitude:" y "Longitude:")
     *
     * @param trimmedLine Línea sin espacios iniciales/finales
     * @param continent   Continente detectado en la sección anterior
     * @param originalLine Línea original (para extracción de lat/lon con mayor contexto)
     * @return Airport parseado, o null si la línea no corresponde a un aeropuerto
     */
    private Airport parseAirportLine(String trimmedLine, String continent, String originalLine) {
        // Verificar que la línea empiece con número + código ICAO
        if (!looksLikeAirportLine(trimmedLine)) return null;

        try {
            // Dividir por múltiples espacios
            String[] parts = trimmedLine.split("\\s{2,}");
            // Esperamos al menos: "NN CODE Ciudad País alias GMT CAPACIDAD Latitude:... Longitude:..."
            // Pero los nombres de ciudad/país pueden tener espacios simples, así que usamos la posición

            // El token 0 es el número de índice
            // El token 1 es el código ICAO
            // Luego ciudad, país, alias, GMT, capacidad (variables por espacios dobles)

            if (parts.length < 5) return null;

            // Extraer código ICAO: primer token de 4 letras mayúsculas
            String code = null;
            String city = null;
            String country = null;
            int gmtOffset = 0;
            int capacity = 0;

            // Parsear manualmente: sabemos que el código ICAO siempre tiene 4 chars mayúsculas
            // y es el segundo token separado por espacios múltiples
            String[] tokens = trimmedLine.split("\\s+");
            // tokens[0] = número, tokens[1] = ICAO code
            if (tokens.length < 6) return null;
            code = tokens[1];
            if (!code.matches("[A-Z]{4}")) return null;

            // Buscar GMT y capacidad: son los últimos campos numéricos antes de "Latitude:"
            // Extraer la porción antes de "Latitude:"
            int latIdx = originalLine.indexOf("Latitude:");
            if (latIdx < 0) return null;

            String beforeLat = originalLine.substring(0, latIdx).trim();
            String[] mainParts = beforeLat.split("\\s{2,}");
            // mainParts structure: [index, CODE, city, country, alias, GMT, CAPACITY]
            if (mainParts.length < 5) return null;

            // El último campo es la capacidad
            capacity = Integer.parseInt(mainParts[mainParts.length - 1].trim());

            // El campo antes de capacidad es el GMT
            String gmtStr = mainParts[mainParts.length - 2].trim();
            gmtOffset = Integer.parseInt(gmtStr);

            // Ciudad: mainParts[2], País: mainParts[3]
            city    = mainParts.length > 2 ? mainParts[2].trim() : "Desconocido";
            country = mainParts.length > 3 ? mainParts[3].trim() : "Desconocido";

            // Extraer latitud y longitud del texto original
            double latitude  = parseLatitude(originalLine);
            double longitude = parseLongitude(originalLine);

            return new Airport(code, city, country, continent,
                    capacity, latitude, longitude, gmtOffset);

        } catch (Exception e) {
            // Si cualquier campo falla, ignoramos la línea silenciosamente
            // (puede ser una línea de formato diferente)
            return null;
        }
    }

    /**
     * Extrae la latitud decimal de una cadena con formato:
     * "Latitude: 04° 42' 05" N"
     *
     * Convierte grados/minutos/segundos a decimal:
     * decimal = grados + minutos/60 + segundos/3600
     * Negativo si es S (sur)
     *
     * @param line Línea completa del aeropuerto
     * @return Latitud en grados decimales, 0.0 si no se encuentra
     */
    private double parseLatitude(String line) {
        Matcher m = LAT_PATTERN.matcher(line);
        if (!m.find()) return 0.0;
        double deg = Double.parseDouble(m.group(1));
        double min = Double.parseDouble(m.group(2));
        double sec = Double.parseDouble(m.group(3));
        double decimal = deg + min / 60.0 + sec / 3600.0;
        return m.group(4).equalsIgnoreCase("S") ? -decimal : decimal;
    }

    /**
     * Extrae la longitud decimal de una cadena con formato:
     * "Longitude:  74° 08' 49" W"
     *
     * Convierte grados/minutos/segundos a decimal:
     * decimal = grados + minutos/60 + segundos/3600
     * Negativo si es W (oeste)
     *
     * @param line Línea completa del aeropuerto
     * @return Longitud en grados decimales, 0.0 si no se encuentra
     */
    private double parseLongitude(String line) {
        Matcher m = LON_PATTERN.matcher(line);
        if (!m.find()) return 0.0;
        double deg = Double.parseDouble(m.group(1));
        double min = Double.parseDouble(m.group(2));
        double sec = Double.parseDouble(m.group(3));
        double decimal = deg + min / 60.0 + sec / 3600.0;
        return m.group(4).equalsIgnoreCase("W") ? -decimal : decimal;
    }
}
