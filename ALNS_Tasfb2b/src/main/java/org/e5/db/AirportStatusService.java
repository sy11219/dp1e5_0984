package org.e5.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

public class AirportStatusService {

    public String listAirportsJson() throws SQLException {
        StringBuilder json = new StringBuilder(16 * 1024);
        json.append("[");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT code, city, country, continent, latitude, longitude,
                            timezone, warehouse_capacity, status
                     FROM airports
                     ORDER BY code
                     """);
             ResultSet result = statement.executeQuery()) {

            boolean first = true;
            while (result.next()) {
                if (!first) {
                    json.append(",");
                }
                first = false;

                String status = result.getString("status");
                boolean active = "ACTIVE".equalsIgnoreCase(status);

                json.append("{");
                prop(json, "code", result.getString("code")).append(",");
                prop(json, "city", result.getString("city")).append(",");
                prop(json, "country", result.getString("country")).append(",");
                prop(json, "continent", result.getString("continent")).append(",");
                prop(json, "latitude", result.getDouble("latitude")).append(",");
                prop(json, "longitude", result.getDouble("longitude")).append(",");
                prop(json, "gmtOffset", parseGmtOffset(result.getString("timezone"))).append(",");
                prop(json, "maxCapacity", result.getInt("warehouse_capacity")).append(",");
                prop(json, "peakLoad", 0).append(",");
                prop(json, "finalLoad", 0).append(",");
                prop(json, "utilization", 0.0).append(",");
                prop(json, "status", "green").append(",");
                prop(json, "operationalStatus", status == null ? "ACTIVE" : status).append(",");
                prop(json, "active", active);
                json.append("}");
            }
        }

        json.append("]");
        return json.toString();
    }

    public String updateAirport(String code, AirportUpdate update) throws SQLException {
        String normalizedCode = normalizeCode(code);
        validateAirportUpdate(update);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE airports
                     SET city = ?,
                         country = ?,
                         continent = ?,
                         status = ?,
                         latitude = ?,
                         longitude = ?,
                         timezone = ?,
                         warehouse_capacity = ?
                     WHERE code = ?
                     RETURNING code, city, country, continent, latitude, longitude,
                               timezone, warehouse_capacity, status
                     """)) {
            statement.setString(1, update.city().trim());
            statement.setString(2, update.country().trim());
            statement.setString(3, update.continent().trim());
            statement.setString(4, normalizeStatus(update.status()));
            statement.setDouble(5, update.latitude());
            statement.setDouble(6, update.longitude());
            statement.setString(7, formatTimezone(update.gmtOffset()));
            statement.setInt(8, update.maxCapacity());
            statement.setString(9, normalizedCode);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Aeropuerto no encontrado: " + normalizedCode);
                }
                return airportJson(result);
            }
        }
    }

    public String createAirport(String code, AirportUpdate update) throws SQLException {
        String normalizedCode = normalizeCode(code);
        validateAirportUpdate(update);

        try (Connection connection = openConnection()) {
            if (airportExists(connection, normalizedCode)) {
                throw new IllegalArgumentException("Ya existe un aeropuerto con código: " + normalizedCode);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO airports (
                       id, code, name, city, country, continent, latitude, longitude,
                       timezone, warehouse_capacity, status
                     )
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                     RETURNING code, city, country, continent, latitude, longitude,
                               timezone, warehouse_capacity, status
                     """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, normalizedCode);
                statement.setString(3, update.city().trim());
                statement.setString(4, update.city().trim());
                statement.setString(5, update.country().trim());
                statement.setString(6, update.continent().trim());
                statement.setDouble(7, update.latitude());
                statement.setDouble(8, update.longitude());
                statement.setString(9, formatTimezone(update.gmtOffset()));
                statement.setInt(10, update.maxCapacity());

                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return airportJson(result);
                }
            }
        }
    }

    public AirportStatus getStatus(String code) throws SQLException {
        String normalizedCode = normalizeCode(code);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT code, status FROM airports WHERE code = ?")) {
            statement.setString(1, normalizedCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Aeropuerto no encontrado: " + normalizedCode);
                }
                return new AirportStatus(result.getString("code"), result.getString("status"));
            }
        }
    }

    public AirportStatus updateStatus(String code, boolean active) throws SQLException {
        String normalizedCode = normalizeCode(code);
        String status = active ? "ACTIVE" : "INACTIVE";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE airports
                     SET status = ?
                     WHERE code = ?
                     RETURNING code, status
                     """)) {
            statement.setString(1, status);
            statement.setString(2, normalizedCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Aeropuerto no encontrado: " + normalizedCode);
                }
                return new AirportStatus(result.getString("code"), result.getString("status"));
            }
        }
    }

    private Connection openConnection() throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No se encontro el driver JDBC de PostgreSQL. Verifica la dependencia org.postgresql:postgresql en pom.xml.", e);
        }

        String url = requireEnv("DB_URL");
        String user = requireEnv("DB_USER");
        String password = requireEnv("DB_PASSWORD");
        return DriverManager.getConnection(url, user, password);
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta variable de entorno: " + name);
        }
        return value;
    }

    private String normalizeCode(String code) {
        if (code == null || !code.matches("(?i)[A-Z]{4}")) {
            throw new IllegalArgumentException("Código de aeropuerto inválido.");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private void validateAirportUpdate(AirportUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Datos de aeropuerto inválidos.");
        }
        requireText(update.city(), "Ciudad");
        requireText(update.country(), "País");
        requireText(update.continent(), "Continente");
        normalizeStatus(update.status());
        if (update.latitude() < -90 || update.latitude() > 90) {
            throw new IllegalArgumentException("Latitud fuera de rango.");
        }
        if (update.longitude() < -180 || update.longitude() > 180) {
            throw new IllegalArgumentException("Longitud fuera de rango.");
        }
        if (update.gmtOffset() < -12 || update.gmtOffset() > 14) {
            throw new IllegalArgumentException("GMT fuera de rango.");
        }
        if (update.maxCapacity() <= 0) {
            throw new IllegalArgumentException("Capacidad debe ser mayor a cero.");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio.");
        }
    }

    private boolean airportExists(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM airports WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException("Estado es obligatorio.");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("INACTIVE")) {
            throw new IllegalArgumentException("Estado debe ser ACTIVE o INACTIVE.");
        }
        return normalized;
    }

    private String formatTimezone(int gmtOffset) {
        return String.format(Locale.US, "UTC%+03d:00", gmtOffset);
    }

    private int parseGmtOffset(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return 0;
        }
        String normalized = timezone.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("UTC") || normalized.startsWith("GMT")) {
            normalized = normalized.substring(3);
        }
        if (normalized.matches("[+-]?\\d+")) {
            return Integer.parseInt(normalized);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([+-])(\\d{1,2})(?::\\d{2})?")
                .matcher(normalized);
        if (!matcher.matches()) {
            return 0;
        }
        int sign = matcher.group(1).equals("-") ? -1 : 1;
        return sign * Integer.parseInt(matcher.group(2));
    }

    private StringBuilder prop(StringBuilder json, String name, String value) {
        return json.append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"');
    }

    private StringBuilder prop(StringBuilder json, String name, int value) {
        return json.append('"').append(escape(name)).append("\":").append(value);
    }

    private StringBuilder prop(StringBuilder json, String name, double value) {
        return json.append('"').append(escape(name)).append("\":").append(String.format(Locale.US, "%.6f", value));
    }

    private StringBuilder prop(StringBuilder json, String name, boolean value) {
        return json.append('"').append(escape(name)).append("\":").append(value);
    }

    private String airportJson(ResultSet result) throws SQLException {
        String status = result.getString("status");
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        StringBuilder json = new StringBuilder(512);

        json.append("{");
        prop(json, "code", result.getString("code")).append(",");
        prop(json, "city", result.getString("city")).append(",");
        prop(json, "country", result.getString("country")).append(",");
        prop(json, "continent", result.getString("continent")).append(",");
        prop(json, "latitude", result.getDouble("latitude")).append(",");
        prop(json, "longitude", result.getDouble("longitude")).append(",");
        prop(json, "gmtOffset", parseGmtOffset(result.getString("timezone"))).append(",");
        prop(json, "maxCapacity", result.getInt("warehouse_capacity")).append(",");
        prop(json, "peakLoad", 0).append(",");
        prop(json, "finalLoad", 0).append(",");
        prop(json, "utilization", 0.0).append(",");
        prop(json, "status", "green").append(",");
        prop(json, "operationalStatus", status == null ? "ACTIVE" : status).append(",");
        prop(json, "active", active);
        json.append("}");

        return json.toString();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record AirportStatus(String code, String status) {
        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public String toJson() {
            return String.format(
                    "{\"code\":\"%s\",\"status\":\"%s\",\"active\":%s}",
                    code,
                    status,
                    active()
            );
        }
    }

    public record AirportUpdate(
            String city,
            String country,
            String continent,
            String status,
            double latitude,
            double longitude,
            int gmtOffset,
            int maxCapacity
    ) {}
}
