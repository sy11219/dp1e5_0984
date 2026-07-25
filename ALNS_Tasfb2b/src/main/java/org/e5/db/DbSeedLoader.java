package org.e5.db;

import org.e5.model.Airport;
import org.e5.parser.AirportParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga los archivos de entrada del proyecto en PostgreSQL/RDS.
 *
 * Variables de entorno requeridas:
 *   DB_URL      jdbc:postgresql://host:5432/tasf_b2b?sslmode=require
 *   DB_USER     usuario de PostgreSQL
 *   DB_PASSWORD password de PostgreSQL
 *
 * Ejemplo:
 *   mvn exec:java -Dexec.mainClass=org.e5.db.DbSeedLoader \
 *     -Dexec.args="--create-schema --start-date=2026-01-01 --days=5"
 */
public class DbSeedLoader {

    private static final Pattern FLIGHT_PATTERN = Pattern.compile(
            "^([A-Z]{4})-([A-Z]{4})-(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})-(\\d{4})\\s*$"
    );

    private static final DateTimeFormatter FLIGHT_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private record Options(
            Path airportsPath,
            Path flightsPath,
            LocalDate startDate,
            int days,
            boolean createSchema
    ) {}

    public static void main(String[] args) throws Exception {
        Options options = parseOptions(args);
        String dbUrl = requireEnv("DB_URL");
        String dbUser = requireEnv("DB_USER");
        String dbPassword = requireEnv("DB_PASSWORD");

        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            connection.setAutoCommit(false);
            try {
                if (options.createSchema()) {
                    createSchema(connection);
                }

                AirportParser airportParser = new AirportParser();
                List<Airport> airports = airportParser.parse(options.airportsPath().toString());
                Map<String, Airport> airportByCode = new HashMap<>();
                Map<String, UUID> airportIds = new HashMap<>();

                for (Airport airport : airports) {
                    airportByCode.put(airport.getCode(), airport);
                    UUID airportId = upsertAirport(connection, airport);
                    airportIds.put(airport.getCode(), airportId);
                }

                int insertedFlights = upsertFlights(connection, options, airportByCode, airportIds);
                connection.commit();

                System.out.printf("Carga completada: %d aeropuertos y %d planes de vuelo.%n",
                        airports.size(), insertedFlights);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static Options parseOptions(String[] args) {
        Path airportsPath = Path.of("data", "aeropuertos.txt");
        Path flightsPath = Path.of("data", "planes_vuelo.txt");
        LocalDate startDate = LocalDate.now();
        int days = 1;
        boolean createSchema = false;

        for (String arg : args) {
            if (arg.equals("--create-schema")) {
                createSchema = true;
            } else if (arg.startsWith("--airports=")) {
                airportsPath = Path.of(arg.substring("--airports=".length()));
            } else if (arg.startsWith("--flights=")) {
                flightsPath = Path.of(arg.substring("--flights=".length()));
            } else if (arg.startsWith("--start-date=")) {
                startDate = LocalDate.parse(arg.substring("--start-date=".length()));
            } else if (arg.startsWith("--days=")) {
                days = Integer.parseInt(arg.substring("--days=".length()));
            } else {
                throw new IllegalArgumentException("Argumento no reconocido: " + arg);
            }
        }

        if (days < 1) {
            throw new IllegalArgumentException("--days debe ser mayor o igual a 1");
        }

        return new Options(airportsPath, flightsPath, startDate, days, createSchema);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + name);
        }
        return value;
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS airports (
                      id UUID PRIMARY KEY,
                      code VARCHAR(10) UNIQUE NOT NULL,
                      name VARCHAR(120) NOT NULL,
                      city VARCHAR(120) NOT NULL,
                      country VARCHAR(120) NOT NULL,
                      continent VARCHAR(40) NOT NULL,
                      latitude NUMERIC(9,6),
                      longitude NUMERIC(9,6),
                      timezone VARCHAR(64) NOT NULL,
                      warehouse_capacity INT NOT NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                      created_at TIMESTAMPTZ DEFAULT now()
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS flight_plans (
                      id UUID PRIMARY KEY,
                      flight_code VARCHAR(40) UNIQUE NOT NULL,
                      origin_airport_id UUID NOT NULL REFERENCES airports(id),
                      destination_airport_id UUID NOT NULL REFERENCES airports(id),
                      departure_time_local TIMESTAMP NOT NULL,
                      arrival_time_local TIMESTAMP NOT NULL,
                      departure_time_utc TIMESTAMPTZ NOT NULL,
                      arrival_time_utc TIMESTAMPTZ NOT NULL,
                      capacity INT NOT NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
                      created_at TIMESTAMPTZ DEFAULT now()
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shipments (
                      id UUID PRIMARY KEY,
                      shipment_code VARCHAR(80) UNIQUE NOT NULL,
                      origin_airport_id UUID NOT NULL REFERENCES airports(id),
                      destination_airport_id UUID NOT NULL REFERENCES airports(id),
                      baggage_count INT NOT NULL CHECK (baggage_count > 0),
                      registered_at TIMESTAMPTZ NOT NULL,
                      max_delivery_at TIMESTAMPTZ NOT NULL,
                      status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
                      created_at TIMESTAMPTZ DEFAULT now()
                    )
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_flights_origin_date
                    ON flight_plans(origin_airport_id, departure_time_utc)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_flights_destination_date
                    ON flight_plans(destination_airport_id, arrival_time_utc)
                    """);

            statement.execute("CREATE INDEX IF NOT EXISTS idx_shipments_code ON shipments(shipment_code)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_shipments_status ON shipments(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_shipments_origin ON shipments(origin_airport_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_shipments_destination ON shipments(destination_airport_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_shipments_registered_at ON shipments(registered_at)");
        }
    }

    private static UUID upsertAirport(Connection connection, Airport airport) throws SQLException {
        UUID id = findAirportId(connection, airport.getCode());
        if (id == null) {
            id = UUID.randomUUID();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO airports (
                  id, code, name, city, country, continent, latitude, longitude,
                  timezone, warehouse_capacity, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (code) DO UPDATE SET
                  name = EXCLUDED.name,
                  city = EXCLUDED.city,
                  country = EXCLUDED.country,
                  continent = EXCLUDED.continent,
                  latitude = EXCLUDED.latitude,
                  longitude = EXCLUDED.longitude,
                  timezone = EXCLUDED.timezone,
                  warehouse_capacity = EXCLUDED.warehouse_capacity,
                  status = EXCLUDED.status
                RETURNING id
                """)) {
            statement.setObject(1, id);
            statement.setString(2, airport.getCode());
            statement.setString(3, airport.getCity());
            statement.setString(4, airport.getCity());
            statement.setString(5, airport.getCountry());
            statement.setString(6, airport.getContinent());
            statement.setDouble(7, airport.getLatitude());
            statement.setDouble(8, airport.getLongitude());
            statement.setString(9, formatTimezone(airport.getGmtOffset()));
            statement.setInt(10, airport.getMaxCapacity());

            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return (UUID) result.getObject("id");
            }
        }
    }

    private static UUID findAirportId(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM airports WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? (UUID) result.getObject("id") : null;
            }
        }
    }

    private static int upsertFlights(
            Connection connection,
            Options options,
            Map<String, Airport> airportByCode,
            Map<String, UUID> airportIds
    ) throws IOException, SQLException {
        int total = 0;

        try (BufferedReader reader = Files.newBufferedReader(options.flightsPath(), StandardCharsets.UTF_8)) {
            String line;
            int baseFlightIndex = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                    continue;
                }

                Matcher matcher = FLIGHT_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    System.out.println("Vuelo ignorado por formato inválido: " + line);
                    continue;
                }

                baseFlightIndex++;
                String originCode = matcher.group(1);
                String destinationCode = matcher.group(2);
                int depHour = Integer.parseInt(matcher.group(3));
                int depMinute = Integer.parseInt(matcher.group(4));
                int arrHour = Integer.parseInt(matcher.group(5));
                int arrMinute = Integer.parseInt(matcher.group(6));
                int capacity = Integer.parseInt(matcher.group(7));

                Airport origin = airportByCode.get(originCode);
                Airport destination = airportByCode.get(destinationCode);
                UUID originId = airportIds.get(originCode);
                UUID destinationId = airportIds.get(destinationCode);

                if (origin == null || destination == null || originId == null || destinationId == null) {
                    throw new IllegalStateException("Vuelo referencia aeropuerto no cargado: " + line);
                }

                for (int day = 0; day < options.days(); day++) {
                    LocalDate flightDate = options.startDate().plusDays(day);
                    LocalDateTime departureLocal = flightDate.atTime(depHour, depMinute);
                    LocalDateTime arrivalLocal = flightDate.atTime(arrHour, arrMinute);

                    OffsetDateTime departureUtc = toUtc(departureLocal, origin.getGmtOffset());
                    OffsetDateTime arrivalUtc = toUtc(arrivalLocal, destination.getGmtOffset());
                    while (!arrivalUtc.isAfter(departureUtc)) {
                        arrivalLocal = arrivalLocal.plusDays(1);
                        arrivalUtc = toUtc(arrivalLocal, destination.getGmtOffset());
                    }

                    String flightCode = buildFlightCode(originCode, destinationCode, flightDate, depHour, depMinute, baseFlightIndex);
                    upsertFlight(connection, flightCode, originId, destinationId,
                            departureLocal, arrivalLocal, departureUtc, arrivalUtc, capacity);
                    total++;
                }
            }
        }

        return total;
    }

    private static void upsertFlight(
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
        UUID id = findFlightId(connection, flightCode);
        if (id == null) {
            id = UUID.randomUUID();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO flight_plans (
                  id, flight_code, origin_airport_id, destination_airport_id,
                  departure_time_local, arrival_time_local,
                  departure_time_utc, arrival_time_utc,
                  capacity, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                ON CONFLICT (flight_code) DO UPDATE SET
                  origin_airport_id = EXCLUDED.origin_airport_id,
                  destination_airport_id = EXCLUDED.destination_airport_id,
                  departure_time_local = EXCLUDED.departure_time_local,
                  arrival_time_local = EXCLUDED.arrival_time_local,
                  departure_time_utc = EXCLUDED.departure_time_utc,
                  arrival_time_utc = EXCLUDED.arrival_time_utc,
                  capacity = EXCLUDED.capacity,
                  status = EXCLUDED.status
                """)) {
            statement.setObject(1, id);
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

    private static UUID findFlightId(Connection connection, String flightCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM flight_plans WHERE flight_code = ?")) {
            statement.setString(1, flightCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? (UUID) result.getObject("id") : null;
            }
        }
    }

    private static OffsetDateTime toUtc(LocalDateTime localDateTime, int gmtOffset) {
        return localDateTime.atOffset(ZoneOffset.ofHours(gmtOffset))
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String formatTimezone(int gmtOffset) {
        return String.format("UTC%+03d:00", gmtOffset);
    }

    private static String buildFlightCode(
            String origin,
            String destination,
            LocalDate date,
            int departureHour,
            int departureMinute,
            int baseFlightIndex
    ) {
        return String.format("%s-%s-%s-%02d%02d-%04d",
                origin, destination, date.format(FLIGHT_DAY_FORMAT),
                departureHour, departureMinute, baseFlightIndex);
    }
}
