package io.hackinvent.scada.play;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hackinvent.scada.core.ScadaException;
import org.apache.pekko.util.ByteString;
import play.http.HttpErrorHandler;
import play.http.HttpEntity;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Stateless MCP Streamable HTTP transport using Play actions and asynchronous services.
 * Each POST receives one JSON response; no server-initiated requests, sessions or SSE streams.
 * See https://modelcontextprotocol.io/specification/2025-11-25/basic/transports.
 */
@Singleton
public final class McpController extends Controller {
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String CURRENT_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOLS = Set.of(CURRENT_PROTOCOL, "2025-06-18", "2025-03-26");
    private static final ObjectReader READER = Json.mapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final ScadaRuntime runtime;
    private final ScadaAccess access;

    @Inject
    public McpController(ScadaRuntime runtime, ScadaAccess access) {
        this.runtime = runtime;
        this.access = access;
    }

    /** Buffer limits apply while consuming the body, including chunked requests. */
    public static final class McpBodyParser extends BodyParser.TolerantText {
        @Inject
        public McpBodyParser(HttpErrorHandler errorHandler) {
            super(MAX_BODY_BYTES, errorHandler);
        }

        @Override
        protected String parse(Http.RequestHeader request, ByteString bytes) throws Exception {
            // MCP JSON is UTF-8 regardless of the content-type's optional charset parameter.
            return StandardCharsets.UTF_8.newDecoder().decode(bytes.asByteBuffer()).toString();
        }
    }

    @BodyParser.Of(McpBodyParser.class)
    public CompletionStage<Result> handle(Http.Request request) {
        Result rejected = authorize(request);
        if (rejected != null) return completed(rejected);
        if (!"POST".equals(request.method())) return completed(methodNotAllowedResponse());

        if (!request.contentType().map("application/json"::equalsIgnoreCase).orElse(false)) {
            return completed(error(415, null, -32600, "Content-Type must be application/json"));
        }
        if (!accepts(request, "application/json") || !accepts(request, "text/event-stream")) {
            return completed(error(406, null, -32600,
                    "Accept must include application/json and text/event-stream"));
        }
        String version = request.getHeaders().get("MCP-Protocol-Version").orElse("2025-03-26");
        if (!PROTOCOLS.contains(version)) {
            return completed(error(400, null, -32600, "Unsupported MCP-Protocol-Version"));
        }

        String text = request.body().asText();
        if (text == null || text.isBlank()) return completed(error(400, null, -32700, "Parse error"));
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return completed(error(413, null, -32600, "Request body exceeds 64 KiB"));
        }
        final JsonNode message;
        try {
            message = READER.readTree(text);
        } catch (Exception malformed) {
            return completed(error(400, null, -32700, "Parse error"));
        }
        if (message != null && message.isArray()) return handleBatch(request, message, version);
        return handleMessage(request, message, version);
    }

    private CompletionStage<Result> handleBatch(Http.Request request, JsonNode batch, String version) {
        // Receiving batches is mandatory in 2025-03-26 and was removed in 2025-06-18.
        if (!"2025-03-26".equals(version) || batch.isEmpty() || batch.size() > 32) {
            return completed(error(400, null, -32600, "Batch requests require protocol 2025-03-26 and 1–32 messages"));
        }
        Set<JsonNode> ids = new HashSet<>();
        for (JsonNode entry : batch) {
            JsonNode id = entry.get("id");
            if (id != null && !ids.add(id)) {
                return completed(error(400, null, -32600, "Batch request ids must be unique"));
            }
        }
        List<CompletableFuture<Result>> pending = new ArrayList<>();
        for (JsonNode entry : batch) {
            CompletionStage<Result> response = "initialize".equals(entry.path("method").asText())
                    ? completed(error(400, entry.get("id"), -32600, "Initialization must be sent separately"))
                    : handleMessage(request, entry, version);
            pending.add(response.toCompletableFuture());
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var responses = Json.newArray();
            for (CompletableFuture<Result> response : pending) {
                Result result = response.join();
                if (result.status() != 202) responses.add(Json.parse(((HttpEntity.Strict) result.body()).data().utf8String()));
            }
            return responses.isEmpty() ? noBodyAccepted() : json(200, responses);
        });
    }

    private CompletionStage<Result> handleMessage(Http.Request request, JsonNode message, String version) {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            return completed(error(400, null, -32600, "Expected one JSON-RPC 2.0 message"));
        }
        JsonNode id = message.get("id");
        if (id != null && !(id.isTextual() || id.isIntegralNumber())) {
            return completed(error(400, null, -32600, "Request id must be a string or integer"));
        }
        if (!message.has("method")) return completed(acceptResponse(message, id));
        if (!message.path("method").isTextual() || message.path("method").asText().isBlank()
                || message.has("result") || message.has("error")) {
            return completed(error(400, id, -32600, "Invalid JSON-RPC request"));
        }
        String method = message.path("method").asText();
        JsonNode params = message.path("params");
        if (!params.isMissingNode() && !params.isObject()) {
            return completed(error(400, id, -32602, "params must be an object"));
        }
        if (id == null) {
            // In particular, a tools/call notification must never execute a command.
            return completed(method.startsWith("notifications/")
                    ? noBodyAccepted()
                    : error(400, null, -32600, "This method requires a request id"));
        }
        try {
            return switch (method) {
                case "initialize" -> completed(initialize(id, params));
                case "ping" -> completed(success(id, Json.newObject()));
                case "tools/list" -> completed(listTools(id, params, access.isOperator(request)));
                case "tools/call" -> callTool(request, id, params, version);
                default -> completed(error(200, id, -32601, "Method not found"));
            };
        } catch (Exception failure) {
            return completed(error(200, id, -32603, "Internal error"));
        }
    }

    /** GET has no SSE stream; DELETE has no session to terminate. */
    public Result unsupported(Http.Request request) {
        Result rejected = authorize(request);
        return rejected != null ? rejected : methodNotAllowedResponse();
    }

    private Result authorize(Http.Request request) {
        if (!access.isAllowedOrigin(request)) return error(403, null, -32000, "Origin is not allowed");
        if (!access.canRead(request)) {
            return error(401, null, -32000, "Authentication required")
                    .withHeader("WWW-Authenticate", "Bearer realm=\"scada-mcp\"");
        }
        return null;
    }

    private Result initialize(JsonNode id, JsonNode params) {
        if (!nonemptyText(params.get("protocolVersion")) || !params.path("capabilities").isObject()
                || !params.path("clientInfo").isObject()
                || !nonemptyText(params.path("clientInfo").get("name"))
                || !nonemptyText(params.path("clientInfo").get("version"))) {
            return error(200, id, -32602, "initialize requires protocolVersion, capabilities and clientInfo");
        }
        String requested = params.path("protocolVersion").asText();
        ObjectNode result = Json.newObject();
        result.put("protocolVersion", PROTOCOLS.contains(requested) ? requested : CURRENT_PROTOCOL);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "hackinvent-play-scada").put("version", "0.1.0");
        result.put("instructions", "Use the configured SCADA catalog. Check measurement quality and freshness. "
                + "Only operators may execute configured commands; use a stable requestId for command retries.");
        return success(id, result);
    }

    private Result listTools(JsonNode id, JsonNode params, boolean operator) {
        if (params.has("cursor")) return error(200, id, -32602, "This catalog does not use pagination cursors");
        ObjectNode result = Json.newObject();
        result.set("tools", runtime.mcpTools(operator));
        return success(id, result);
    }

    private CompletionStage<Result> callTool(Http.Request request, JsonNode id, JsonNode params, String version) {
        if (!nonemptyText(params.get("name"))
                || (params.has("arguments") && !params.path("arguments").isObject())) {
            return completed(error(200, id, -32602, "tools/call requires name and object arguments"));
        }
        if (params.has("task")) return completed(error(200, id, -32602, "Task execution is not supported"));
        String name = params.path("name").asText();
        // The catalog comes from the same runtime as the REST and browser interfaces.
        boolean known = false;
        for (JsonNode tool : runtime.mcpTools(true)) {
            if (name.equals(tool.path("name").asText())) { known = true; break; }
        }
        if (!known) return completed(error(200, id, -32602, "Unknown tool"));
        ObjectNode arguments = params.has("arguments")
                ? ((ObjectNode) params.get("arguments")).deepCopy() : Json.newObject();
        if (arguments.has("requestId") && !nonemptyText(arguments.get("requestId"))) {
            return completed(error(200, id, -32602, "requestId must be a non-empty string"));
        }
        String argumentKey = arguments.path("requestId").asText(null);
        String headerKey = request.getHeaders().get("Idempotency-Key").orElse(null);
        if (headerKey != null && (headerKey.isBlank() || headerKey.length() > 128
                || (argumentKey != null && !headerKey.equals(argumentKey)))) {
            return completed(error(200, id, -32602, "Invalid or conflicting Idempotency-Key"));
        }
        String key = headerKey != null ? headerKey : argumentKey;
        if (key != null && key.length() > 128) {
            return completed(error(200, id, -32602, "requestId exceeds 128 characters"));
        }
        try {
            // Runtime checks operator permission, configured values and idempotency.
            // Never retry here: an OPC UA write may have succeeded before a connection failed.
            return runtime.callTool(name, arguments, access.isOperator(request), key)
                    .handle((value, failure) -> failure == null
                            ? success(id, toolResult(value, false, version))
                            : toolFailure(id, failure, version));
        } catch (Exception failure) {
            return completed(toolFailure(id, failure, version));
        }
    }

    private static Result toolFailure(JsonNode id, Throwable failure, String version) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        ObjectNode detail = Json.newObject();
        if (cause instanceof ScadaException scada) {
            detail.put("code", scada.getCode());
            detail.put("message", scada.getMessage());
        } else {
            detail.put("code", "INTERNAL_ERROR");
            detail.put("message", "Tool execution failed");
        }
        return success(id, toolResult(detail, true, version));
    }

    private static ObjectNode toolResult(JsonNode value, boolean failed, String version) {
        ObjectNode result = Json.newObject();
        result.putArray("content").addObject().put("type", "text").put("text", Json.stringify(value));
        // Structured results were added in 2025-06-18; text remains compatible with older clients.
        if (!"2025-03-26".equals(version) && value != null && value.isObject()) result.set("structuredContent", value);
        result.put("isError", failed);
        return result;
    }

    private static Result acceptResponse(JsonNode message, JsonNode id) {
        boolean validResult = message.has("result") && message.get("result").isObject() && !message.has("error");
        JsonNode rpcError = message.path("error");
        boolean validError = !message.has("result") && rpcError.isObject()
                && rpcError.path("code").isIntegralNumber() && rpcError.path("message").isTextual();
        // No requests originate from this stateless server, so valid client responses need no work.
        return id != null && !message.has("params") && (validResult || validError)
                ? noBodyAccepted() : error(400, id, -32600, "Invalid JSON-RPC message");
    }

    private static boolean accepts(Http.Request request, String expected) {
        for (String header : request.getHeaders().getAll("Accept")) {
            for (String entry : header.split(",")) {
                String[] parts = entry.trim().split(";");
                if (!expected.equalsIgnoreCase(parts[0].trim())) continue;
                double quality = 1;
                for (int i = 1; i < parts.length; i++) {
                    String[] parameter = parts[i].trim().split("=", 2);
                    if (parameter[0].equalsIgnoreCase("q")) {
                        try { quality = parameter.length == 2 ? Double.parseDouble(parameter[1]) : 0; }
                        catch (NumberFormatException invalid) { quality = 0; }
                    }
                }
                if (quality > 0 && quality <= 1) return true;
            }
        }
        return false;
    }

    private static boolean nonemptyText(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    private static Result success(JsonNode id, JsonNode result) {
        ObjectNode response = Json.newObject().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return json(200, response);
    }

    private static Result error(int status, JsonNode id, int code, String message) {
        ObjectNode response = Json.newObject().put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return json(status, response);
    }

    private static Result json(int status, JsonNode body) {
        return status(status, body).as("application/json")
                .withHeaders("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff");
    }

    private static Result noBodyAccepted() {
        return status(202).withHeader("Cache-Control", "no-store");
    }

    private static Result methodNotAllowedResponse() {
        return status(405).withHeaders("Allow", "POST", "Cache-Control", "no-store");
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}
