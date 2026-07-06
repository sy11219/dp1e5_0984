package org.e5.db;

import org.e5.model.Airport;
import org.e5.model.Shipment;
import org.e5.parser.AirportParser;
import org.e5.parser.ShipmentParser;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShipmentService {
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneOffset LIST_TIME_ZONE = ZoneOffset.ofHours(-5);
    private static final Pattern SHIPMENT_LINE = Pattern.compile(
            "^(\\d{1,9})-(\\d{8})-(\\d{2})-(\\d{2})-([A-Za-z]{4})-(\\d{3})-(\\d{7})$"
    );
    private static final Pattern TZ_PATTERN = Pattern.compile("(?:UTC|GMT)?([+-])(\\d{1,2})(?::(\\d{2}))?");

    public String createShipment(ShipmentCreateRequest request) throws SQLException {
        validateRequest(request);
        String originCode = normalizeAirportCode(request.originAirportCode());
        String destinationCode = normalizeAirportCode(request.destinationAirportCode());
        String clientId = normalizeClientId(request.clientId());

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            AirportRef origin = findAirport(connection, originCode);
            AirportRef destination = findAirport(connection, destinationCode);

            if (origin == null) {
                throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + originCode);
            }
            if (destination == null) {
                throw new IllegalArgumentException("Aeropuerto destino no encontrado: " + destinationCode);
            }

            OffsetDateTime registeredAtUtc = parseDateTimeAtOrigin(request.departureDate(), origin.offset());
            OffsetDateTime maxDeliveryAt = registeredAtUtc.plusDays(deadlineDays(origin, destination));
            String shipmentCode = nextShipmentCode(connection, originCode);

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO shipments (
                      id, shipment_code, origin_airport_id, destination_airport_id,
                      baggage_count, registered_at, max_delivery_at, status, client_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'REGISTERED', ?)
                    RETURNING shipment_code, baggage_count, registered_at, max_delivery_at, status, client_id
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, shipmentCode);
                statement.setObject(3, origin.id());
                statement.setObject(4, destination.id());
                statement.setInt(5, request.baggageCount());
                statement.setObject(6, registeredAtUtc);
                statement.setObject(7, maxDeliveryAt);
                statement.setString(8, clientId);

                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    String json = shipmentJson(result, originCode, destinationCode);
                    connection.commit();
                    return json;
                }
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public String createShipmentsBatch(ShipmentBatchCreateRequest request) throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("Datos de lote invalidos.");
        }
        String originCode = normalizeAirportCode(request.originAirportCode());
        if (request.fileContent() == null || request.fileContent().isBlank()) {
            throw new IllegalArgumentException("El archivo de envios esta vacio.");
        }

        String[] lines = request.fileContent().split("\\R");
        int inserted = 0;
        int skipped = 0;
        int parsed = 0;

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            AirportRef origin = findAirport(connection, originCode);
            if (origin == null) {
                throw new IllegalArgumentException("Aeropuerto origen no encontrado: " + originCode);
            }

            Map<String, AirportRef> airportCache = new HashMap<>();
            airportCache.put(originCode, origin);

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO shipments (
                      id, shipment_code, origin_airport_id, destination_airport_id,
                      baggage_count, registered_at, max_delivery_at, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'REGISTERED')
                    ON CONFLICT (shipment_code) DO NOTHING
                    """)) {
                for (int index = 0; index < lines.length; index++) {
                    String line = lines[index].trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                        continue;
                    }

                    Matcher matcher = SHIPMENT_LINE.matcher(line);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("Linea " + (index + 1) + " invalida: " + line);
                    }

                    String shipmentCode = originCode + "-" + normalizeShipmentId(matcher.group(1));
                    OffsetDateTime registeredAtUtc = parseShipmentLineDate(
                            matcher.group(2), matcher.group(3), matcher.group(4), index + 1, origin.offset());
                    String destinationCode = normalizeAirportCode(matcher.group(5));
                    int baggageCount = Integer.parseInt(matcher.group(6));
                    if (baggageCount <= 0) {
                        throw new IllegalArgumentException("Linea " + (index + 1) + ": la cantidad de maletas debe ser mayor a cero.");
                    }

                    AirportRef destination = airportCache.get(destinationCode);
                    if (destination == null) {
                        destination = findAirport(connection, destinationCode);
                        if (destination != null) {
                            airportCache.put(destinationCode, destination);
                        }
                    }
                    if (destination == null) {
                        throw new IllegalArgumentException("Linea " + (index + 1) + ": aeropuerto destino no encontrado: " + destinationCode);
                    }

                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, shipmentCode);
                    statement.setObject(3, origin.id());
                    statement.setObject(4, destination.id());
                    statement.setInt(5, baggageCount);
                    statement.setObject(6, registeredAtUtc);
                    statement.setObject(7, registeredAtUtc.plusDays(deadlineDays(origin, destination)));

                    int affected = statement.executeUpdate();
                    parsed++;
                    if (affected == 1) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                }
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw e;
            }

            if (parsed == 0) {
                connection.rollback();
                throw new IllegalArgumentException("El archivo no contiene lineas de envios validas.");
            }

            connection.commit();
        }

        return batchJson(parsed, inserted, skipped);
    }

    public String listShipmentsForDate(String rawDate) throws IOException {
        String date = normalizeRawDate(rawDate);
        LocalDate listDate = LocalDate.parse(date, RAW_DATE);
        String parserStartDate = listDate.minusDays(1).format(RAW_DATE);
        OffsetDateTime windowStart = listDate.equals(LocalDate.now(LIST_TIME_ZONE))
                ? OffsetDateTime.now(LIST_TIME_ZONE)
                : listDate.atStartOfDay().atOffset(LIST_TIME_ZONE);
        OffsetDateTime windowEnd = listDate.plusDays(1).atStartOfDay().atOffset(LIST_TIME_ZONE);
        Map<String, Airport> airportMap = loadAirportMap();
        ShipmentParser parser = new ShipmentParser(airportMap);

        List<ListedShipment> shipments = new ArrayList<>();
        for (Shipment shipment : parser.parseAll("data/envios", parserStartDate, 3)) {
            ListedShipment listedShipment = ListedShipment.from(shipment, "TXT", airportMap);
            if (listedShipment.isWithin(windowStart, windowEnd)) {
                shipments.add(listedShipment);
            }
        }

        for (Shipment shipment : parser.parseAllFromDatabase(date, 1, LIST_TIME_ZONE)) {
            ListedShipment listedShipment = ListedShipment.from(shipment, "BD", airportMap);
            if (listedShipment.isWithin(windowStart, windowEnd)) {
                shipments.add(listedShipment);
            }
        }

        shipments.sort(Comparator
                .comparing(ListedShipment::shipmentDate)
                .thenComparing(ListedShipment::originAirportCode)
                .thenComparing(ListedShipment::shipmentCode));

        StringBuilder json = new StringBuilder(shipments.size() * 160 + 64);
        json.append("[");
        for (int index = 0; index < shipments.size(); index++) {
            appendListedShipment(json, shipments.get(index));
            if (index < shipments.size() - 1) {
                json.append(",");
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

    private AirportRef findAirport(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, code, continent, timezone
                FROM airports
                WHERE code = ?
                """)) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new AirportRef(
                        (UUID) result.getObject("id"),
                        result.getString("code"),
                        result.getString("continent"),
                        parseTimezone(result.getString("timezone"))
                );
            }
        }
    }

    private Map<String, Airport> loadAirportMap() throws IOException {
        try {
            Map<String, Airport> airportMap = new LinkedHashMap<>();
            for (Airport airport : new AirportParser().parse()) {
                airportMap.put(airport.getCode(), airport);
            }
            return airportMap;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudieron cargar aeropuertos para listar envios.", e);
        }
    }

    private String normalizeRawDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return LocalDate.now(LIST_TIME_ZONE).format(RAW_DATE);
        }
        String normalized = rawDate.trim().replace("-", "");
        if (!normalized.matches("\\d{8}")) {
            throw new IllegalArgumentException("La fecha debe tener formato aaaammdd.");
        }
        LocalDate.parse(normalized, RAW_DATE);
        return normalized;
    }

    private void validateRequest(ShipmentCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Datos de envio invalidos.");
        }
        normalizeAirportCode(request.originAirportCode());
        normalizeAirportCode(request.destinationAirportCode());
        normalizeClientId(request.clientId());
        validateDateTime(request.departureDate());
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

    private String normalizeClientId(String clientId) {
        if (clientId == null || !clientId.matches("\\d{7}")) {
            throw new IllegalArgumentException("El id del cliente debe tener 7 digitos.");
        }
        return clientId;
    }

    private String nextShipmentCode(Connection connection, String originCode) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            lock.setString(1, "shipments:" + originCode);
            lock.execute();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(shipment_code FROM 6 FOR 9) AS INTEGER)), 0) + 1 AS next_id
                FROM shipments
                WHERE shipment_code ~ ?
                """)) {
            statement.setString(1, "^" + originCode + "-[0-9]{9}$");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                int nextId = result.getInt("next_id");
                if (nextId > 999_999_999) {
                    throw new IllegalArgumentException("Se alcanzo el maximo de envios para " + originCode + ".");
                }
                return originCode + "-" + String.format("%09d", nextId);
            }
        }
    }

    private void validateDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La fecha de salida es obligatoria.");
        }
        try {
            parseDateTimeAtOrigin(value, ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("La fecha de salida debe tener formato ISO local.");
        }
    }

    private OffsetDateTime parseDateTimeAtOrigin(String value, ZoneOffset originOffset) {
        String normalized = value.trim();
        if (normalized.endsWith("Z")) {
            return Instant.parse(normalized).atOffset(ZoneOffset.UTC);
        }
        if (normalized.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC);
        }
        return LocalDateTime.parse(normalized).atOffset(originOffset).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private OffsetDateTime parseShipmentLineDate(String date, String hour, String minute,
                                                 int lineNumber, ZoneOffset originOffset) {
        try {
            return LocalDateTime.parse(
                    date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                            + "T" + hour + ":" + minute
            ).atOffset(originOffset).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Linea " + lineNumber + ": fecha de salida invalida.");
        }
    }

    private ZoneOffset parseTimezone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneOffset.UTC;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = TZ_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return ZoneOffset.UTC;
        }
        int sign = matcher.group(1).equals("-") ? -1 : 1;
        int hours = Integer.parseInt(matcher.group(2));
        int minutes = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return ZoneOffset.ofTotalSeconds(sign * ((hours * 60 + minutes) * 60));
    }

    private int deadlineDays(AirportRef origin, AirportRef destination) {
        return origin.continent().equalsIgnoreCase(destination.continent()) ? 1 : 2;
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
        prop(json, "status", result.getString("status")).append(",");
        prop(json, "client_id", result.getString("client_id"));
        json.append("}");
        return json.toString();
    }

    private String batchJson(int parsed, int inserted, int skipped) {
        StringBuilder json = new StringBuilder(128);
        json.append("{");
        prop(json, "parsed", parsed).append(",");
        prop(json, "inserted", inserted).append(",");
        prop(json, "skipped", skipped);
        json.append("}");
        return json.toString();
    }

    private void appendListedShipment(StringBuilder json, ListedShipment shipment) {
        json.append("{");
        prop(json, "shipment_code", shipment.shipmentCode()).append(",");
        prop(json, "origin_airport_code", shipment.originAirportCode()).append(",");
        prop(json, "destination_airport_code", shipment.destinationAirportCode()).append(",");
        prop(json, "baggage_count", shipment.baggageCount()).append(",");
        prop(json, "shipment_date", shipment.shipmentDate()).append(",");
        prop(json, "source", shipment.source());
        json.append("}");
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
            String clientId
    ) {}

    public record ShipmentBatchCreateRequest(
            String originAirportCode,
            String fileContent
    ) {}

    private record ListedShipment(
            String shipmentCode,
            String originAirportCode,
            String destinationAirportCode,
            int baggageCount,
            String shipmentDate,
            String source,
            OffsetDateTime localDateTime
    ) {
        private static ListedShipment from(
                Shipment shipment,
                String source,
                Map<String, Airport> airportMap
        ) {
            OffsetDateTime shipmentTime = localShipmentTime(shipment, airportMap)
                    .withOffsetSameInstant(LIST_TIME_ZONE);
            String shipmentDate = shipmentTime.format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm"));
            return new ListedShipment(
                    shipment.getShipmentId(),
                    shipment.getOriginCode(),
                    shipment.getDestCode(),
                    shipment.getSuitcaseCount(),
                    shipmentDate,
                    source,
                    shipmentTime
            );
        }

        private boolean isWithin(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
            return !localDateTime.isBefore(startInclusive) && localDateTime.isBefore(endExclusive);
        }

        private static OffsetDateTime localShipmentTime(Shipment shipment, Map<String, Airport> airportMap) {
            LocalDateTime rawLocalTime = LocalDateTime.parse(
                    shipment.getRawDate().substring(0, 4)
                            + "-" + shipment.getRawDate().substring(4, 6)
                            + "-" + shipment.getRawDate().substring(6, 8)
                            + "T" + shipment.getRawHour()
                            + ":" + shipment.getRawMinuteStr()
            );
            Airport origin = airportMap.get(shipment.getOriginCode());
            int offset = origin == null ? 0 : origin.getGmtOffset();
            return rawLocalTime.atOffset(ZoneOffset.ofHours(offset));
        }
    }

    private record AirportRef(UUID id, String code, String continent, ZoneOffset offset) {}
}
