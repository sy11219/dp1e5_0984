package org.e5.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;

public class SimulatorServer {
    private static final int DEFAULT_PORT = 8080;
    private static final Pattern START_DATE = Pattern.compile("\"startDate\"\\s*:\\s*\"(\\d{8})\"");
    private static final Pattern DAYS = Pattern.compile("\"days\"\\s*:\\s*(\\d+)");
    private static final Pattern STEPS = Pattern.compile("\"steps\"\\s*:\\s*(\\d+)");
    private static final Pattern FLIGHT_ID = Pattern.compile("\"flightId\"\\s*:\\s*\"([^\"]+)\"");

    private final SimulationService simulationService = new SimulationService();
    private final RealtimeSimulationService realtimeSimulationService = new RealtimeSimulationService();

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        SimulatorServer app = new SimulatorServer();
        app.start(port);
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
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/health", this::health);
        server.createContext("/api/simulations/alns", this::runAlns);
        server.createContext("/api/realtime", this::realtime);
        server.createContext("/", this::staticFile);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.printf("Simulador ALNS listo en http://localhost:%d/%n", port);
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop(0);
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        send(exchange, 200, "application/json", "{\"status\":\"ok\",\"service\":\"ALNS simulator\"}");
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
            int days = readInt(DAYS, body, 3);
            String result = simulationService.runAlns(startDate, days);
            send(exchange, 200, "application/json", result);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "application/json", "{\"error\":\"No se pudo ejecutar la simulacion ALNS\"}");
        }
    }

    private void realtime(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            if ("/api/realtime/start".equals(path) && "POST".equalsIgnoreCase(method)) {
                String startDate = readString(START_DATE, body, "20260102");
                int days = readInt(DAYS, body, 3);
                send(exchange, 200, "application/json", realtimeSimulationService.start(startDate, days));
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
                send(exchange, 200, "application/json", realtimeSimulationService.advance(tickMatcher.group(1), steps));
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
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String contentType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "text/html";
    }

    private String readString(Pattern pattern, String body, String fallback) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private int readInt(Pattern pattern, String body, int fallback) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String escape(String message) {
        return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
