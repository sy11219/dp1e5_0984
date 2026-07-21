package org.e5.util;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/** Zona horaria civil usada al registrar datos operativos. */
public final class AirportTimeZones {
    private static final Map<String, ZoneId> KNOWN_ZONES = Map.ofEntries(
            Map.entry("SPIM", ZoneId.of("America/Lima")),
            Map.entry("SABE", ZoneId.of("America/Argentina/Buenos_Aires")),
            Map.entry("SCEL", ZoneId.of("America/Santiago")),
            Map.entry("SVMI", ZoneId.of("America/Caracas")),
            Map.entry("SBBR", ZoneId.of("America/Sao_Paulo")),
            Map.entry("SKBO", ZoneId.of("America/Bogota")),
            Map.entry("SGAS", ZoneId.of("America/Asuncion")),
            Map.entry("SUAA", ZoneId.of("America/Montevideo")),
            Map.entry("EKCH", ZoneId.of("Europe/Copenhagen")),
            Map.entry("EBCI", ZoneId.of("Europe/Brussels")),
            Map.entry("LBSF", ZoneId.of("Europe/Sofia")),
            Map.entry("EHAM", ZoneId.of("Europe/Amsterdam")),
            Map.entry("OAKB", ZoneId.of("Asia/Kabul")),
            Map.entry("OPKC", ZoneId.of("Asia/Karachi")),
            Map.entry("OMDB", ZoneId.of("Asia/Dubai")),
            Map.entry("VIDP", ZoneId.of("Asia/Kolkata"))
    );

    private AirportTimeZones() {
    }

    public static ZoneId resolve(String airportCode, String configuredTimeZone) {
        ZoneId known = KNOWN_ZONES.get(normalizeCode(airportCode));
        return known != null ? known : parseConfigured(configuredTimeZone);
    }

    private static ZoneId parseConfigured(String value) {
        if (value == null || value.isBlank()) return ZoneOffset.UTC;
        String trimmed = value.trim();
        try {
            return ZoneId.of(trimmed);
        } catch (Exception ignored) {
            // El catálogo histórico guarda valores como UTC-05:00 o GMT+02.
        }

        String normalized = trimmed.toUpperCase(Locale.ROOT).replace("GMT", "UTC");
        if ("UTC".equals(normalized)) return ZoneOffset.UTC;
        if (normalized.startsWith("UTC")) {
            try {
                return ZoneOffset.of(normalized.substring(3));
            } catch (Exception ignored) {
                throw new IllegalArgumentException("Timezone de aeropuerto invalido: " + value);
            }
        }
        throw new IllegalArgumentException("Timezone de aeropuerto invalido: " + value);
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
