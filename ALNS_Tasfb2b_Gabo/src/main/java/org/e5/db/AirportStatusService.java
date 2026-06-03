package org.e5.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class AirportStatusService {

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
