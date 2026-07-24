package org.e5.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorServerTest {

    @Test
    void findAvailablePortSkipsOccupiedPreferredPort() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int preferredPort = occupied.getLocalPort();
            int selectedPort = SimulatorServer.findAvailablePort(preferredPort, 5);

            assertNotEquals(preferredPort, selectedPort);
            assertTrue(selectedPort > 0);
        }
    }

    @Test
    void validatesMapTileCoordinatesWithinZoomBounds() {
        assertTrue(SimulatorServer.isValidMapTileCoordinate(3, 4, 2));
        assertTrue(!SimulatorServer.isValidMapTileCoordinate(3, 8, 2));
        assertTrue(!SimulatorServer.isValidMapTileCoordinate(20, 0, 0));
    }

    @Test
    void paginatesFlightsInTheTwentyFourHourOperationalWindow() throws Exception {
        RealtimeSimulationService service = new RealtimeSimulationService();
        String session = service.startBatchSimulation("20260102", 0, "08:00", "UTC");
        Matcher matcher = Pattern.compile("\\\"simulationId\\\":\\\"([^\\\"]+)\\\"").matcher(session);
        assertTrue(matcher.find());

        String page = service.batchFlights(
                matcher.group(1), 1, 10,
                "", "", "", "", "",
                "departureMinute", "asc");

        assertTrue(page.contains("\"page\":1"));
        assertTrue(page.contains("\"pageSize\":10"));
        assertTrue(page.contains("\"windowStartMinute\":-960"));
        assertTrue(page.contains("\"windowEndMinute\":1920"));
        assertEquals(10, page.split("\"scheduleStatus\":").length - 1);
    }

    @Test
    void cancelsTheNextDailyOccurrenceWhenTheImmediateFlightIsAnHourOrLessAway() throws Exception {
        RealtimeSimulationService service = new RealtimeSimulationService();
        String session = service.startBatchSimulation("20260102", 1, "08:00", "UTC");
        Matcher sessionMatcher = Pattern.compile("\\\"simulationId\\\":\\\"([^\\\"]+)\\\"").matcher(session);
        assertTrue(sessionMatcher.find());
        String simulationId = sessionMatcher.group(1);

        String page = service.batchFlights(
                simulationId, 1, 100,
                "", "", "", "not-started", "",
                "departureMinute", "asc");
        Matcher flightMatcher = Pattern.compile(
                "\\\"id\\\":\\\"([^\\\"]+)\\\".*?\\\"absoluteDepartureMinute\\\":(\\d+)",
                Pattern.DOTALL).matcher(page);
        assertTrue(flightMatcher.find());

        String flightCode = flightMatcher.group(1);
        int immediateDeparture = Integer.parseInt(flightMatcher.group(2));
        assertTrue(immediateDeparture > 480 && immediateDeparture <= 540);

        service.cancelFlight(simulationId, flightCode);
        String state = service.state(simulationId);

        assertTrue(state.contains("\"" + flightCode + "@" + (immediateDeparture + 1440) + "\""));
    }
}
