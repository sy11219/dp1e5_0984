package org.e5.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

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
}
