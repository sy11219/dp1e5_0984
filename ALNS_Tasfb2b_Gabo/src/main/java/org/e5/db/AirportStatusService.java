package org.e5.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

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
            throw new IllegalArgumentException("Codigo de aeropuerto invalido.");
        }
        return code.toUpperCase(Locale.ROOT);
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
}
