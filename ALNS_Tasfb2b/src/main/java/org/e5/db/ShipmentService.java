package org.e5.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

public class ShipmentService {

    public String createShipment(ShipmentCreateRequest request) throws SQLException {
        validateRequest(request);
        String originCode = normalizeAirportCode(request.originAirportCode());
        String destinationCode = normalizeAirportCode(request.destinationAirportCode());
        String shipmentCode = originCode + "-" + normalizeShipmentId(request.shipmentId());
        OffsetDateTime departureDate = parseDateTime(request.departureDate());

        try (Connection connection = openConnection()) {
            UUID originId = findAirportId(connection, originCode);
            UUID destinationId = findAirportId(connection, destinationCode);

            if (originId == null) {
                throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + originCode);
            }
            if (destinationId == null) {
                throw new IllegalArgumentException("Aeropuerto destino no encontrado: " + destinationCode);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO shipments (
                      id, shipment_code, origin_airport_id, destination_airport_id,
                      baggage_count, registered_at, max_delivery_at, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'REGISTERED')
                    RETURNING shipment_code, baggage_count, registered_at, max_delivery_at, status
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, shipmentCode);
                statement.setObject(3, originId);
                statement.setObject(4, destinationId);
                statement.setInt(5, request.baggageCount());
                statement.setObject(6, departureDate);
                statement.setObject(7, departureDate);

                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return shipmentJson(result, originCode, destinationCode);
                }
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

    private UUID findAirportId(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM airports WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? (UUID) result.getObject("id") : null;
            }
        }
    }

    private void validateRequest(ShipmentCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Datos de envio invalidos.");
        }
        normalizeAirportCode(request.originAirportCode());
        normalizeAirportCode(request.destinationAirportCode());
        normalizeShipmentId(request.shipmentId());
        parseDateTime(request.departureDate());
        if (request.baggageCount() <= 0) {
            throw new IllegalArgumentException("La cantidad de maletas debe ser mayor a cero.");
        }
    }

    private String normalizeAirportCode(String code) {
        if (code == null || !code.matches("(?i)[A-Z]{4}")) {
            throw new IllegalArgumentException("Codigo de aeropuerto invalido.");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private String normalizeShipmentId(String shipmentId) {
        if (shipmentId == null || !shipmentId.matches("\\d{1,9}")) {
            throw new IllegalArgumentException("El id del envio debe tener hasta 9 digitos.");
        }
        return String.format("%09d", Integer.parseInt(shipmentId));
    }

    private OffsetDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La fecha de salida es obligatoria.");
        }
        String normalized = value.trim();
        if (normalized.endsWith("Z")) {
            return Instant.parse(normalized).atOffset(ZoneOffset.UTC);
        }
        return LocalDateTime.parse(normalized).atOffset(ZoneOffset.UTC);
    }

    private String shipmentJson(ResultSet result, String originCode, String destinationCode) throws SQLException {
        StringBuilder json = new StringBuilder(512);
        json.append("{");
        prop(json, "shipment_code", result.getString("shipment_code")).append(",");
        prop(json, "origin_airport_code", originCode).append(",");
        prop(json, "destination_airport_code", destinationCode).append(",");
        prop(json, "baggage_count", result.getInt("baggage_count")).append(",");
        prop(json, "registered_at", result.getObject("registered_at", OffsetDateTime.class).toString()).append(",");
        prop(json, "max_delivery_at", result.getObject("max_delivery_at", OffsetDateTime.class).toString()).append(",");
        prop(json, "status", result.getString("status"));
        json.append("}");
        return json.toString();
    }

    private StringBuilder prop(StringBuilder json, String name, String value) {
        return json.append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"');
    }

    private StringBuilder prop(StringBuilder json, String name, int value) {
        return json.append('"').append(escape(name)).append("\":").append(value);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ShipmentCreateRequest(
            String originAirportCode,
            String destinationAirportCode,
            String departureDate,
            int baggageCount,
            String shipmentId
    ) {}
}
