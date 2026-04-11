package io.github.dajac.kaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.common.TopicPartition;

public class SimulatorRestServer {

    private final HttpServer server;
    private final SimulatorEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public SimulatorRestServer(int port, SimulatorEngine engine) throws IOException {
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", this::handleRoot);
        server.createContext("/api/simulator/status", this::handleStatus);
        server.createContext("/api/simulator/start", this::handleStart);
        server.createContext("/api/simulator/stop", this::handleStop);
        server.createContext("/api/simulator/group", this::handleGroup);
        server.createContext("/api/simulator/members/", this::handleMembers);
        server.createContext("/api/simulator/chaos/", this::handleChaos);
    }

    public void start() {
        server.start();
        System.out.println("REST server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        InputStream is = getClass().getResourceAsStream("/static/simulator.html");
        if (is == null) {
            sendResponse(exchange, 404, "Not Found");
            return;
        }
        byte[] html = is.readAllBytes();
        is.close();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, html.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("phase", engine.phase().name());
        if (engine.config() != null) {
            result.put("config", engine.config());
        }
        result.put("chaosRunning", engine.isChaosRunning());
        sendJson(exchange, 200, result);
    }

    private void handleStart(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        try {
            SimulatorConfig config = mapper.readValue(exchange.getRequestBody(), SimulatorConfig.class);
            engine.start(config);
            sendJson(exchange, 200, Map.of("status", "started"));
        } catch (IllegalStateException e) {
            sendJson(exchange, 409, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        engine.stop();
        sendJson(exchange, 200, Map.of("status", "stopped"));
    }

    private void handleGroup(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        if (engine.phase() != SimulatorEngine.Phase.RUNNING) {
            sendJson(exchange, 200, Map.of("phase", engine.phase().name()));
            return;
        }

        AdminPoller poller = engine.adminPoller();
        SimulatorGroupState adminState = poller != null ? poller.lastState() : null;

        // Merge admin state with slot data.
        Map<String, Object> result = new HashMap<>();
        result.put("phase", engine.phase().name());

        if (adminState != null) {
            result.put("groupEpoch", adminState.groupEpoch);
            result.put("targetAssignmentEpoch", adminState.targetAssignmentEpoch);
            result.put("groupState", adminState.groupState);
        } else {
            result.put("groupState", "UNKNOWN");
        }

        // Build merged member list: all slots + admin data.
        List<Map<String, Object>> members = new ArrayList<>();
        List<ConsumerSlot> slots = engine.slots();
        Map<String, SimulatorGroupState.MemberState> adminMembers = new HashMap<>();
        if (adminState != null && adminState.members != null) {
            for (SimulatorGroupState.MemberState ms : adminState.members) {
                adminMembers.put(ms.clientId, ms);
            }
        }

        for (ConsumerSlot slot : slots) {
            Map<String, Object> m = new HashMap<>();
            m.put("slotIndex", slot.index());
            m.put("clientId", slot.clientId());
            m.put("subscribed", slot.desiredSubscribed().get());
            m.put("blocked", slot.blocked().get());
            m.put("partitioned", slot.partitioned().get());
            m.put("processingDelayMs", slot.processingDelayMs().get());

            // Consumer's local view of its assignment, captured via rebalance listener.
            Set<String> localAssignment = slot.localAssignment().stream()
                .map(tp -> tp.topic() + "-" + tp.partition())
                .collect(Collectors.toSet());
            m.put("localAssignment", localAssignment);

            SimulatorGroupState.MemberState adminMember = adminMembers.get(slot.clientId());
            if (adminMember != null) {
                m.put("memberId", adminMember.memberId);
                m.put("instanceId", adminMember.instanceId);
                m.put("memberEpoch", adminMember.memberEpoch);
                // Override state to "Stopping" if unsubscribed but still in the group.
                if (!slot.desiredSubscribed().get()) {
                    m.put("state", "Stopping");
                } else {
                    m.put("state", adminMember.state);
                }
                m.put("assignment", adminMember.assignment);
                m.put("targetAssignment", adminMember.targetAssignment);
            } else {
                m.put("memberId", "");
                m.put("instanceId", null);
                m.put("memberEpoch", -1);
                m.put("state", slot.desiredSubscribed().get() ? "Joining" : "Stopped");
                m.put("assignment", Set.of());
                m.put("targetAssignment", Set.of());
            }

            members.add(m);
        }

        result.put("members", members);
        sendJson(exchange, 200, result);
    }

    private void handleMembers(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        // Path format: /api/simulator/members/{index}/{action}
        String[] parts = path.split("/");
        // parts: ["", "api", "simulator", "members", "{index}", "{action}"]
        if (parts.length < 6) {
            sendJson(exchange, 400, Map.of("error", "Invalid path"));
            return;
        }

        int index;
        try {
            index = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid index"));
            return;
        }

        List<ConsumerSlot> slots = engine.slots();
        if (index < 0 || index >= slots.size()) {
            sendJson(exchange, 404, Map.of("error", "Slot not found"));
            return;
        }

        ConsumerSlot slot = slots.get(index);
        String action = parts[5];

        switch (action) {
            case "subscribe":
                slot.desiredSubscribed().set(true);
                sendJson(exchange, 200, Map.of("status", "subscribed"));
                break;
            case "unsubscribe":
                slot.desiredSubscribed().set(false);
                sendJson(exchange, 200, Map.of("status", "unsubscribed"));
                break;
            case "block":
                slot.blocked().set(true);
                sendJson(exchange, 200, Map.of("status", "blocked"));
                break;
            case "unblock":
                slot.blocked().set(false);
                sendJson(exchange, 200, Map.of("status", "unblocked"));
                break;
            case "partition":
                slot.partitioned().set(true);
                PartitionFilter.block(slot.clientId());
                sendJson(exchange, 200, Map.of("status", "partitioned"));
                break;
            case "heal":
                slot.partitioned().set(false);
                PartitionFilter.unblock(slot.clientId());
                sendJson(exchange, 200, Map.of("status", "healed"));
                break;
            case "delay":
                JsonNode body = mapper.readTree(exchange.getRequestBody());
                int delayMs = body.get("delayMs").asInt();
                slot.processingDelayMs().set(delayMs);
                sendJson(exchange, 200, Map.of("status", "delay set", "delayMs", delayMs));
                break;
            default:
                sendJson(exchange, 400, Map.of("error", "Unknown action: " + action));
        }
    }

    private void handleChaos(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/start")) {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            int minMs = body.has("intervalMinMs") ? body.get("intervalMinMs").asInt() : 10000;
            int maxMs = body.has("intervalMaxMs") ? body.get("intervalMaxMs").asInt() : 30000;
            engine.startChaos(minMs, maxMs);
            sendJson(exchange, 200, Map.of("status", "chaos started"));
        } else if (path.endsWith("/stop")) {
            engine.stopChaos();
            sendJson(exchange, 200, Map.of("status", "chaos stopped"));
        } else {
            sendJson(exchange, 400, Map.of("error", "Unknown chaos action"));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object obj) throws IOException {
        byte[] json = mapper.writeValueAsBytes(obj);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
