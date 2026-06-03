package org.e5.db;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Funcion de backend para cargar envios desde S3 hacia PostgreSQL/RDS.
 *
 * Variables de entorno requeridas:
 * - S3_BUCKET: bucket donde estan los txt.
 * - S3_PREFIX: prefijo de los txt, por defecto data/envios/
 * - DB_URL: jdbc:postgresql://host:5432/tasf_b2b?sslmode=require
 * - DB_USER
 * - DB_PASSWORD
 *
 * Variables opcionales:
 * - BATCH_SIZE: por defecto 5000
 * - RETURN_LIMIT: por defecto 5000
 */
public class S3ShipmentLoader {
    private static final Pattern FILE_PATTERN = Pattern.compile("_envio[s]?_([A-Z]{4})_\\.txt$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)-(\\d{8})-(\\d{2})-(\\d{2})-([A-Z]{4})-(\\d{3})-(\\d{7})\\s*$");
    private static final Pattern TZ_PATTERN = Pattern.compile("^UTC([+-])(\\d{2}):(\\d{2})$");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final S3Client s3Client;

    public S3ShipmentLoader() {
        this(S3Client.create());
    }

    public S3ShipmentLoader(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public String loadShipments(String fechaInicio, int dias) throws Exception {
        LocalDate startDate = parseDate(fechaInicio);
        if (dias < 1) {
            throw new IllegalArgumentException("dias debe ser mayor o igual a 1");
        }
        LocalDate endDateExclusive = startDate.plusDays(dias);

        String bucket = requireEnv("S3_BUCKET");
        String prefix = optionalEnv("S3_PREFIX", "data/envios/");
        String dbUrl = requireEnv("DB_URL");
        String dbUser = requireEnv("DB_USER");
        String dbPassword = requireEnv("DB_PASSWORD");
        int batchSize = parsePositiveInt(optionalEnv("BATCH_SIZE", "5000"), "BATCH_SIZE");
        int returnLimit = parsePositiveInt(optionalEnv("RETURN_LIMIT", "5000"), "RETURN_LIMIT");

        List<ShipmentResponse> returned = new ArrayList<>();
        int totalInserted = 0;

        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            connection.setAutoCommit(false);
            Map<String, AirportRef> airports = loadAirports(connection);
            if (airports.isEmpty()) {
                throw new IllegalStateException("No hay aeropuertos cargados en la tabla airports.");
            }

            List<String> keys = listTxtKeys(bucket, prefix);
            keys.sort(Comparator.naturalOrder());

            List<ShipmentRow> batch = new ArrayList<>(batchSize);
            for (String key : keys) {
                String originCode = originFromKey(key);
                if (originCode == null) continue;

                AirportRef origin = airports.get(originCode);
                if (origin == null) {
                    throw new IllegalStateException("El origen " + originCode + " de " + key + " no existe en airports.");
                }

                int fileCount = 0;
                for (ShipmentRow row : readShipmentsFromS3(bucket, key, origin, airports, startDate, endDateExclusive)) {
                    fileCount++;
                    if (returned.size() < returnLimit) {
                        returned.add(ShipmentResponse.from(row));
                    }

                    batch.add(row);
                    if (batch.size() >= batchSize) {
                        totalInserted += upsertBatch(connection, batch);
                        connection.commit();
                        batch.clear();
                    }
                }
                System.out.printf("[S3ShipmentLoader] %s -> %d envios dentro del rango.%n", key, fileCount);
            }

            if (!batch.isEmpty()) {
                totalInserted += upsertBatch(connection, batch);
                connection.commit();
            }
        }

        return toJson(startDate, dias, endDateExclusive, totalInserted, returned, returnLimit);
    }

    private Map<String, AirportRef> loadAirports(Connection connection) throws SQLException {
        Map<String, AirportRef> airports = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, continent, timezone FROM airports")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    String code = rs.getString("code");
                    String continent = rs.getString("continent");
                    String timezone = rs.getString("timezone");
                    airports.put(code, new AirportRef(id, code, continent, parseTimezone(timezone)));
                }
            }
        }
        return airports;
    }

    private List<String> listTxtKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix);
            if (continuationToken != null) {
                builder.continuationToken(continuationToken);
            }

            ListObjectsV2Response response = s3Client.listObjectsV2(builder.build());
            for (S3Object object : response.contents()) {
                String key = object.key();
                if (key.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    keys.add(key);
                }
            }
            continuationToken = response.nextContinuationToken();
        } while (continuationToken != null);
        return keys;
    }

    private Iterable<ShipmentRow> readShipmentsFromS3(
            String bucket,
            String key,
            AirportRef origin,
            Map<String, AirportRef> airports,
            LocalDate startDate,
            LocalDate endDateExclusive
    ) throws Exception {
        List<ShipmentRow> rows = new ArrayList<>();

        try (ResponseInputStream<GetObjectResponse> input = s3Client.getObject(builder -> builder.bucket(bucket).key(key));
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                Matcher matcher = LINE_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    System.out.printf("[S3ShipmentLoader] Linea ignorada %s:%d -> %s%n", key, lineNumber, line);
                    continue;
                }

                String rawShipmentId = matcher.group(1);
                String rawDate = matcher.group(2);
                String rawHour = matcher.group(3);
                String rawMinute = matcher.group(4);
                String destinationCode = matcher.group(5);
                int baggageCount = Integer.parseInt(matcher.group(6));
                String clientId = matcher.group(7);

                LocalDate shipmentDate = parseDate(rawDate);
                if (shipmentDate.isBefore(startDate) || !shipmentDate.isBefore(endDateExclusive)) {
                    continue;
                }

                AirportRef destination = airports.get(destinationCode);
                if (destination == null) {
                    throw new IllegalStateException("El destino " + destinationCode + " en " + key + ":" + lineNumber + " no existe en airports.");
                }

                OffsetDateTime registeredAt = toUtc(rawDate, rawHour, rawMinute, origin.offset());
                OffsetDateTime maxDeliveryAt = registeredAt.plusDays(deadlineDays(origin, destination));

                rows.add(new ShipmentRow(
                        UUID.randomUUID(),
                        origin.code() + "-" + rawShipmentId,
                        origin.id(),
                        destination.id(),
                        baggageCount,
                        registeredAt,
                        maxDeliveryAt,
                        "REGISTERED",
                        origin.code(),
                        destination.code(),
                        clientId
                ));
            }
        }

        return rows;
    }

    private int upsertBatch(Connection connection, List<ShipmentRow> rows) throws SQLException {
        String sql = """
                INSERT INTO shipments (
                  id, shipment_code, origin_airport_id, destination_airport_id,
                  baggage_count, registered_at, max_delivery_at, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (shipment_code) DO UPDATE SET
                  origin_airport_id = EXCLUDED.origin_airport_id,
                  destination_airport_id = EXCLUDED.destination_airport_id,
                  baggage_count = EXCLUDED.baggage_count,
                  registered_at = EXCLUDED.registered_at,
                  max_delivery_at = EXCLUDED.max_delivery_at,
                  status = EXCLUDED.status
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ShipmentRow row : rows) {
                statement.setObject(1, row.id());
                statement.setString(2, row.shipmentCode());
                statement.setObject(3, row.originAirportId());
                statement.setObject(4, row.destinationAirportId());
                statement.setInt(5, row.baggageCount());
                statement.setObject(6, row.registeredAt());
                statement.setObject(7, row.maxDeliveryAt());
                statement.setString(8, row.status());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return rows.size();
    }

    private String originFromKey(String key) {
        String filename = key.substring(key.lastIndexOf('/') + 1);
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        Matcher matcher = FILE_PATTERN.matcher(filename);
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private ZoneOffset parseTimezone(String value) {
        Matcher matcher = TZ_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Formato timezone no soportado en airports.timezone: " + value);
        }
        int sign = matcher.group(1).equals("+") ? 1 : -1;
        int hours = Integer.parseInt(matcher.group(2));
        int minutes = Integer.parseInt(matcher.group(3));
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
    }

    private OffsetDateTime toUtc(String rawDate, String rawHour, String rawMinute, ZoneOffset originOffset) {
        LocalDate date = parseDate(rawDate);
        LocalDateTime local = date.atTime(Integer.parseInt(rawHour), Integer.parseInt(rawMinute));
        return local.atOffset(originOffset).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private int deadlineDays(AirportRef origin, AirportRef destination) {
        return origin.continent().equalsIgnoreCase(destination.continent()) ? 1 : 2;
    }

    private LocalDate parseDate(String value) {
        if (value.matches("\\d{8}")) {
            return LocalDate.parse(value, BASIC_DATE);
        }
        return LocalDate.parse(value);
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta variable de entorno: " + name);
        }
        return value;
    }

    private String optionalEnv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int parsePositiveInt(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " debe ser mayor o igual a 1");
        }
        return parsed;
    }

    private String toJson(LocalDate startDate, int days, LocalDate endDateExclusive,
                          int inserted, List<ShipmentResponse> shipments, int returnLimit) {
        StringBuilder json = new StringBuilder(64 * 1024);
        json.append('{');
        prop(json, "fecha_inicio", startDate.toString()).append(',');
        prop(json, "dias", days).append(',');
        prop(json, "fecha_fin_exclusiva", endDateExclusive.toString()).append(',');
        prop(json, "insertados", inserted).append(',');
        prop(json, "devueltos", shipments.size()).append(',');
        prop(json, "return_limit", returnLimit).append(',');
        prop(json, "truncated", inserted > shipments.size()).append(',');
        json.append("\"shipments\":[");
        for (int i = 0; i < shipments.size(); i++) {
            ShipmentResponse shipment = shipments.get(i);
            json.append('{');
            prop(json, "shipment_code", shipment.shipmentCode()).append(',');
            prop(json, "origin_code", shipment.originCode()).append(',');
            prop(json, "destination_code", shipment.destinationCode()).append(',');
            prop(json, "baggage_count", shipment.baggageCount()).append(',');
            prop(json, "registered_at", shipment.registeredAt().toString()).append(',');
            prop(json, "max_delivery_at", shipment.maxDeliveryAt().toString()).append(',');
            prop(json, "status", shipment.status()).append(',');
            prop(json, "client_id", shipment.clientId());
            json.append('}');
            if (i < shipments.size() - 1) json.append(',');
        }
        json.append(']');
        json.append('}');
        return json.toString();
    }

    private StringBuilder prop(StringBuilder json, String name, String value) {
        return json.append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"');
    }

    private StringBuilder prop(StringBuilder json, String name, int value) {
        return json.append('"').append(escape(name)).append("\":").append(value);
    }

    private StringBuilder prop(StringBuilder json, String name, boolean value) {
        return json.append('"').append(escape(name)).append("\":").append(value);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record AirportRef(UUID id, String code, String continent, ZoneOffset offset) {}

    private record ShipmentRow(
            UUID id,
            String shipmentCode,
            UUID originAirportId,
            UUID destinationAirportId,
            int baggageCount,
            OffsetDateTime registeredAt,
            OffsetDateTime maxDeliveryAt,
            String status,
            String originCode,
            String destinationCode,
            String clientId
    ) {}

    private record ShipmentResponse(
            String shipmentCode,
            String originCode,
            String destinationCode,
            int baggageCount,
            OffsetDateTime registeredAt,
            OffsetDateTime maxDeliveryAt,
            String status,
            String clientId
    ) {
        static ShipmentResponse from(ShipmentRow row) {
            return new ShipmentResponse(
                    row.shipmentCode(),
                    row.originCode(),
                    row.destinationCode(),
                    row.baggageCount(),
                    row.registeredAt(),
                    row.maxDeliveryAt(),
                    row.status(),
                    row.clientId()
            );
        }
    }
}
