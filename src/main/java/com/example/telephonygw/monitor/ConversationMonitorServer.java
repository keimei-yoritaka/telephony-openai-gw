package com.example.telephonygw.monitor;

import com.example.telephonygw.config.GatewayConfig.MonitorConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.session.CallSession;
import com.example.telephonygw.session.CallSessionManager;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ConversationMonitorServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ConversationMonitorServer.class.getName());
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final int SSE_CLIENT_QUEUE_CAPACITY = 200;
    private static final long SSE_KEEPALIVE_SECONDS = 15L;
    private static final String MONITOR_RESOURCE_ROOT = "monitor/";
    private static final Path MONITOR_SOURCE_ROOT = Path.of("src/main/resources/monitor");
    private static final Path RESOURCE_SOURCE_ROOT = Path.of("resources");

    private final MonitorConfig config;
    private final ConversationEventHub eventHub;
    private final CallSessionManager sessionManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private HttpServer server;
    private ExecutorService executor;

    public ConversationMonitorServer(MonitorConfig config, ConversationEventHub eventHub, CallSessionManager sessionManager) {
        this.config = config;
        this.eventHub = eventHub;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
            server.createContext("/", this::handleRoot);
            server.createContext("/api/sessions", this::handleSessions);
            server.createContext("/api/sessions/latest", this::handleLatestSession);
            server.createContext("/events", this::handleEvents);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.start();
            LOG.log(System.Logger.Level.INFO,
                    "Started conversation monitor server: bind={0}, port={1}, maxEvents={2}",
                    config.bindAddress(), config.port(), config.maxEvents());
            GatewayEventLogger.info(LOG, "conversation_monitor_started",
                    "bind", config.bindAddress(),
                    "port", config.port(),
                    "maxEvents", config.maxEvents());
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("Failed to start conversation monitor server", e);
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false) && server != null) {
            server.stop(1);
            server = null;
            executor.shutdownNow();
            executor = null;
            LOG.log(System.Logger.Level.INFO, "Stopped conversation monitor server");
            GatewayEventLogger.info(LOG, "conversation_monitor_stopped");
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed\n", "text/plain; charset=utf-8");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/", "/index.html" -> sendAsset(exchange, "index.html", "text/html; charset=utf-8");
            case "/fabicon.ico", "/favicon.ico" -> sendAsset(exchange, "fabicon.ico", "image/x-icon");
            case "/background.png" -> sendAsset(exchange, "background.png", "image/png");
            case "/assets/app.css" -> sendAsset(exchange, "app.css", "text/css; charset=utf-8");
            case "/assets/app.js" -> sendAsset(exchange, "app.js", "application/javascript; charset=utf-8");
            default -> sendText(exchange, 404, "Not Found\n", "text/plain; charset=utf-8");
        }
    }

    private void handleSessions(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/api/sessions".equals(path)) {
            sendJson(exchange, 200, recentSessionsJson());
            return;
        }
        if (path.startsWith("/api/sessions/")) {
            String sessionId = URLDecoder.decode(path.substring("/api/sessions/".length()), StandardCharsets.UTF_8);
            sendJson(exchange, 200, sessionJson(sessionId, eventHub.eventsForSession(sessionId)));
            return;
        }
        sendJson(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private void handleLatestSession(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String latestSessionId = eventHub.latestSessionId();
        List<ConversationEvent> events = latestSessionId.isBlank()
                ? List.of()
                : eventHub.eventsForSession(latestSessionId);
        sendJson(exchange, 200, sessionJson(latestSessionId, events));
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        SseClient client = new SseClient();
        eventHub.addSubscriber(client);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            writeSse(output, "retry: 2000\n\n");
            while (running.get()) {
                ConversationEvent event = client.poll();
                if (event == null) {
                    writeSse(output, ": keepalive\n\n");
                    continue;
                }
                writeSse(output,
                        "id: " + event.id() + "\n"
                                + "event: transcript\n"
                                + "data: " + eventJson(event) + "\n\n");
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Conversation monitor SSE client disconnected: error={0}", e.getMessage());
        } finally {
            eventHub.removeSubscriber(client);
            exchange.close();
        }
    }

    private static String sessionJson(String sessionId, List<ConversationEvent> events) {
        StringBuilder json = new StringBuilder();
        json.append("{\"latestSessionId\":\"").append(json(sessionId)).append("\",\"events\":[");
        appendEvents(json, events);
        json.append("]}");
        return json.toString();
    }

    private String recentSessionsJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"sessions\":[");
        List<CallSession> sessions = sessionManager.recentSessions();
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CallSession session = sessions.get(i);
            json.append("{\"sessionId\":\"").append(json(session.sessionId())).append("\"")
                    .append(",\"slotId\":\"").append(json(session.slotId())).append("\"")
                    .append(",\"state\":\"").append(json(session.state().name().toLowerCase())).append("\"")
                    .append(",\"startedAt\":\"").append(json(TIMESTAMP_FORMATTER.format(session.startedAt()))).append("\"")
                    .append(",\"endedAt\":\"").append(json(formatInstant(session.endedAt()))).append("\"")
                    .append("}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String formatInstant(java.time.Instant instant) {
        return instant == null ? "" : TIMESTAMP_FORMATTER.format(instant);
    }

    private static void appendEvents(StringBuilder json, List<ConversationEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(eventJson(events.get(i)));
        }
    }

    private static String eventJson(ConversationEvent event) {
        return "{"
                + "\"id\":" + event.id()
                + ",\"sessionId\":\"" + json(event.sessionId()) + "\""
                + ",\"speaker\":\"" + json(event.speaker()) + "\""
                + ",\"text\":\"" + json(event.text()) + "\""
                + ",\"timestamp\":\"" + json(TIMESTAMP_FORMATTER.format(event.timestamp())) + "\""
                + ",\"itemId\":\"" + json(event.itemId()) + "\""
                + ",\"responseId\":\"" + json(event.responseId()) + "\""
                + ",\"final\":" + event.finalTranscript()
                + "}";
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        sendText(exchange, status, body + "\n", "application/json; charset=utf-8");
    }

    private static void sendAsset(HttpExchange exchange, String resourceName, String contentType) throws IOException {
        byte[] bytes = readAsset(resourceName);
        if (bytes.length == 0) {
            sendText(exchange, 404, "Not Found\n", "text/plain; charset=utf-8");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] readAsset(String resourceName) throws IOException {
        String resourcePath = MONITOR_RESOURCE_ROOT + resourceName;
        try (InputStream resource = ConversationMonitorServer.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (resource != null) {
                return resource.readAllBytes();
            }
        }
        Path sourcePath = MONITOR_SOURCE_ROOT.resolve(resourceName).normalize();
        if (Files.isRegularFile(sourcePath)) {
            return Files.readAllBytes(sourcePath);
        }
        sourcePath = RESOURCE_SOURCE_ROOT.resolve(resourceName).normalize();
        if (Files.isRegularFile(sourcePath)) {
            return Files.readAllBytes(sourcePath);
        }
        return new byte[0];
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void writeSse(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String json(String value) {
        String safe = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(safe.length() + 16);
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class SseClient implements Consumer<ConversationEvent> {
        private final ArrayBlockingQueue<ConversationEvent> queue =
                new ArrayBlockingQueue<>(SSE_CLIENT_QUEUE_CAPACITY);

        @Override
        public void accept(ConversationEvent event) {
            if (!queue.offer(event)) {
                queue.poll();
                queue.offer(event);
            }
        }

        private ConversationEvent poll() {
            try {
                return queue.poll(SSE_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
