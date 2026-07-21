package org.e5.db;

import org.e5.util.AirportTimeZones;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FlightPlanService {
    private static final DateTimeFormatter FLIGHT_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern BATCH_FLIGHT_LINE = Pattern.compile(
            "^([A-Za-z]{4})-([A-Za-z]{4})-(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})-(\\d{4})\\s*$"
    );

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

    public String updateFlight(String flightCode, FlightPlanUpdate update) throws SQLException {
        String normalizedCode = normalizeFlightCode(flightCode);
        validatePersistedFlight(update);

        try (Connection connection = openConnection()) {
            String originCode = existingOriginAirportCode(connection, normalizedCode);
            String destinationCode = normalizeAirportCode(update.destinationAirportCode());
            UUID originId = findAirportId(connection, originCode);
            UUID destinationId = findAirportId(connection, destinationCode);
            ZoneId originZone = findAirportZone(connection, originCode);
            ZoneId destinationZone = findAirportZone(connection, destinationCode);

            if (originId == null) {
                throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + originCode);
            }
            if (destinationId == null) {
                throw new IllegalArgumentException("Aeropuerto destino no encontrado: " + destinationCode);
            }
            if (originZone == null) {
                throw new IllegalArgumentException("Timezone de aeropuerto origen no encontrado: " + originCode);
            }
            if (destinationZone == null) {
                throw new IllegalArgumentException("Timezone de aeropuerto destino no encontrado: " + destinationCode);
            }

            LocalDateTime departureLocal = parseLocalDateTime(update.departureTimeLocal(), "SALIDA_LOCAL");
            LocalDateTime arrivalLocal = parseLocalDateTime(update.arrivalTimeLocal(), "LLEGADA_LOCAL");
            OffsetDateTime departureUtc = toUtc(departureLocal, originZone);
            OffsetDateTime arrivalUtc = toUtc(arrivalLocal, destinationZone);
            String updatedFlightCode = nextAvailableFlightCode(
                    connection, buildFlightCode(originCode, destinationCode, departureUtc), normalizedCode);

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE flight_plans
                    SET flight_code = ?,
                        origin_airport_id = ?,
                        destination_airport_id = ?,
                        departure_time_local = ?,
                        arrival_time_local = ?,
                        departure_time_utc = ?,
                        arrival_time_utc = ?,
                        capacity = ?,
                        status = ?
                    WHERE flight_code = ?
                    """)) {
                statement.setString(1, updatedFlightCode);
                statement.setObject(2, originId);
                statement.setObject(3, destinationId);
                statement.setTimestamp(4, Timestamp.valueOf(departureLocal));
                statement.setTimestamp(5, Timestamp.valueOf(arrivalLocal));
                statement.setObject(6, departureUtc);
                statement.setObject(7, arrivalUtc);
                statement.setInt(8, update.capacity());
                statement.setString(9, normalizeStatus(update.status()));
                statement.setString(10, normalizedCode);

                int updated = statement.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("Vuelo no encontrado en BD: " + normalizedCode);
                }
            }

            return getFlightJson(connection, updatedFlightCode);
        }
    }

    public String createFlight(FlightPlanUpdate update) throws SQLException {
        validateCreate(update);

        try (Connection connection = openConnection()) {
            String originCode = normalizeAirportCode(update.originAirportCode());
            String destinationCode = normalizeAirportCode(update.destinationAirportCode());
            UUID originId = findAirportId(connection, originCode);
            UUID destinationId = findAirportId(connection, destinationCode);
            ZoneId originZone = findAirportZone(connection, originCode);
            ZoneId destinationZone = findAirportZone(connection, destinationCode);

            if (originId == null) {
                throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + originCode);
            }
            if (destinationId == null) {
                throw new IllegalArgumentException("Aeropuerto destino no encontrado: " + destinationCode);
            }
            if (originZone == null) {
                throw new IllegalArgumentException("Timezone de aeropuerto origen no encontrado: " + originCode);
            }
            if (destinationZone == null) {
                throw new IllegalArgumentException("Timezone de aeropuerto destino no encontrado: " + destinationCode);
            }

            LocalDateTime departureLocal = parseLocalDateTime(update.departureTimeLocal(), "SALIDA_LOCAL");
            LocalDateTime arrivalLocal = parseLocalDateTime(update.arrivalTimeLocal(), "LLEGADA_LOCAL");
            OffsetDateTime departureUtc = toUtc(departureLocal, originZone);
            OffsetDateTime arrivalUtc = toUtc(arrivalLocal, destinationZone);
            String flightCode = nextAvailableFlightCode(
                    connection, buildFlightCode(originCode, destinationCode, departureUtc), null);

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO flight_plans (
                      id, flight_code, origin_airport_id, destination_airport_id,
                      departure_time_local, arrival_time_local,
                      departure_time_utc, arrival_time_utc,
                      capacity, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, flightCode);
                statement.setObject(3, originId);
                statement.setObject(4, destinationId);
                statement.setTimestamp(5, Timestamp.valueOf(departureLocal));
                statement.setTimestamp(6, Timestamp.valueOf(arrivalLocal));
                statement.setObject(7, departureUtc);
                statement.setObject(8, arrivalUtc);
                statement.setInt(9, update.capacity());
                statement.executeUpdate();
            }

            return getFlightJson(connection, flightCode);
        }
    }

    /**
     * Importa el bloque de planes adicionales con el formato indicado en el
     * curso. Se persiste en el catálogo común: los tres escenarios lo leen al
     * iniciar una nueva sesión. La fecha ancla la conversión de local a UTC;
     * el motor mantiene los vuelos como planes recurrentes diarios.
     */
    public String createFlightsBatch(FlightPlanBatchCreateRequest request) throws SQLException {
        if (request == null || request.fileContent() == null || request.fileContent().isBlank()) {
            throw new IllegalArgumentException("El archivo de planes de vuelo esta vacio.");
        }
        LocalDate scheduleDate = parseScheduleDate(request.scheduleDate());
        List<FlightPlanLine> plans = parseBatchLines(request.fileContent());

        int inserted = 0;
        int skipped = 0;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (FlightPlanLine plan : plans) {
                    AirportReference origin = findAirportReference(connection, plan.originCode());
                    AirportReference destination = findAirportReference(connection, plan.destinationCode());
                    if (origin == null) {
                        throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + plan.originCode());
                    }
                    if (destination == null) {
                        throw new IllegalArgumentException("Aeropuerto destino no encontrado: " + plan.destinationCode());
                    }

                    LocalDateTime departureLocal = LocalDateTime.of(scheduleDate, plan.departureTime());
                    OffsetDateTime departureUtc = toUtc(departureLocal, origin.zone());
                    LocalDate arrivalDate = scheduleDate;
                    OffsetDateTime arrivalUtc = toUtc(
                            LocalDateTime.of(arrivalDate, plan.arrivalTime()), destination.zone());
                    while (!arrivalUtc.isAfter(departureUtc)) {
                        arrivalDate = arrivalDate.plusDays(1);
                        arrivalUtc = toUtc(
                                LocalDateTime.of(arrivalDate, plan.arrivalTime()), destination.zone());
                    }
                    LocalDateTime arrivalLocal = LocalDateTime.of(arrivalDate, plan.arrivalTime());
                    String flightCode = nextAvailableFlightCode(
                            connection, buildFlightCode(plan.originCode(), plan.destinationCode(), departureUtc), null);

                    insertFlight(connection, flightCode, origin.id(), destination.id(), departureLocal,
                            arrivalLocal, departureUtc, arrivalUtc, plan.capacity());
                    inserted++;
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        }
        return batchJson(plans.size(), inserted, skipped);
    }

    public String listFlightsJson() throws SQLException {
        StringBuilder json = new StringBuilder(64 * 1024);
        json.append("[");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT fp.flight_code,
                            fp.origin_airport_id,
                            fp.destination_airport_id,
                            oa.code AS origin_code,
                            da.code AS destination_code,
                            fp.departure_time_local,
                            fp.arrival_time_local,
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
                Instant departure = instant(result, "departure_time_utc");
                Instant arrival = instant(result, "arrival_time_utc");
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
                prop(json, "flight_code", result.getString("flight_code")).append(",");
                prop(json, "origin", result.getString("origin_code")).append(",");
                prop(json, "origin_airport_id", result.getString("origin_airport_id")).append(",");
                prop(json, "destination", result.getString("destination_code")).append(",");
                prop(json, "destination_airport_id", result.getString("destination_airport_id")).append(",");
                prop(json, "departure_time_local", localDateTime(result, "departure_time_local").toString()).append(",");
                prop(json, "arrival_time_local", localDateTime(result, "arrival_time_local").toString()).append(",");
                prop(json, "departure_time_utc", departure.toString()).append(",");
                prop(json, "arrival_time_utc", arrival.toString()).append(",");
                prop(json, "capacity", capacity).append(",");
                prop(json, "dayOffset", dayOffset).append(",");
                prop(json, "departureMinute", departureMinute).append(",");
                prop(json, "arrivalMinute", arrivalMinute).append(",");
                prop(json, "absoluteDepartureMinute", absoluteDeparture).append(",");
                prop(json, "absoluteArrivalMinute", absoluteArrival).append(",");
                prop(json, "assignedLoad", 0).append(",");
                prop(json, "maxCapacity", capacity).append(",");
                prop(json, "utilization", 0.0).append(",");
                prop(json, "status", "green").append(",");
                prop(json, "flightStatus", result.getString("status")).append(",");
                prop(json, "scheduleStatus", result.getString("status"));
                json.append("}");
            }
        }

        json.append("]");
        return json.toString();
    }

    private String getFlightJson(Connection connection, String flightCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fp.flight_code,
                       fp.origin_airport_id,
                       fp.destination_airport_id,
                       oa.code AS origin_code,
                       da.code AS destination_code,
                       fp.departure_time_local,
                       fp.arrival_time_local,
                       fp.departure_time_utc,
                       fp.arrival_time_utc,
                       fp.capacity,
                       fp.status
                FROM flight_plans fp
                JOIN airports oa ON oa.id = fp.origin_airport_id
                JOIN airports da ON da.id = fp.destination_airport_id
                WHERE fp.flight_code = ?
                """)) {
            statement.setString(1, flightCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Vuelo no encontrado en BD: " + flightCode);
                }
                Instant departure = instant(result, "departure_time_utc");
                LocalDate baseDate = departure.atZone(ZoneOffset.UTC).toLocalDate();
                Instant baseInstant = baseDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                return flightJson(result, baseInstant);
            }
        }
    }

    private String flightJson(ResultSet result, Instant baseInstant) throws SQLException {
        Instant departure = instant(result, "departure_time_utc");
        Instant arrival = instant(result, "arrival_time_utc");
        int absoluteDeparture = minutesBetween(baseInstant, departure);
        int absoluteArrival = minutesBetween(baseInstant, arrival);
        int dayOffset = Math.floorDiv(absoluteDeparture, 1440);
        int departureMinute = Math.floorMod(absoluteDeparture, 1440);
        int arrivalMinute = absoluteArrival - (dayOffset * 1440);
        int capacity = result.getInt("capacity");
        StringBuilder json = new StringBuilder(1024);

        json.append("{");
        prop(json, "id", result.getString("flight_code")).append(",");
        prop(json, "flight_code", result.getString("flight_code")).append(",");
        prop(json, "origin", result.getString("origin_code")).append(",");
        prop(json, "origin_airport_id", result.getString("origin_airport_id")).append(",");
        prop(json, "destination", result.getString("destination_code")).append(",");
        prop(json, "destination_airport_id", result.getString("destination_airport_id")).append(",");
        prop(json, "departure_time_local", localDateTime(result, "departure_time_local").toString()).append(",");
        prop(json, "arrival_time_local", localDateTime(result, "arrival_time_local").toString()).append(",");
        prop(json, "departure_time_utc", departure.toString()).append(",");
        prop(json, "arrival_time_utc", arrival.toString()).append(",");
        prop(json, "capacity", capacity).append(",");
        prop(json, "dayOffset", dayOffset).append(",");
        prop(json, "departureMinute", departureMinute).append(",");
        prop(json, "arrivalMinute", arrivalMinute).append(",");
        prop(json, "absoluteDepartureMinute", absoluteDeparture).append(",");
        prop(json, "absoluteArrivalMinute", absoluteArrival).append(",");
        prop(json, "assignedLoad", 0).append(",");
        prop(json, "maxCapacity", capacity).append(",");
        prop(json, "utilization", 0.0).append(",");
        prop(json, "status", "green").append(",");
        prop(json, "flightStatus", result.getString("status")).append(",");
        prop(json, "scheduleStatus", result.getString("status"));
        json.append("}");
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

    private UUID findAirportId(Connection connection, String code) throws SQLException {
        String normalizedCode = normalizeAirportCode(code);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM airports WHERE code = ?")) {
            statement.setString(1, normalizedCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? (UUID) result.getObject("id") : null;
            }
        }
    }

    private String existingOriginAirportCode(Connection connection, String flightCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.code
                FROM flight_plans fp
                JOIN airports a ON a.id = fp.origin_airport_id
                WHERE fp.flight_code = ?
                """)) {
            statement.setString(1, flightCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Vuelo no encontrado en BD: " + flightCode);
                }
                return result.getString("code");
            }
        }
    }

    private ZoneId findAirportZone(Connection connection, String code) throws SQLException {
        String normalizedCode = normalizeAirportCode(code);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, timezone FROM airports WHERE code = ?")) {
            statement.setString(1, normalizedCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? AirportTimeZones.resolve(result.getString("code"), result.getString("timezone"))
                        : null;
            }
        }
    }

    private AirportReference findAirportReference(Connection connection, String code) throws SQLException {
        String normalizedCode = normalizeAirportCode(code);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, timezone FROM airports WHERE code = ?")) {
            statement.setString(1, normalizedCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new AirportReference(
                        (UUID) result.getObject("id"),
                        AirportTimeZones.resolve(result.getString("code"), result.getString("timezone"))
                );
            }
        }
    }

    private boolean flightCodeExists(Connection connection, String flightCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM flight_plans WHERE flight_code = ?")) {
            statement.setString(1, flightCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Los planes pueden compartir la misma ruta y horario. Se conserva el
     * código horario como base y se agrega un sufijo estable si ya está ocupado.
     */
    private String nextAvailableFlightCode(
            Connection connection,
            String baseFlightCode,
            String currentFlightCode
    ) throws SQLException {
        if (baseFlightCode.equals(currentFlightCode) || !flightCodeExists(connection, baseFlightCode)) {
            return baseFlightCode;
        }
        for (int sequence = 2; sequence < 10_000; sequence++) {
            String candidate = baseFlightCode + "-" + String.format(Locale.ROOT, "%02d", sequence);
            if (candidate.equals(currentFlightCode) || !flightCodeExists(connection, candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No se pudo asignar un codigo unico al vuelo: " + baseFlightCode);
    }

    private void insertFlight(
            Connection connection,
            String flightCode,
            UUID originId,
            UUID destinationId,
            LocalDateTime departureLocal,
            LocalDateTime arrivalLocal,
            OffsetDateTime departureUtc,
            OffsetDateTime arrivalUtc,
            int capacity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO flight_plans (
                  id, flight_code, origin_airport_id, destination_airport_id,
                  departure_time_local, arrival_time_local,
                  departure_time_utc, arrival_time_utc,
                  capacity, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, flightCode);
            statement.setObject(3, originId);
            statement.setObject(4, destinationId);
            statement.setTimestamp(5, Timestamp.valueOf(departureLocal));
            statement.setTimestamp(6, Timestamp.valueOf(arrivalLocal));
            statement.setObject(7, departureUtc);
            statement.setObject(8, arrivalUtc);
            statement.setInt(9, capacity);
            statement.executeUpdate();
        }
    }

    private String buildFlightCode(
            String originCode,
            String destinationCode,
            OffsetDateTime departureUtc
    ) {
        OffsetDateTime utc = departureUtc.withOffsetSameInstant(ZoneOffset.UTC);
        return String.format(
                "%s-%s-%02d%02d",
                originCode,
                destinationCode,
                utc.getHour(),
                utc.getMinute()
        );
    }

    private void validateUpdate(FlightPlanUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Datos de vuelo invalidos.");
        }
        normalizeAirportCode(update.originAirportCode());
        normalizeAirportCode(update.destinationAirportCode());
        parseLocalDateTime(update.departureTimeLocal(), "SALIDA_LOCAL");
        parseLocalDateTime(update.arrivalTimeLocal(), "LLEGADA_LOCAL");
        parseUtcDateTime(update.departureTimeUtc(), "SALIDA_UTC");
        parseUtcDateTime(update.arrivalTimeUtc(), "LLEGADA_UTC");
        normalizeStatus(update.status());
        if (update.capacity() <= 0) {
            throw new IllegalArgumentException("Capacidad debe ser mayor a cero.");
        }
    }

    private void validateCreate(FlightPlanUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Datos de vuelo invalidos.");
        }
        normalizeAirportCode(update.originAirportCode());
        normalizeAirportCode(update.destinationAirportCode());
        parseLocalDateTime(update.departureTimeLocal(), "SALIDA_LOCAL");
        parseLocalDateTime(update.arrivalTimeLocal(), "LLEGADA_LOCAL");
        if (update.capacity() <= 0) {
            throw new IllegalArgumentException("Capacidad debe ser mayor a cero.");
        }
    }

    private void validatePersistedFlight(FlightPlanUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("Datos de vuelo invalidos.");
        }
        normalizeAirportCode(update.destinationAirportCode());
        parseLocalDateTime(update.departureTimeLocal(), "SALIDA_LOCAL");
        parseLocalDateTime(update.arrivalTimeLocal(), "LLEGADA_LOCAL");
        normalizeStatus(update.status());
        if (update.capacity() <= 0) {
            throw new IllegalArgumentException("Capacidad debe ser mayor a cero.");
        }
    }

    private String normalizeFlightCode(String flightCode) {
        if (flightCode == null || flightCode.isBlank()) {
            throw new IllegalArgumentException("Codigo de vuelo invalido.");
        }
        return flightCode.trim();
    }

    private String normalizeAirportCode(String code) {
        if (code == null || !code.matches("(?i)[A-Z]{4}")) {
            throw new IllegalArgumentException("Codigo de aeropuerto invalido.");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Status es obligatorio.");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("CANCELLED")) {
            return "CANCELED";
        }
        if (!normalized.equals("SCHEDULED") && !normalized.equals("CANCELED")) {
            throw new IllegalArgumentException("Status debe ser SCHEDULED o CANCELED.");
        }
        return normalized;
    }

    private LocalDateTime parseLocalDateTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " es obligatorio.");
        }
        return LocalDateTime.parse(value.trim());
    }

    private OffsetDateTime parseUtcDateTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " es obligatorio.");
        }
        String normalized = value.trim();
        if (normalized.endsWith("Z")) {
            return Instant.parse(normalized).atOffset(ZoneOffset.UTC);
        }
        return LocalDateTime.parse(normalized).atOffset(ZoneOffset.UTC);
    }

    private OffsetDateTime toUtc(LocalDateTime localDateTime, ZoneId zone) {
        return localDateTime.atZone(zone)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private LocalDate parseScheduleDate(String value) {
        if (value == null || value.isBlank()) return LocalDate.now(ZoneOffset.UTC);
        String normalized = value.trim().replace("-", "");
        if (!normalized.matches("\\d{8}")) {
            throw new IllegalArgumentException("La fecha de los vuelos debe tener formato aaaammdd.");
        }
        try {
            return LocalDate.parse(normalized, FLIGHT_DAY_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("La fecha de los vuelos no es valida.");
        }
    }

    private List<FlightPlanLine> parseBatchLines(String rawContent) {
        List<FlightPlanLine> plans = new ArrayList<>();
        for (String rawLine : rawContent.replace("\uFEFF", "").split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("**")) {
                continue;
            }

            Matcher matcher = BATCH_FLIGHT_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Linea de vuelo invalida: " + line
                        + ". Usa ORIG-DEST-HO:MO-HD:MD-####.");
            }
            String origin = normalizeAirportCode(matcher.group(1));
            String destination = normalizeAirportCode(matcher.group(2));
            int departureHour = Integer.parseInt(matcher.group(3));
            int departureMinute = Integer.parseInt(matcher.group(4));
            int arrivalHour = Integer.parseInt(matcher.group(5));
            int arrivalMinute = Integer.parseInt(matcher.group(6));
            int capacity = Integer.parseInt(matcher.group(7));
            try {
                plans.add(new FlightPlanLine(
                        origin,
                        destination,
                        LocalTime.of(departureHour, departureMinute),
                        LocalTime.of(arrivalHour, arrivalMinute),
                        capacity
                ));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Hora invalida en el vuelo: " + line);
            }
        }

        if (plans.isEmpty()) {
            throw new IllegalArgumentException("El archivo no contiene planes de vuelo validos.");
        }
        Set<FlightPlanLine> unique = new LinkedHashSet<>(plans);
        return new ArrayList<>(unique);
    }

    private String batchJson(int parsed, int inserted, int skipped) {
        return "{\"parsed\":" + parsed
                + ",\"inserted\":" + inserted
                + ",\"skipped\":" + skipped + "}";
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

    private Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private LocalDateTime localDateTime(ResultSet result, String column) throws SQLException {
        return result.getObject(column, LocalDateTime.class);
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

    public record FlightPlanUpdate(
            String originAirportCode,
            String destinationAirportCode,
            String departureTimeLocal,
            String arrivalTimeLocal,
            String departureTimeUtc,
            String arrivalTimeUtc,
            int capacity,
            String status
    ) {}

    public record FlightPlanBatchCreateRequest(String scheduleDate, String fileContent) {}

    private record AirportReference(UUID id, ZoneId zone) {}

    private record FlightPlanLine(
            String originCode,
            String destinationCode,
            LocalTime departureTime,
            LocalTime arrivalTime,
            int capacity
    ) {}
}
