package org.e5.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
