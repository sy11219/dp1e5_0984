package org.e5.parser;

import org.e5.model.Airport;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga aeropuertos del sistema.
 *
 * El backend usa parse() para leer desde PostgreSQL/RDS. La sobrecarga
 * parse(String) se mantiene como lector legado de data/aeropuertos.txt para
 * scripts o pruebas locales que aun dependan del archivo.
 */
public class AirportParser {

    private static final Pattern LAT_PATTERN = Pattern.compile(
            "Latitude:\\s*(\\d+)[°º]\\s*(\\d+)'\\s*([\\d.]+)\"\\s*([NS])"
    );
    private static final Pattern LON_PATTERN = Pattern.compile(
            "Longitude:\\s*(\\d+)[°º]\\s*(\\d+)'\\s*([\\d.]+)\"\\s*([EW])"
    );
    private static final Pattern UTC_OFFSET_PATTERN = Pattern.compile("([+-])(\\d{1,2})(?::\\d{2})?");

    /**
     * Carga aeropuertos activos desde la tabla airports.
     *
     * Variables de entorno requeridas:
     * - DB_URL: jdbc:postgresql://host:5432/tasf_b2b?sslmode=require
     * - DB_USER
     * - DB_PASSWORD
     */
    public List<Airport> parse() throws IOException {
        List<Airport> airports = new ArrayList<>();

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IOException("No se encontro el driver JDBC de PostgreSQL.", e);
        }

        String sql = """
                SELECT code, city, country, continent, warehouse_capacity,
                       latitude, longitude, timezone
                FROM airports
                WHERE status IS NULL OR UPPER(status) = 'ACTIVE'
                ORDER BY code
                """;

        try (Connection connection = DriverManager.getConnection(
                    requireEnv("DB_URL"),
                    requireEnv("DB_USER"),
                    requireEnv("DB_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                airports.add(new Airport(
                        result.getString("code"),
                        result.getString("city"),
                        result.getString("country"),
                        result.getString("continent"),
                        result.getInt("warehouse_capacity"),
                        result.getDouble("latitude"),
                        result.getDouble("longitude"),
                        parseGmtOffset(result.getString("timezone"))
                ));
            }
        } catch (SQLException e) {
            throw new IOException("No se pudieron cargar aeropuertos desde la base de datos.", e);
        }

        System.out.printf("[AirportParser] Cargados %d aeropuertos desde BD.%n", airports.size());
        return airports;
    }

    /**
     * Lector legado del archivo aeropuertos.txt.
     */
    public List<Airport> parse(String filePath) throws IOException {
        List<Airport> airports = new ArrayList<>();
        String currentContinent = "Desconocido";

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_16))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("PDDS")) {
                    continue;
                }

                String upper = trimmed.toUpperCase();
                if (upper.contains("AMERICA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "America";
                    continue;
                }
                if (upper.contains("EUROPA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "Europa";
                    continue;
                }
                if (upper.contains("ASIA") && !looksLikeAirportLine(trimmed)) {
                    currentContinent = "Asia";
                    continue;
                }

                Airport airport = parseAirportLine(trimmed, currentContinent, line);
                if (airport != null) {
                    airports.add(airport);
                }
            }
        }

        System.out.printf("[AirportParser] Cargados %d aeropuertos desde archivo.%n", airports.size());
        return airports;
    }

    private String requireEnv(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Falta variable de entorno requerida: " + name);
        }
        return value;
    }

    private int parseGmtOffset(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return 0;
        }

        String normalized = timezone.trim().toUpperCase();
        if (normalized.startsWith("UTC") || normalized.startsWith("GMT")) {
            normalized = normalized.substring(3);
        }

        if (normalized.matches("[+-]?\\d+")) {
            return Integer.parseInt(normalized);
        }

        Matcher matcher = UTC_OFFSET_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int sign = matcher.group(1).equals("-") ? -1 : 1;
            return sign * Integer.parseInt(matcher.group(2));
        }

        return 0;
    }

    private boolean looksLikeAirportLine(String line) {
        return line.matches("^\\s*\\d+\\s+[A-Z]{4}\\s+.*");
    }

    private Airport parseAirportLine(String trimmedLine, String continent, String originalLine) {
        if (!looksLikeAirportLine(trimmedLine)) return null;

        try {
            int latIdx = originalLine.indexOf("Latitude:");
            if (latIdx < 0) return null;

            String beforeLat = originalLine.substring(0, latIdx).trim();
            String[] mainParts = beforeLat.split("\\s{2,}");
            if (mainParts.length < 7) return null;

            String code = mainParts[1].trim();
            if (!code.matches("[A-Z]{4}")) return null;

            String city = mainParts[2].trim();
            String country = mainParts[3].trim();
            int gmtOffset = Integer.parseInt(mainParts[mainParts.length - 2].trim());
            int capacity = Integer.parseInt(mainParts[mainParts.length - 1].trim());
            double latitude = parseLatitude(originalLine);
            double longitude = parseLongitude(originalLine);

            return new Airport(code, city, country, continent, capacity, latitude, longitude, gmtOffset);
        } catch (Exception e) {
            return null;
        }
    }

    private double parseLatitude(String line) {
        Matcher m = LAT_PATTERN.matcher(line);
        if (!m.find()) return 0.0;
        double deg = Double.parseDouble(m.group(1));
        double min = Double.parseDouble(m.group(2));
        double sec = Double.parseDouble(m.group(3));
        double decimal = deg + min / 60.0 + sec / 3600.0;
        return m.group(4).equalsIgnoreCase("S") ? -decimal : decimal;
    }

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
