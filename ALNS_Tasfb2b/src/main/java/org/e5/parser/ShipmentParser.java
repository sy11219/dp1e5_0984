package org.e5.parser;

import org.e5.model.Airport;
import org.e5.model.Shipment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de archivos de envíos (_envios_XXXX_.txt) de TASF.B2B.
 *
 * Cada aeropuerto tiene su propio archivo de envíos en la carpeta data/envios/.
 * El nombre del archivo sigue el patrón: _envios_XXXX_.txt
 * donde XXXX es el código ICAO del aeropuerto origen.
 *
 * Formato de cada línea de envío:
 *   id_envio-aaaammdd-hh-mm-DEST-###-IdCliente
 *
 * Ejemplo:
 *   000000001-20260102-01-22-SBBR-004-0025872
 *
 * El parser:
 * - Escanea la carpeta data/envios/ buscando todos los archivos que coincidan
 *   con el patrón _envios_XXXX_.txt
 * - Para cada archivo, extrae el código del aeropuerto origen del nombre del archivo
 * - Lee y parsea cada línea de envío
 * - Convierte la fecha/hora del envío a minutos absolutos desde el inicio de simulación
 *   para que el planificador pueda comparar tiempos fácilmente
 *
 * NOTA sobre la fecha de inicio de simulación:
 * El usuario ingresa la fecha de inicio (ej: 20260102). El parser calcula cuántos
 * minutos han pasado desde esa fecha hasta la hora del pedido, para expresar
 * el requestMinute del envío como minutos desde el inicio de la simulación.
 * Envíos de días anteriores al inicio son ignorados o marcados como día 0.
 */
public class ShipmentParser {

    private static final String ENVIOS_FOLDER = "data/envios";
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final boolean ASSUME_SORTED_TXT_SHIPMENTS = readBoolean(
            "TASF_ASSUME_SORTED_SHIPMENTS", true);

    // Patrón del nombre de archivo: _envios_SKBO_.txt  (o _envio_SKBO_.txt - ambos aceptados)
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "_envio[s]?_([A-Z]{4})_\\.txt", Pattern.CASE_INSENSITIVE
    );

    // Patrón de cada línea: 000000001-20260102-01-22-SBBR-004-0025872
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d+)-(\\d{8})-(\\d{2})-(\\d{2})-([A-Z]{4})-(\\d{3})-(\\d{7})\\s*$"
    );

    private final Map<String, Airport> airportMap;

    public ShipmentParser(Map<String, Airport> airportMap) {
        this.airportMap = airportMap;
    }

    /**
     * Carga todos los envíos de todos los aeropuertos desde la carpeta data/envios/.
     *
     * Proceso:
     * 1. Lista todos los archivos en data/envios/
     * 2. Filtra los que coinciden con el patrón _envios_XXXX_.txt
     * 3. Por cada archivo, extrae el código ICAO del aeropuerto origen
     * 4. Parsea cada línea como un Shipment
     * 5. Convierte fecha/hora a minutos desde el inicio de simulación
     * 6. Filtra envíos con fecha posterior al final de la simulación
     *
     * @param simulationStartDate Fecha de inicio en formato aaaammdd (ej: "20260102")
     * @param maxSimulationDays   Número máximo de días de simulación (ej: 2)
     * @return Lista completa de todos los envíos de todos los aeropuertos dentro del rango
     * @throws IOException Si hay error de lectura
     */
    public List<Shipment> parseAll(String simulationStartDate, int maxSimulationDays) throws IOException {
        return parseAll("data/envios", simulationStartDate, maxSimulationDays);
    }

    public List<Shipment> parseAllFromDatabase(String simulationStartDate, int maxSimulationDays) throws IOException {
        return parseAllFromDatabase(simulationStartDate, maxSimulationDays, ZoneOffset.UTC);
    }

    public List<Shipment> parseAllFromDatabase(
            String simulationStartDate,
            int maxSimulationDays,
            ZoneId simulationZone
    ) throws IOException {
        if (simulationStartDate == null || !simulationStartDate.matches("\\d{8}")) {
            throw new IOException("La fecha inicial debe tener formato aaaammdd.");
        }
        if (maxSimulationDays < 1) {
            throw new IOException("maxSimulationDays debe ser mayor o igual a 1.");
        }

        LocalDate startDate = LocalDate.parse(simulationStartDate, RAW_DATE);
        ZoneId zone = simulationZone == null ? ZoneOffset.UTC : simulationZone;
        Instant startInstant = startDate.atStartOfDay(zone).toInstant();
        Instant endInstant = startDate.plusDays(maxSimulationDays).atStartOfDay(zone).toInstant();
        int maxSimulationMinutes = maxSimulationDays * 1440;
        List<Shipment> shipments = new ArrayList<>();

        try (Connection connection = openConnection()) {
            boolean hasClientId = columnExists(connection, "shipments", "client_id");
            String clientColumn = hasClientId ? "s.client_id AS client_id," : "NULL AS client_id,";
            String sql = """
                    SELECT s.shipment_code,
                           %s
                           oa.code AS origin_code,
                           da.code AS destination_code,
                           s.baggage_count,
                           s.registered_at
                    FROM shipments s
                    JOIN airports oa ON oa.id = s.origin_airport_id
                    JOIN airports da ON da.id = s.destination_airport_id
                    WHERE s.registered_at >= ?
                      AND s.registered_at < ?
                      AND (s.status IS NULL OR UPPER(s.status) <> 'CANCELED')
                    ORDER BY s.registered_at, s.shipment_code
                    """.formatted(clientColumn);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, OffsetDateTime.ofInstant(startInstant, ZoneOffset.UTC));
                statement.setObject(2, OffsetDateTime.ofInstant(endInstant, ZoneOffset.UTC));

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Timestamp registeredTimestamp = result.getTimestamp("registered_at");
                        if (registeredTimestamp == null) continue;

                        Instant registeredInstant = registeredTimestamp.toInstant();
                        int requestMinute = (int) Duration.between(startInstant, registeredInstant).toMinutes();
                        if (requestMinute < 0 || requestMinute >= maxSimulationMinutes) continue;

                        String originCode = result.getString("origin_code");
                        OffsetDateTime originLocalTime = registeredInstant.atOffset(ZoneOffset.UTC)
                                .withOffsetSameInstant(originOffset(originCode));
                        String clientId = result.getString("client_id");

                        shipments.add(new Shipment(
                                result.getString("shipment_code"),
                                originCode,
                                result.getString("destination_code"),
                                requestMinute,
                                result.getInt("baggage_count"),
                                clientId == null ? "" : clientId,
                                originLocalTime.format(RAW_DATE),
                                twoDigits(originLocalTime.getHour()),
                                twoDigits(originLocalTime.getMinute())
                        ));
                    }
                }
            }
        } catch (IllegalStateException e) {
            System.err.printf("[ShipmentParser] %s. Usando carpeta local data/envios.%n", e.getMessage());
            return parseAll("data/envios", simulationStartDate, maxSimulationDays);
        } catch (SQLException e) {
            System.err.printf("[ShipmentParser] No se pudo conectar a la base de datos (%s). Usando carpeta local data/envios.%n", e.getMessage());
            return parseAll("data/envios", simulationStartDate, maxSimulationDays);
        }

        System.out.printf("[ShipmentParser] Cargados %d envios desde BD.%n", shipments.size());
        return shipments;
    }

    /**
     * Carga todos los envíos desde una carpeta específica.
     *
     * @param folderPath          Ruta a la carpeta de envíos
     * @param simulationStartDate Fecha inicio en formato aaaammdd
     * @param maxSimulationDays   Número máximo de días de simulación
     * @return Lista completa de envíos dentro del rango
     * @throws IOException Si hay error de lectura
     */
    public List<Shipment> parseAll(String folderPath, String simulationStartDate, int maxSimulationDays) throws IOException {
        List<Shipment> allShipments = new ArrayList<>();

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.err.printf("[ShipmentParser] ERROR: La carpeta '%s' no existe.%n", folderPath);
            return allShipments;
        }

        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            System.err.printf("[ShipmentParser] AVISO: No se encontraron archivos en '%s'.%n", folderPath);
            return allShipments;
        }

        int fileCount = 0;
        for (File file : files) {
            if (!file.isFile()) continue;

            Matcher fm = FILE_PATTERN.matcher(file.getName());
            if (!fm.matches()) continue;

            String airportCode = fm.group(1).toUpperCase();
            List<Shipment> shipments = parseFile(file.getAbsolutePath(), airportCode, simulationStartDate, maxSimulationDays);
            allShipments.addAll(shipments);
            fileCount++;

            System.out.printf("[ShipmentParser] %s → %d envios cargados desde '%s'%n",
                    airportCode, shipments.size(), file.getName());
        }

        System.out.printf("[ShipmentParser] Total TXT: %d envios cargados de %d archivos.%n",
                allShipments.size(), fileCount);
        return allShipments;
    }

    /**
     * Parsea un único archivo de envíos de aeropuerto.
     *
     * @param filePath            Ruta al archivo _envios_XXXX_.txt
     * @param originCode          Código ICAO del aeropuerto origen (del nombre del archivo)
     * @param simulationStartDate Fecha inicio en formato aaaammdd
     * @param maxSimulationDays   Número máximo de días de simulación
     * @return Lista de envíos del archivo dentro del rango
     * @throws IOException Si hay error de lectura
     */
    public List<Shipment> parseFile(String filePath, String originCode,
                                     String simulationStartDate, int maxSimulationDays) throws IOException {
        List<Shipment> shipments = new ArrayList<>();
        LocalDate startDate = LocalDate.parse(simulationStartDate, RAW_DATE);
        String earliestRelevantDate = startDate.minusDays(1).format(RAW_DATE);
        String latestExclusiveDate = startDate.plusDays(maxSimulationDays + 1L).format(RAW_DATE);

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;
            while ((line = br.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                Matcher m = LINE_PATTERN.matcher(line);
                if (!m.matches()) {
                    System.err.printf("[ShipmentParser] Linea %d ignorada en %s: '%s'%n",
                            lineNumber, filePath, line);
                    continue;
                }

                String shipmentId   = originCode + "-" + m.group(1);
                String date         = m.group(2);  // aaaammdd
                if (date.compareTo(earliestRelevantDate) < 0) continue;
                if (date.compareTo(latestExclusiveDate) >= 0 && ASSUME_SORTED_TXT_SHIPMENTS) break;
                String hourStr      = m.group(3);  // HH
                String minuteStr    = m.group(4);  // MM
                String destCode     = m.group(5);  // DEST
                int    suitcaseCount = Integer.parseInt(m.group(6));
                String clientId     = m.group(7);

                // Convertir fecha/hora a minutos desde el inicio de simulación
                int requestMinute = dateTimeToSimMinutes(date, hourStr, minuteStr, simulationStartDate, originCode, airportMap);

                // Filtrar envíos fuera del rango de simulación
                int maxSimulationMinutes = maxSimulationDays * 1440;
                if (requestMinute < 0) continue; // Ignorar envíos anteriores al inicio
                if (requestMinute >= maxSimulationMinutes) continue; // Ignorar envíos posteriores al final

                Shipment shipment = new Shipment(
                        shipmentId, originCode, destCode,
                        requestMinute, suitcaseCount, clientId,
                        date, hourStr, minuteStr
                );
                shipments.add(shipment);
            }
        }

        return shipments;
    }

    /**
     * Convierte una fecha y hora del envío a minutos desde el inicio de la simulación.
     *
     * Cálculo:
     *   días_diferencia = dias_entre(simulationStartDate, fecha_envio)
     *   minutos = días_diferencia * 1440 + hora * 60 + minuto
     *
     * Si la fecha del envío es anterior al inicio de la simulación, el resultado
     * puede ser negativo (el llamador decide cómo manejarlo).
     *
     * @param date                Fecha del envío (aaaammdd)
     * @param hourStr             Hora del envío (HH)
     * @param minuteStr           Minuto del envío (MM)
     * @param simulationStartDate Fecha inicio de simulación (aaaammdd)
     * @return Minutos desde inicio de simulación (puede ser negativo si fecha anterior)
     */
    // Conexion a PostgreSQL para la lectura principal de shipments.
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

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private ZoneOffset originOffset(String originCode) {
        Airport origin = airportMap.get(originCode);
        return ZoneOffset.ofHours(origin != null ? origin.getGmtOffset() : 0);
    }

    private String twoDigits(int value) {
        return String.format("%02d", value);
    }

    // Conversor usado por el lector legado de archivos TXT.
    private int dateTimeToSimMinutes(String date, String hourStr, String minuteStr,
                                      String simulationStartDate,
                                     String originCode,
                                     Map<String, Airport> airportMap) {
        try {
            // Parsear fechas: aaaammdd → año, mes, día
            int sYear  = Integer.parseInt(simulationStartDate.substring(0, 4));
            int sMonth = Integer.parseInt(simulationStartDate.substring(4, 6));
            int sDay   = Integer.parseInt(simulationStartDate.substring(6, 8));

            int eYear  = Integer.parseInt(date.substring(0, 4));
            int eMonth = Integer.parseInt(date.substring(4, 6));
            int eDay   = Integer.parseInt(date.substring(6, 8));

            int hour   = Integer.parseInt(hourStr);
            int minute = Integer.parseInt(minuteStr);

            int gmtOffset = 0;
            Airport origin = airportMap.get(originCode);
            if (origin != null) {
                gmtOffset = origin.getGmtOffset();
            }

            int totalMinutesLocal = hour * 60 + minute;
            int totalMinutesUTC = totalMinutesLocal - gmtOffset * 60;

            // Convertir ambas fechas a número de días desde época (aproximado con días del año)
            long startDays = daysFromEpoch(sYear, sMonth, sDay);
            long eventDays = daysFromEpoch(eYear, eMonth, eDay);

            long dayDiff = eventDays - startDays;
            return (int)(dayDiff * 1440) + totalMinutesUTC;

        } catch (Exception e) {
            System.err.printf("[ShipmentParser] Error convirtiendo fecha %s %s:%s → 0%n",
                    date, hourStr, minuteStr);
            return 0;
        }
    }

    /**
     * Calcula el número de días desde una época arbitraria (1/1/1900) para una fecha dada.
     * Se usa para calcular diferencias de días entre dos fechas.
     *
     * Usa la fórmula estándar de conversión gregoriana.
     *
     * @param year  Año (ej: 2026)
     * @param month Mes (1-12)
     * @param day   Día (1-31)
     * @return Número de días desde la época
     */
    private long daysFromEpoch(int year, int month, int day) {
        // Algoritmo de Rata Die simplificado para diferencias de fechas
        int y = year;
        int m = month;
        int d = day;
        if (m < 3) { m += 12; y--; }
        long era = (y >= 0 ? y : y - 399) / 400;
        int  yoe = (int)(y - era * 400);
        int  doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        int  doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe;
    }

    private static boolean readBoolean(String name, boolean defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) return false;
        return defaultValue;
    }



}
