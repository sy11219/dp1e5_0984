package org.e5.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

public class FlightPlanService {

    public void cancelFlight(String flightCode) throws SQLException {
        updateStatus(flightCode, "CANCELED");
    }

    public void updateStatus(String flightCode, String status) throws SQLException {
        if (flightCode == null || flightCode.isBlank()) {
            throw new IllegalArgumentException("Codigo de vuelo invalido.");
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE flight_plans
                     SET status = ?
                     WHERE flight_code = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, flightCode.trim());
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("Vuelo no encontrado en BD: " + flightCode);
            }
        }
    }

    public String listFlightsJson() throws SQLException {
        StringBuilder json = new StringBuilder(64 * 1024);
        json.append("[");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT fp.flight_code,
                            oa.code AS origin_code,
                            da.code AS destination_code,
                            fp.departure_time_utc,
                            fp.arrival_time_utc,
                            fp.capacity,
                            fp.status
                     FROM flight_plans fp
                     JOIN airports oa ON oa.id = fp.origin_airport_id
                     JOIN airports da ON da.id = fp.destination_airport_id
                     ORDER BY fp.departure_time_utc, fp.flight_code
                     """);
             ResultSet result = statement.executeQuery()) {

            Instant baseInstant = null;
            boolean first = true;
            while (result.next()) {
                Instant departure = result.getTimestamp("departure_time_utc").toInstant();
                Instant arrival = result.getTimestamp("arrival_time_utc").toInstant();
                if (baseInstant == null) {
                    LocalDate baseDate = departure.atZone(ZoneOffset.UTC).toLocalDate();
                    baseInstant = baseDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                }

                int absoluteDeparture = minutesBetween(baseInstant, departure);
                int absoluteArrival = minutesBetween(baseInstant, arrival);
                int dayOffset = Math.floorDiv(absoluteDeparture, 1440);
                int departureMinute = Math.floorMod(absoluteDeparture, 1440);
                int arrivalMinute = absoluteArrival - (dayOffset * 1440);
                int capacity = result.getInt("capacity");

                if (!first) {
                    json.append(",");
                }
                first = false;

                json.append("{");
                prop(json, "id", result.getString("flight_code")).append(",");
                prop(json, "origin", result.getString("origin_code")).append(",");
                prop(json, "destination", result.getString("destination_code")).append(",");
                prop(json, "dayOffset", dayOffset).append(",");
                prop(json, "departureMinute", departureMinute).append(",");
                prop(json, "arrivalMinute", arrivalMinute).append(",");
                prop(json, "absoluteDepartureMinute", absoluteDeparture).append(",");
                prop(json, "absoluteArrivalMinute", absoluteArrival).append(",");
                prop(json, "assignedLoad", 0).append(",");
                prop(json, "maxCapacity", capacity).append(",");
                prop(json, "utilization", 0.0).append(",");
                prop(json, "status", "green").append(",");
                prop(json, "scheduleStatus", result.getString("status"));
                json.append("}");
            }
        }

        json.append("]");
        return json.toString();
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

    private int minutesBetween(Instant baseInstant, Instant value) {
        return Math.toIntExact(Duration.between(baseInstant, value).toMinutes());
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

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
