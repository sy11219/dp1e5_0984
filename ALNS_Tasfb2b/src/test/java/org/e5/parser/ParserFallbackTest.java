package org.e5.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ParserFallbackTest {

    @Test
    void airportParserFallsBackToLocalDataWhenDatabaseEnvIsMissing() throws Exception {
        AirportParser parser = new AirportParser();
        List<org.e5.model.Airport> airports = parser.parse();

        assertNotNull(airports);
        assertFalse(airports.isEmpty());
        assertFalse(airports.stream().anyMatch(a -> a.getCode() == null || a.getCode().isBlank()));
    }

    @Test
    void flightParserFallsBackToLocalDataWhenDatabaseEnvIsMissing() throws Exception {
        FlightPlanParser parser = new FlightPlanParser();
        List<org.e5.model.Flight> flights = parser.parseScheduledFromDatabase("20260102", 2, Map.of());

        assertNotNull(flights);
        assertFalse(flights.isEmpty());
    }

    @Test
    void txtShipmentRequestMinuteUsesScenarioTimeZone(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("_envios_SKBO_.txt"),
                "000000001-20260102-00-47-SPIM-001-0000001%n".formatted()
        );
        Map<String, org.e5.model.Airport> airports = Map.of(
                "SKBO",
                new org.e5.model.Airport("SKBO", "Bogota", "Colombia", "America", 1000, 4.7, -74.1, -5)
        );

        ShipmentParser parser = new ShipmentParser(airports);
        List<org.e5.model.Shipment> shipments = parser.parseAll(
                tempDir.toString(),
                "20260102",
                1,
                ZoneOffset.ofHours(-5)
        );

        assertEquals(1, shipments.size());
        assertEquals(47, shipments.get(0).getRequestMinute());
    }
}
