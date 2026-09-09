package io.hackinvent.scada.play;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import io.hackinvent.scada.core.*;
import io.hackinvent.scada.opcua.*;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import playwebmcp.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Play lifecycle adapter and common tool catalogue. Business rules live in ScadaEngine. */
@Singleton
public final class ScadaRuntime {
    private static final Logger log = LoggerFactory.getLogger(ScadaRuntime.class);
    private final ScadaEngine engine;
    private final MiloScadaConnector connector;
    private final DemoOpcUaServer simulator;
    private final Config config;
    private final ScheduledExecutorService refresh;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    @Inject public ScadaRuntime(Config config, ApplicationLifecycle lifecycle) {
        this.config = config;
        // Parse everything used during startup before opening sockets or scheduling work.
        boolean simulated = config.getBoolean("scada.simulator.enabled");
        int port = config.getInt("scada.simulator.port");
        Duration staleAfter = config.getDuration("scada.stale-after");
        Duration refreshInterval = config.getDuration("scada.refresh-interval");
        if (staleAfter.isNegative() || staleAfter.isZero() || refreshInterval.isNegative() || refreshInterval.isZero())
            throw new IllegalArgumentException("SCADA stale and refresh durations must be positive");
        long interval = Math.max(500, refreshInterval.toMillis());
        OpcUaConnectorConfig connection = simulated
            ? OpcUaConnectorConfig.localDemo("opc.tcp://127.0.0.1:" + port + "/scada")
            : new OpcUaConnectorConfig(config.getString("scada.opcua.endpoint"),
                config.getString("scada.opcua.security-policy"), config.getString("scada.opcua.security-mode"),
                Path.of(config.getString("scada.opcua.keystore")), config.getString("scada.opcua.keystore-password"),
                config.getString("scada.opcua.key-alias"), Path.of(config.getString("scada.opcua.trust-directory")),
                config.getString("scada.opcua.application-uri"), config.getString("scada.opcua.username"),
                config.getString("scada.opcua.password"), config.getDouble("scada.opcua.publish-interval-ms"));
        List<TagDefinition> tags = config.getConfigList("scada.tags").stream().map(c -> new TagDefinition(
            c.getString("id"), c.getString("nodeId"), c.getString("label"), c.hasPath("unit") ? c.getString("unit") : "",
            c.getString("dataType"), c.hasPath("writable") && c.getBoolean("writable"))).toList();
        List<CommandDefinition> commands = config.getConfigList("scada.commands").stream().map(c -> new CommandDefinition(
            c.getString("id"), c.getString("label"), c.getString("description"), c.getString("tagId"), c.getBoolean("requiresValue"),
            c.hasPath("fixedValue") ? c.getAnyRef("fixedValue") : null,
            c.hasPath("min") ? c.getDouble("min") : null, c.hasPath("max") ? c.getDouble("max") : null)).toList();
        MiloScadaConnector createdConnector = null;
        DemoOpcUaServer createdSimulator = null;
        ScheduledExecutorService createdRefresh = null;
        try {
            createdConnector = new MiloScadaConnector(connection);
            engine = new ScadaEngine(createdConnector, tags, commands, staleAfter);
            createdSimulator = simulated ? new DemoOpcUaServer(port) : null;
            createdRefresh = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "scada-state-refresh"); thread.setDaemon(true); return thread;
            });
            connector = createdConnector;
            simulator = createdSimulator;
            refresh = createdRefresh;
            lifecycle.addStopHook(() -> stopOwned(connector, simulator, refresh));
            refresh.scheduleWithFixedDelay(() -> {
                if (engine.isConnected() && refreshing.compareAndSet(false, true)) {
                    try { engine.read(List.of()).whenComplete((ignored, error) -> refreshing.set(false)); }
                    catch (RuntimeException ignored) { refreshing.set(false); }
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
            CompletionStage<Void> starting = simulator == null ? CompletableFuture.completedFuture(null) : simulator.start();
            starting.thenCompose(ignored -> engine.start()).whenComplete((ignored, error) -> {
                if (error != null) log.error("SCADA startup failed; connection remains unavailable", error);
                else log.info("SCADA connected with {} configured tags; simulator={}", tags.size(), simulated());
            });
        } catch (RuntimeException | Error failure) {
            try { stopOwned(createdConnector, createdSimulator, createdRefresh).toCompletableFuture().join(); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static CompletionStage<Void> stopOwned(MiloScadaConnector connector, DemoOpcUaServer simulator,
                                                    ScheduledExecutorService refresh) {
        if (refresh != null) refresh.shutdownNow();
        CompletionStage<Void> disconnected = connector == null
            ? CompletableFuture.completedFuture(null) : connector.shutdown();
        return disconnected.handle((ignored, connectorError) -> connectorError).thenCompose(connectorError -> {
            CompletionStage<Void> stopped = simulator == null ? CompletableFuture.completedFuture(null) : simulator.stop();
            return stopped.handle((ignored, simulatorError) -> {
                if (connectorError != null) {
                    if (simulatorError != null) connectorError.addSuppressed(simulatorError);
                    throw new CompletionException(connectorError);
                }
                if (simulatorError != null) throw new CompletionException(simulatorError);
                return (Void) null;
            });
        });
    }

    public boolean simulated() { return simulator != null; }
    public boolean assistantEnabled() {
        return !config.getString("scada.openai.api-key").isBlank() && !config.getString("scada.openai.model").isBlank();
    }
    public ObjectNode catalog(boolean operator) {
        ObjectNode result = Json.newObject();
        result.set("tags", Json.toJson(engine.tags()));
        result.set("commands", Json.toJson(engine.commands()));
        result.putObject("access").put("canCommand", operator).put("canUseAssistant", assistantEnabled()).put("demo", simulated());
        result.putObject("connection").put("connected", engine.isConnected()).put("simulated", simulated());
        return result;
    }
    public CompletionStage<JsonNode> snapshot() {
        ObjectNode result = Json.newObject().put("connected", engine.isConnected()).put("simulated", simulated())
            .put("timestamp", Instant.now().toString());
        result.set("values", valuesJson(engine.snapshot()));
        return CompletableFuture.completedFuture(result);
    }
    public CompletionStage<JsonNode> executeCommand(String id, JsonNode args, boolean operator, String requestId) {
        requireObject(args);
        rejectUnknown(args, Set.of("value", "requestId", "commandId"));
        if (args.has("commandId") && !id.equals(requiredText(args, "commandId")))
            throw new ScadaException("invalid_arguments", 400, "Conflicting command identifiers");
        String bodyId = args.has("requestId") ? requiredText(args, "requestId") : null;
        if (requestId != null && bodyId != null && !requestId.equals(bodyId))
            throw new ScadaException("invalid_arguments", 400, "Conflicting request identifiers");
        String key = requestId != null ? requestId : bodyId;
        Object value = null;
        if (args.has("value")) {
            JsonNode v = args.get("value");
            if (v.isBoolean()) value = v.booleanValue();
            else if (v.isNumber()) value = v.numberValue();
            else if (v.isTextual()) value = v.textValue();
            else throw new ScadaException("invalid_arguments", 400, "Command value must be a scalar");
        }
        return engine.execute(id, value, operator, key).thenApply(result -> {
            log.info("SCADA command accepted commandId={} requestId={}", id, key);
            return Json.newObject().put("commandId", result.commandId()).put("requestId", result.requestId())
                .put("status", result.status()).put("timestamp", result.timestamp().toString())
                .set("value", Json.toJson(result.value()));
        });
    }

    public CompletionStage<JsonNode> callTool(String name, JsonNode args, boolean operator, String requestId) {
        requireObject(args);
        switch (name) {
            case "scada_list_tags" -> {
                rejectUnknown(args, Set.of());
                ObjectNode result = Json.newObject(); result.set("tags", Json.toJson(engine.tags()));
                return CompletableFuture.completedFuture(result);
            }
            case "scada_list_commands" -> {
                rejectUnknown(args, Set.of());
                ObjectNode result = Json.newObject().put("canExecute", operator);
                result.set("commands", Json.toJson(engine.commands())); return CompletableFuture.completedFuture(result);
            }
            case "scada_read_tags" -> {
                rejectUnknown(args, Set.of("ids"));
                List<String> ids = new ArrayList<>();
                if (args.has("ids")) {
                    if (!args.get("ids").isArray()) throw new ScadaException("invalid_arguments", 400, "ids must be an array");
                    for (JsonNode id : args.get("ids")) {
                        if (!id.isTextual()) throw new ScadaException("invalid_arguments", 400, "Each tag identifier must be a string");
                        ids.add(id.textValue());
                    }
                }
                return engine.read(ids).thenApply(values -> Json.newObject().set("values", valuesJson(values)));
            }
            case "scada_execute_command" -> {
                return executeCommand(requiredText(args, "commandId"), args, operator, requestId);
            }
            default -> throw new ScadaException("unknown_tool", 404, "Unknown SCADA tool");
        }
    }

    public List<Tool> browserTools() {
        List<Tool> tools = new ArrayList<>();
        for (JsonNode tool : mcpTools(true)) {
            String handler = switch (tool.get("name").asText()) {
                case "scada_list_tags" -> "listTags";
                case "scada_read_tags" -> "readTags";
                case "scada_list_commands" -> "listCommands";
                default -> "executeCommand";
            };
            tools.add(Tool.create(tool.get("name").asText(), tool.get("description").asText(),
                tool.get("inputSchema").toString(), handler).withReadOnly(!handler.equals("executeCommand")));
        }
        return List.copyOf(tools);
    }

    public JsonNode mcpTools(boolean operator) {
        ArrayNode tools = Json.newArray();
        tools.add(tool("scada_list_tags", "List configured SCADA measurements with their types and units", emptySchema(), true));
        ObjectNode reads = emptySchema();
        reads.withObject("/properties").putObject("ids").put("type", "array").put("maxItems", 256)
            .putObject("items").put("type", "string");
        tools.add(tool("scada_read_tags", "Read current OPC UA values, quality and timestamps. Omit ids to read all configured tags", reads, true));
        tools.add(tool("scada_list_commands", "List configured commands and their allowed values. Listing does not execute commands", emptySchema(), true));
        if (operator) {
            ObjectNode execute = emptySchema();
            ObjectNode properties = execute.withObject("/properties");
            properties.putObject("commandId").put("type", "string");
            ArrayNode types = properties.putObject("value").putArray("type");
            types.add("number").add("boolean").add("string");
            properties.putObject("requestId").put("type", "string").put("pattern", "^[A-Za-z0-9_.:-]{1,128}$")
                .put("description", "Unique operation identifier; reuse the same identifier when checking a retry. Required unless Idempotency-Key is supplied over HTTP");
            execute.putArray("required").add("commandId");
            tools.add(tool("scada_execute_command", "Execute an explicitly configured command with operator permission. Acknowledgement means OPC UA accepted the write; inspect current state for its effect", execute, false));
        }
        return tools;
    }
    private static ObjectNode emptySchema() {
        ObjectNode schema = Json.newObject().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties"); return schema;
    }
    private static ObjectNode tool(String name, String description, JsonNode schema, boolean readOnly) {
        ObjectNode tool = Json.newObject().put("name", name).put("description", description);
        tool.set("inputSchema", schema);
        tool.putObject("annotations").put("readOnlyHint", readOnly).put("destructiveHint", !readOnly).put("openWorldHint", false);
        return tool;
    }
    public static ObjectNode valuesJson(Map<String, TagValue> values) {
        ObjectNode result = Json.newObject();
        values.forEach((id, value) -> {
            ObjectNode node = result.putObject(id);
            node.set("value", Json.toJson(value.value())); node.put("quality", value.quality());
            if (value.statusCode() == null) node.putNull("statusCode"); else node.put("statusCode", value.statusCode());
            timestamp(node, "sourceTimestamp", value.sourceTimestamp());
            timestamp(node, "serverTimestamp", value.serverTimestamp()); timestamp(node, "receivedAt", value.receivedAt());
        });
        return result;
    }
    private static void timestamp(ObjectNode node, String name, Instant value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }
    public static void requireObject(JsonNode args) {
        if (args == null || !args.isObject()) throw new ScadaException("invalid_arguments", 400, "A JSON object is required");
    }
    public static String requiredText(JsonNode args, String key) {
        if (!args.has(key) || !args.get(key).isTextual() || args.get(key).asText().isBlank())
            throw new ScadaException("invalid_arguments", 400, key + " must be a nonempty string");
        return args.get(key).asText();
    }
    private static void rejectUnknown(JsonNode args, Set<String> allowed) {
        args.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new ScadaException("invalid_arguments", 400, "Unexpected argument: " + key);
        });
    }
}
