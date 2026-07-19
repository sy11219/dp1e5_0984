package org.e5.web;

import org.e5.model.Airport;
import org.e5.model.Shipment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lectura secuencial de los TXT de envios para el escenario de colapso.
 *
 * Cada archivo se recorre una sola vez y conserva su siguiente envio pendiente.
 * Asi una ejecucion larga no vuelve a escanear los ~400 MB de TXT en cada
 * ventana de planificacion.
 */
final class CollapseShipmentSource implements AutoCloseable {
    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "_envio[s]?_([A-Z]{4})_\\.txt", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d+)-(\\d{8})-(\\d{2})-(\\d{2})-([A-Z]{4})-(\\d{3})-(\\d{7})\\s*$");

    private final LocalDate simulationStartDate;
    private final ZoneId simulationZone;
    private final Map<String, Airport> airportMap;
    private final int maxMinute;
    private final List<Cursor> cursors = new ArrayList<>();

    CollapseShipmentSource(
            String startDate,
            int maxMinute,
            Map<String, Airport> airportMap,
            ZoneId simulationZone
    ) throws IOException {
        this.simulationStartDate = LocalDate.parse(startDate, RAW_DATE);
        this.simulationZone = simulationZone == null ? ZoneOffset.UTC : simulationZone;
        this.maxMinute = maxMinute;
        this.airportMap = airportMap;

        File folder = new File("data/envios");
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.isFile()) continue;
            Matcher matcher = FILE_PATTERN.matcher(file.getName());
            if (!matcher.matches()) continue;
            cursors.add(new Cursor(file, matcher.group(1).toUpperCase()));
        }
    }

    List<Shipment> takeWindow(int startMinute, int endMinute) throws IOException {
        if (endMinute <= startMinute) return List.of();

        List<Shipment> shipments = new ArrayList<>();
        for (Cursor cursor : cursors) {
            cursor.takeWindow(startMinute, endMinute, shipments);
        }
        shipments.sort(Comparator
                .comparingInt(Shipment::getRequestMinute)
                .thenComparing(Shipment::getShipmentId));
        return shipments;
    }

    @Override
    public void close() {
        for (Cursor cursor : cursors) {
            cursor.close();
        }
    }

    private final class Cursor {
        private final String originCode;
        private final BufferedReader reader;
        private Shipment pending;
        private boolean finished;

        Cursor(File file, String originCode) throws IOException {
            this.originCode = originCode;
            this.reader = new BufferedReader(new FileReader(file));
        }

        void takeWindow(int startMinute, int endMinute, List<Shipment> destination) throws IOException {
            while (true) {
                Shipment shipment = peek();
                if (shipment == null || shipment.getRequestMinute() >= endMinute) return;
                pending = null;
                if (shipment.getRequestMinute() >= startMinute) {
                    destination.add(shipment);
                }
            }
        }

        private Shipment peek() throws IOException {
            if (pending != null) return pending;
            if (finished) return null;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                Matcher matcher = LINE_PATTERN.matcher(line);
                if (!matcher.matches()) continue;

                int requestMinute = requestMinute(matcher.group(2), matcher.group(3), matcher.group(4));
                if (requestMinute >= maxMinute) {
                    finished = true;
                    close();
                    return null;
                }

                pending = new Shipment(
                        originCode + "-" + matcher.group(1),
                        originCode,
                        matcher.group(5),
                        requestMinute,
                        Integer.parseInt(matcher.group(6)),
                        matcher.group(7),
                        matcher.group(2),
                        matcher.group(3),
                        matcher.group(4)
                );
                return pending;
            }

            finished = true;
            close();
            return null;
        }

        private int requestMinute(String date, String hour, String minute) {
            Airport origin = airportMap.get(originCode);
            int gmtOffset = origin == null ? 0 : origin.getGmtOffset();
            Instant simulationStart = simulationStartDate.atStartOfDay(simulationZone).toInstant();
            Instant shipmentInstant = LocalDate.parse(date, RAW_DATE)
                    .atTime(Integer.parseInt(hour), Integer.parseInt(minute))
                    .atOffset(ZoneOffset.ofHours(gmtOffset))
                    .toInstant();
            return Math.toIntExact(Duration.between(simulationStart, shipmentInstant).toMinutes());
        }

        void close() {
            try {
                reader.close();
            } catch (IOException ignored) {
                // El lector se usa solo para el ciclo de vida de una sesion.
            }
        }
    }
}
