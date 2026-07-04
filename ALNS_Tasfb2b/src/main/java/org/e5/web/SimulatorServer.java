package org.e5.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.e5.db.AirportStatusService;
import org.e5.db.FlightPlanService;
import org.e5.db.ShipmentService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;

public class SimulatorServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_REALTIME_DAYS =
            envPositiveInt("TASF_REALTIME_DAYS", 5);
    private static final String DEFAULT_REALTIME_TIME_ZONE =
            envString("TASF_REALTIME_TIME_ZONE", java.time.ZoneId.systemDefault().getId());
    private static final Pattern START_DATE = Pattern.compile("\"startDate\"\\s*:\\s*\"(\\d{8})\"");
    private static final Pattern DAYS = Pattern.compile("\"days\"\\s*:\\s*(\\d+)");
    private static final Pattern ACTIVE = Pattern.compile("\"active\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAUSED = Pattern.compile("\"paused\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AIRPORT_STATUS_PATH = Pattern.compile("^/api/airports/([A-Za-z]{4})/status$");
    private static final Pattern AIRPORT_PATH = Pattern.compile("^/api/airports/([A-Za-z]{4})$");
    private static final Pattern FLIGHT_PATH = Pattern.compile("^/api/flights/([^/]+)$");
    private static final Pattern STEPS = Pattern.compile("\"steps\"\\s*:\\s*(\\d+)");
    private static final Pattern EXPECTED_TICK = Pattern.compile("\"expectedTick\"\\s*:\\s*(-?\\d+)");
    private static final Pattern START_TIME = Pattern.compile("\"startTime\"\\s*:\\s*\"(\\d{2}:\\d{2})\"");
    private static final Pattern TIME_ZONE = Pattern.compile("\"timeZone\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FLIGHT_ID = Pattern.compile("\"flightId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CLIENT_ID = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CONTROL_TOKEN = Pattern.compile("\"controlToken\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CITY = Pattern.compile("\"city\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern COUNTRY = Pattern.compile("\"country\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CONTINENT = Pattern.compile("\"continent\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern OPERATIONAL_STATUS = Pattern.compile("\"operationalStatus\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LATITUDE = Pattern.compile("\"latitude\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern LONGITUDE = Pattern.compile("\"longitude\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern GMT_OFFSET = Pattern.compile("\"gmtOffset\"\\s*:\\s*(-?\\d+)");
    private static final Pattern MAX_CAPACITY = Pattern.compile("\"maxCapacity\"\\s*:\\s*(\\d+)");
    private static final Pattern ORIGIN_AIRPORT_CODE = Pattern.compile("\"originAirportCode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DESTINATION_AIRPORT_CODE = Pattern.compile("\"destinationAirportCode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DEPARTURE_TIME_LOCAL = Pattern.compile("\"departureTimeLocal\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ARRIVAL_TIME_LOCAL = Pattern.compile("\"arrivalTimeLocal\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DEPARTURE_TIME_UTC = Pattern.compile("\"departureTimeUtc\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ARRIVAL_TIME_UTC = Pattern.compile("\"arrivalTimeUtc\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CAPACITY = Pattern.compile("\"capacity\"\\s*:\\s*(\\d+)");
    private static final Pattern FLIGHT_STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DEPARTURE_DATE = Pattern.compile("\"departureDate\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BAGGAGE_COUNT = Pattern.compile("\"baggageCount\"\\s*:\\s*(\\d+)");
    private static final Pattern SHIPMENT_ID = Pattern.compile("\"shipmentId\"\\s*:\\s*\"?([0-9]{1,9})\"?");
    private static final Pattern FILE_CONTENT_BASE64 = Pattern.compile("\"fileContentBase64\"\\s*:\\s*\"([^\"]*)\"");

    private final SimulationService simulationService = new SimulationService();
    private final AirportStatusService airportStatusService = new AirportStatusService();
    private final FlightPlanService flightPlanService = new FlightPlanService();
    private final ShipmentService shipmentService = new ShipmentService();
    private final RealtimeSimulationService realtimeSimulationService = new RealtimeSimulationService();

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        SimulatorServer app = new SimulatorServer();
        app.start(port);
    }

    static int findAvailablePort(int preferredPort, int maxAttempts) {
        if (isPortAvailable(preferredPort)) {
            return preferredPort;
        }

        for (int offset = 1; offset <= maxAttempts; offset++) {
            int candidate = preferredPort + offset;
            if (isPortAvailable(candidate)) {
                return candidate;
            }
        }

        return preferredPort;
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String envString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envPositiveInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                return DEFAULT_PORT;
            }
        }
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
                return DEFAULT_PORT;
            }
        }
        return DEFAULT_PORT;
    }

    private void start(int port) throws IOException {
        int resolvedPort = findAvailablePort(port, 10);
        if (resolvedPort != port) {
            System.out.printf("[Servidor] Puerto %d ocupado; usando %d en su lugar.%n", port, resolvedPort);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", resolvedPort), 0);
        server.createContext("/api/health", this::health);
        server.createContext("/api/simulations/alns", this::runAlns);
        server.createContext("/api/airports", this::airportStatus);
        server.createContext("/api/flights", this::flights);
        server.createContext("/api/shipments", this::shipments);
        server.createContext("/api/simulations/batch", this::batchSimulation);
        server.createContext("/api/realtime", this::realtime);
        server.createContext("/api/upload", this::upload);
        server.createContext("/", this::staticFile);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.printf("Simulador ALNS listo en http://localhost:%d/%n", resolvedPort);
        startRealtimeOnBoot();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop(0);
        }
    }

    private void startRealtimeOnBoot() {
        try {
            realtimeSimulationService.startAtCurrentTime(DEFAULT_REALTIME_DAYS, DEFAULT_REALTIME_TIME_ZONE);
            System.out.printf("[Tiempo real] Scheduler iniciado en fecha/hora actual (%s) por %d dias.%n",
                    DEFAULT_REALTIME_TIME_ZONE, DEFAULT_REALTIME_DAYS);
        } catch (Exception e) {
            System.err.printf("[Tiempo real] No se pudo iniciar automaticamente: %s%n", e.getMessage());
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        send(exchange, 200, "application/json", "{\"status\":\"ok\",\"service\":\"ALNS simulator\"}");
    }

    private void upload(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", "{\"error\":\"Use POST\"}");
            return;
        }
        send(exchange, 200, "application/json", "{\"status\":\"success\"}");
    }

    private void runAlns(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", "{\"error\":\"Use POST\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String startDate = readString(START_DATE, body, "20260102");
            int days = readInt(DAYS, body, 5);
            String result = simulationService.runAlns(startDate, days);
            send(exchange, 200, "application/json", result);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "application/json", "{\"error\":\"No se pudo ejecutar la simulacion ALNS\"}");
        }
    }

    private void airportStatus(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        if ("/api/airports".equals(path) || "/api/airports/".equals(path)) {
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 200, "application/json", airportStatusService.listAirportsJson());
                    return;
                }

                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    AirportStatusService.AirportUpdate update = new AirportStatusService.AirportUpdate(
                            readRequiredString(CITY, body, "city"),
                            readRequiredString(COUNTRY, body, "country"),
                            readRequiredString(CONTINENT, body, "continent"),
                            "ACTIVE",
                            readRequiredDouble(LATITUDE, body, "latitude"),
                            readRequiredDouble(LONGITUDE, body, "longitude"),
                            readRequiredInt(GMT_OFFSET, body, "gmtOffset"),
                            readRequiredInt(MAX_CAPACITY, body, "maxCapacity")
                    );
                    send(exchange, 201, "application/json",
                            airportStatusService.createAirport(readRequiredString(CODE, body, "code"), update));
                    return;
                }

                send(exchange, 405, "application/json", "{\"error\":\"Use GET o POST\"}");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudo procesar el aeropuerto\"}");
            }
            return;
        }

        Matcher pathMatcher = AIRPORT_STATUS_PATH.matcher(exchange.getRequestURI().getPath());
        if (pathMatcher.matches()) {
            String code = pathMatcher.group(1);
            try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 200, "application/json", airportStatusService.getStatus(code).toJson());
                return;
            }

            if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Boolean active = readBoolean(ACTIVE, body);
                if (active == null) {
                    send(exchange, 400, "application/json", "{\"error\":\"Envie active como true o false\"}");
                    return;
                }
                send(exchange, 200, "application/json", airportStatusService.updateStatus(code, active).toJson());
                return;
            }

            send(exchange, 405, "application/json", "{\"error\":\"Use GET o PATCH\"}");
            } catch (IllegalArgumentException e) {
                send(exchange, 404, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudo actualizar el aeropuerto\"}");
            }
            return;
        }

        Matcher airportMatcher = AIRPORT_PATH.matcher(exchange.getRequestURI().getPath());
        if (airportMatcher.matches()) {
            if (!"PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"Use PATCH\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                AirportStatusService.AirportUpdate update = new AirportStatusService.AirportUpdate(
                        readRequiredString(CITY, body, "city"),
                        readRequiredString(COUNTRY, body, "country"),
                        readRequiredString(CONTINENT, body, "continent"),
                        readRequiredString(OPERATIONAL_STATUS, body, "operationalStatus"),
                        readRequiredDouble(LATITUDE, body, "latitude"),
                        readRequiredDouble(LONGITUDE, body, "longitude"),
                        readRequiredInt(GMT_OFFSET, body, "gmtOffset"),
                        readRequiredInt(MAX_CAPACITY, body, "maxCapacity")
                );
                send(exchange, 200, "application/json",
                        airportStatusService.updateAirport(airportMatcher.group(1), update));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudo actualizar el aeropuerto\"}");
            }
            return;
        }

        send(exchange, 404, "application/json", "{\"error\":\"Endpoint no encontrado\"}");
    }

    private void flights(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        if ("/api/flights".equals(path) || "/api/flights/".equals(path)) {
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 200, "application/json", flightPlanService.listFlightsJson());
                    return;
                }

                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    FlightPlanService.FlightPlanUpdate update = new FlightPlanService.FlightPlanUpdate(
                            readRequiredString(ORIGIN_AIRPORT_CODE, body, "originAirportCode"),
                            readRequiredString(DESTINATION_AIRPORT_CODE, body, "destinationAirportCode"),
                            readRequiredString(DEPARTURE_TIME_LOCAL, body, "departureTimeLocal"),
                            readRequiredString(ARRIVAL_TIME_LOCAL, body, "arrivalTimeLocal"),
                            readRequiredString(DEPARTURE_TIME_UTC, body, "departureTimeUtc"),
                            readRequiredString(ARRIVAL_TIME_UTC, body, "arrivalTimeUtc"),
                            readRequiredInt(CAPACITY, body, "capacity"),
                            "SCHEDULED"
                    );
                    send(exchange, 201, "application/json", flightPlanService.createFlight(update));
                    return;
                }

                send(exchange, 405, "application/json", "{\"error\":\"Use GET o POST\"}");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudo procesar el vuelo\"}");
            }
            return;
        }

        Matcher flightMatcher = FLIGHT_PATH.matcher(path);
        if (flightMatcher.matches()) {
            if (!"PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"Use PATCH\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                FlightPlanService.FlightPlanUpdate update = new FlightPlanService.FlightPlanUpdate(
                        readRequiredString(ORIGIN_AIRPORT_CODE, body, "originAirportCode"),
                        readRequiredString(DESTINATION_AIRPORT_CODE, body, "destinationAirportCode"),
                        readRequiredString(DEPARTURE_TIME_LOCAL, body, "departureTimeLocal"),
                        readRequiredString(ARRIVAL_TIME_LOCAL, body, "arrivalTimeLocal"),
                        readRequiredString(DEPARTURE_TIME_UTC, body, "departureTimeUtc"),
                        readRequiredString(ARRIVAL_TIME_UTC, body, "arrivalTimeUtc"),
                        readRequiredInt(CAPACITY, body, "capacity"),
                        readRequiredString(FLIGHT_STATUS_FIELD, body, "status")
                );
                send(exchange, 200, "application/json",
                        flightPlanService.updateFlight(flightMatcher.group(1), update));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudo actualizar el vuelo\"}");
            }
            return;
        }

        send(exchange, 404, "application/json", "{\"error\":\"Endpoint no encontrado\"}");
    }

    private void shipments(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        if (!"/api/shipments".equals(path) && !"/api/shipments/".equals(path) && !"/api/shipments/batch".equals(path)) {
            send(exchange, 404, "application/json", "{\"error\":\"Endpoint no encontrado\"}");
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                && ("/api/shipments".equals(path) || "/api/shipments/".equals(path))) {
            try {
                String date = queryParams(exchange).get("date");
                send(exchange, 200, "application/json", shipmentService.listShipmentsForDate(date));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"No se pudieron cargar los envios\"}");
            }
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", "{\"error\":\"Use GET o POST\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if ("/api/shipments/batch".equals(path)) {
                ShipmentService.ShipmentBatchCreateRequest request = new ShipmentService.ShipmentBatchCreateRequest(
                        readRequiredString(ORIGIN_AIRPORT_CODE, body, "originAirportCode"),
                        readRequiredBase64String(FILE_CONTENT_BASE64, body, "fileContentBase64")
                );
                send(exchange, 201, "application/json", shipmentService.createShipmentsBatch(request));
                return;
            }

            ShipmentService.ShipmentCreateRequest request = new ShipmentService.ShipmentCreateRequest(
                    readRequiredString(ORIGIN_AIRPORT_CODE, body, "originAirportCode"),
                    readRequiredString(DESTINATION_AIRPORT_CODE, body, "destinationAirportCode"),
                    readRequiredString(DEPARTURE_DATE, body, "departureDate"),
                    readRequiredInt(BAGGAGE_COUNT, body, "baggageCount"),
                    readRequiredString(SHIPMENT_ID, body, "shipmentId")
            );
            String response = shipmentService.createShipment(request);
            realtimeSimulationService.syncRegisteredShipmentsFromDatabase();
            send(exchange, 201, "application/json", response);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "application/json", "{\"error\":\"No se pudo registrar el envio\"}");
        }
    }

    private void realtime(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            if ("/api/realtime/start".equals(path) && "POST".equalsIgnoreCase(method)) {
                String startDate = readString(START_DATE, body, "");
                String startTime = readString(START_TIME, body, "00:00");
                int days = readInt(DAYS, body, 5);
                String timeZone = readString(TIME_ZONE, body, "");
                if (startDate.isBlank()) {
                    send(exchange, 200, "application/json",
                            realtimeSimulationService.startAtCurrentTime(days, timeZone));
                } else {
                    send(exchange, 200, "application/json",
                            realtimeSimulationService.start(startDate, days, startTime, timeZone));
                }
                return;
            }

            if ("/api/realtime/current".equals(path) && "GET".equalsIgnoreCase(method)) {
                send(exchange, 200, "application/json", realtimeSimulationService.currentRealtime());
                return;
            }

            Matcher stateMatcher = Pattern.compile("^/api/realtime/([^/]+)$").matcher(path);
            if (stateMatcher.matches() && "GET".equalsIgnoreCase(method)) {
                send(exchange, 200, "application/json", realtimeSimulationService.state(stateMatcher.group(1)));
                return;
            }

            Matcher tickMatcher = Pattern.compile("^/api/realtime/([^/]+)/tick$").matcher(path);
            if (tickMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                int steps = readInt(STEPS, body, 1);
                int expectedTick = readInt(EXPECTED_TICK, body, -1);
                send(exchange, 200, "application/json", realtimeSimulationService.advance(tickMatcher.group(1), steps, expectedTick));
                return;
            }

            Matcher pauseMatcher = Pattern.compile("^/api/realtime/([^/]+)/pause$").matcher(path);
            if (pauseMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                Boolean paused = readBoolean(PAUSED, body);
                if (paused == null) {
                    send(exchange, 400, "application/json", "{\"error\":\"Envie paused como true o false\"}");
                    return;
                }
                send(exchange, 200, "application/json",
                        realtimeSimulationService.pauseRealtime(pauseMatcher.group(1), paused));
                return;
            }

            Matcher cancelMatcher = Pattern.compile("^/api/realtime/([^/]+)/cancel-flight$").matcher(path);
            if (cancelMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                String flightId = readString(FLIGHT_ID, body, "");
                send(exchange, 200, "application/json", realtimeSimulationService.cancelFlight(cancelMatcher.group(1), flightId));
                return;
            }

            send(exchange, 404, "application/json", "{\"error\":\"Endpoint de tiempo real no encontrado\"}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "application/json", "{\"error\":\"No se pudo ejecutar tiempo real\"}");
        }
    }

    private void batchSimulation(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            if ("/api/simulations/batch/start".equals(path) && "POST".equalsIgnoreCase(method)) {
                String startDate = readString(START_DATE, body, "20260102");
                int days = readInt(DAYS, body, 5);
                String startTime = readString(START_TIME, body, "00:00");
                String timeZone = readString(TIME_ZONE, body, "");
                String clientId = readString(CLIENT_ID, body, "");
                String controlToken = readString(CONTROL_TOKEN, body, "");
                send(exchange, 200, "application/json",
                        realtimeSimulationService.startBatchSimulation(startDate, days, startTime, timeZone,
                                clientId, controlToken));
                return;
            }

            if ("/api/simulations/batch/current".equals(path) && "GET".equalsIgnoreCase(method)) {
                send(exchange, 200, "application/json", realtimeSimulationService.currentSimulation());
                return;
            }

            Matcher stateMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)$").matcher(path);
            if (stateMatcher.matches() && "GET".equalsIgnoreCase(method)) {
                send(exchange, 200, "application/json", realtimeSimulationService.state(stateMatcher.group(1)));
                return;
            }

            Matcher advanceMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)/advance$").matcher(path);
            if (advanceMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                int steps = readInt(STEPS, body, RealtimeSimulationService.BATCH_MINUTES);
                int expectedTick = readInt(EXPECTED_TICK, body, -1);
                String clientId = readString(CLIENT_ID, body, "");
                String controlToken = readString(CONTROL_TOKEN, body, "");
                send(exchange, 200, "application/json",
                        realtimeSimulationService.advance(advanceMatcher.group(1), steps, expectedTick,
                                clientId, controlToken));
                return;
            }

            Matcher stopMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)/stop$").matcher(path);
            if (stopMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                String clientId = readString(CLIENT_ID, body, "");
                String controlToken = readString(CONTROL_TOKEN, body, "");
                send(exchange, 200, "application/json",
                        realtimeSimulationService.stopBatchSimulation(stopMatcher.group(1), clientId, controlToken));
                return;
            }

            Matcher pauseMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)/pause$").matcher(path);
            if (pauseMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                String clientId = readString(CLIENT_ID, body, "");
                String controlToken = readString(CONTROL_TOKEN, body, "");
                Boolean paused = readBoolean(PAUSED, body);
                if (paused == null) {
                    send(exchange, 400, "application/json", "{\"error\":\"Envie paused como true o false\"}");
                    return;
                }
                send(exchange, 200, "application/json",
                        realtimeSimulationService.pauseBatchSimulation(
                                pauseMatcher.group(1), paused, clientId, controlToken));
                return;
            }

            Matcher shipmentsMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)/shipments$").matcher(path);
            if (shipmentsMatcher.matches() && "GET".equalsIgnoreCase(method)) {
                Map<String, String> query = queryParams(exchange);
                send(exchange, 200, "application/json",
                        realtimeSimulationService.batchShipments(
                                shipmentsMatcher.group(1),
                                queryInt(query, "page", 1),
                                queryInt(query, "pageSize", 25),
                                query.getOrDefault("search", ""),
                                query.getOrDefault("origin", ""),
                                query.getOrDefault("destination", ""),
                                query.getOrDefault("status", "")));
                return;
            }

            Matcher cancelMatcher = Pattern.compile("^/api/simulations/batch/([^/]+)/cancel-flight$").matcher(path);
            if (cancelMatcher.matches() && "POST".equalsIgnoreCase(method)) {
                String flightId = readString(FLIGHT_ID, body, "");
                send(exchange, 200, "application/json",
                        realtimeSimulationService.cancelFlight(cancelMatcher.group(1), flightId));
                return;
            }

            send(exchange, 404, "application/json", "{\"error\":\"Endpoint de simulacion por lotes no encontrado\"}");
        } catch (SecurityException e) {
            send(exchange, 403, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "application/json", "{\"error\":\"No se pudo ejecutar la simulacion por lotes\"}");
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(exchange, 400, "text/plain", "Bad request");
            return;
        }

        String resourcePath = "/web" + path;
        try (InputStream in = SimulatorServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Not found");
                return;
            }
            send(exchange, 200, contentType(path), in.readAllBytes());
        }
    }

    private boolean preflight(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        addCors(headers);
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void addCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,PATCH,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String contentType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "text/html";
    }

    private Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return params;

        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private int queryInt(Map<String, String> query, String key, int fallback) {
        String value = query.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String readString(Pattern pattern, String body, String fallback) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private int readInt(Pattern pattern, String body, int fallback) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String readRequiredString(Pattern pattern, String body, String name) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Falta campo: " + name);
        }
        return unescape(matcher.group(1));
    }

    private int readRequiredInt(Pattern pattern, String body, String name) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Falta campo: " + name);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String readRequiredBase64String(Pattern pattern, String body, String name) {
        String encoded = readRequiredString(pattern, body, name);
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Campo invalido: " + name);
        }
    }

    private double readRequiredDouble(Pattern pattern, String body, String name) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Falta campo: " + name);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private Boolean readBoolean(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : null;
    }

    private String escape(String message) {
        return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescape(String value) {
        return value == null ? "" : value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
