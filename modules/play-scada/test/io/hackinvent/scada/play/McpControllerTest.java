package io.hackinvent.scada.play;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hackinvent.scada.core.ScadaException;
import org.junit.Before;
import org.junit.Test;
import play.http.HttpEntity;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class McpControllerTest {
    private ScadaRuntime runtime;
    private ScadaAccess access;
    private McpController controller;

    @Before
    public void setup() {
        runtime = mock(ScadaRuntime.class);
        access = mock(ScadaAccess.class);
        controller = new McpController(runtime, access);
        when(access.canRead(any())).thenReturn(true);
        when(access.isAllowedOrigin(any())).thenReturn(true);
        JsonNode tools = Json.parse("""
                [{"name":"scada_list_tags","description":"Configured tags","inputSchema":{"type":"object"}},
                 {"name":"scada_read_tags","description":"Current values","inputSchema":{"type":"object"}},
                 {"name":"scada_list_commands","description":"Commands","inputSchema":{"type":"object"}},
                 {"name":"scada_execute_command","description":"Run command","inputSchema":{"type":"object"}}]
                """);
        when(runtime.mcpTools(true)).thenReturn(tools);
        when(runtime.mcpTools(false)).thenReturn(Json.parse("[{\"name\":\"scada_list_tags\"}]"));
    }

    @Test
    public void initializeNegotiatesVersionsAndPreservesStringIds() throws Exception {
        for (String protocol : new String[]{"2025-11-25", "2025-06-18", "2025-03-26"}) {
            Result result = call(request(initialize(protocol)));
            JsonNode body = body(result);
            assertEquals(200, result.status());
            assertEquals("application/json", result.contentType().orElseThrow());
            assertEquals("init-01", body.path("id").asText());
            assertEquals(protocol, body.at("/result/protocolVersion").asText());
            assertFalse(body.at("/result/capabilities/tools/listChanged").asBoolean());
            assertFalse(result.header("Mcp-Session-Id").isPresent());
        }
        assertEquals("2025-11-25", body(call(request(initialize("2099-01-01"))))
                .at("/result/protocolVersion").asText());
        verifyNoInteractions(runtime);
    }

    @Test
    public void initializationRequiresClientMetadata() throws Exception {
        Result result = call(request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        assertEquals(-32602, body(result).at("/error/code").asInt());
        verifyNoInteractions(runtime);
    }

    @Test
    public void acceptsInitializedNotificationWithNoBody() throws Exception {
        Result result = call(request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertEquals(202, result.status());
        assertEquals("", contentAsString(result));
        verifyNoInteractions(runtime);
    }

    @Test
    public void neverDispatchesToolCallWithoutRequestId() throws Exception {
        ObjectNode notification = toolCall("scada_execute_command", Json.parse("{\"commandId\":\"pump.start\"}"));
        notification.remove("id");
        Result result = call(request(notification.toString()));
        assertEquals(400, result.status());
        assertEquals(-32600, body(result).at("/error/code").asInt());
        verifyNoInteractions(runtime);
    }

    @Test
    public void catalogUsesCurrentUserPermission() throws Exception {
        Result result = call(request("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));
        assertEquals(7, body(result).path("id").asInt());
        assertEquals(1, body(result).at("/result/tools").size());
        verify(runtime).mcpTools(false);
        verify(runtime, never()).mcpTools(true);
    }

    @Test
    public void unauthorizedAndDisallowedOriginsCannotReachRuntime() throws Exception {
        when(access.canRead(any())).thenReturn(false);
        Result unauthorized = call(request(initialize("2025-11-25")));
        assertEquals(401, unauthorized.status());
        assertEquals("Bearer realm=\"scada-mcp\"", unauthorized.header("WWW-Authenticate").orElseThrow());
        when(access.isAllowedOrigin(any())).thenReturn(false);
        assertEquals(403, call(request(initialize("2025-11-25"))).status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void rejectsUnsupportedProtocolHeader() throws Exception {
        Result result = call(request(initialize("2025-11-25")).header("MCP-Protocol-Version", "2099-01-01"));
        assertEquals(400, result.status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void validatesContentTypeAndBothAcceptedMediaTypes() throws Exception {
        assertEquals(415, call(request(initialize("2025-11-25")).header("Content-Type", "text/plain")).status());
        assertEquals(406, call(request(initialize("2025-11-25")).header("Accept", "application/json")).status());
        assertEquals(406, call(request(initialize("2025-11-25"))
                .header("Accept", "application/json, text/event-stream;q=0")).status());
        assertEquals(200, call(request(initialize("2025-11-25"))
                .header("Accept", "application/json; q=0.8, text/event-stream")).status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void rejectsMalformedDuplicateAndConcatenatedJson() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        for (String text : new String[]{"{", ping + ping,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"method\":\"tools/call\"}"}) {
            Result result = call(request(text));
            assertEquals(400, result.status());
            assertEquals(-32700, body(result).at("/error/code").asInt());
        }
        verifyNoInteractions(runtime);
    }

    @Test
    public void rejectsBatchNullAndInvalidIds() throws Exception {
        for (String text : new String[]{"[]", "null", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}"}) {
            Result result = call(request(text));
            assertEquals(400, result.status());
            assertEquals(-32600, body(result).at("/error/code").asInt());
        }
        verifyNoInteractions(runtime);
    }

    @Test
    public void legacyBatchReturnsOnlyRequestResponsesAndCurrentProtocolRejectsBatches() throws Exception {
        String batch = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]";
        Result result = call(request(batch).header("MCP-Protocol-Version", "2025-03-26"));
        assertEquals(200, result.status());
        assertEquals(1, body(result).size());
        assertEquals(1, body(result).get(0).path("id").asInt());
        assertTrue(body(result).get(0).path("result").isObject());
        assertEquals(400, call(request(batch).header("MCP-Protocol-Version", "2025-11-25")).status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void legacyNotificationBatchIsEmptyAndDuplicateIdsNeverDispatch() throws Exception {
        Result notifications = call(request("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));
        assertEquals(202, notifications.status());
        assertEquals("", contentAsString(notifications));
        String tool = toolCall("scada_execute_command", Json.parse("{\"commandId\":\"pump.start\",\"requestId\":\"one\"}")).toString();
        Result duplicate = call(request("[" + tool + "," + tool + "]"));
        assertEquals(400, duplicate.status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void rejectsOversizedRequestBeforeDispatch() throws Exception {
        Result result = call(request(" ".repeat(McpController.MAX_BODY_BYTES) + "{}"));
        assertEquals(413, result.status());
        verifyNoInteractions(runtime);
    }

    @Test
    public void returnsMethodNotFoundWithOriginalId() throws Exception {
        Result result = call(request("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"not/a/method\"}"));
        assertEquals(-32601, body(result).at("/error/code").asInt());
        assertEquals("x", body(result).path("id").asText());
    }

    @Test
    public void validatesToolNameAndArgumentEnvelopeWithoutExecuting() throws Exception {
        Result unknown = call(request(toolCall("missing_tool", Json.newObject()).toString()));
        assertEquals(-32602, body(unknown).at("/error/code").asInt());
        Result invalid = call(request(toolCall("scada_read_tags", Json.newArray()).toString()));
        assertEquals(-32602, body(invalid).at("/error/code").asInt());
        verify(runtime, never()).callTool(anyString(), any(), anyBoolean(), any());
    }

    @Test
    public void returnsReadableAndStructuredToolOutputForCurrentProtocol() throws Exception {
        JsonNode value = Json.parse("{\"tags\":[{\"id\":\"tank.temperature\",\"value\":21.5,\"quality\":\"GOOD\"}]} ");
        when(runtime.callTool(eq("scada_read_tags"), any(), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(value));
        Result result = call(request(toolCall("scada_read_tags", Json.newObject()).toString())
                .header("MCP-Protocol-Version", "2025-11-25"));
        JsonNode body = body(result);
        assertEquals(value, body.at("/result/structuredContent"));
        assertEquals(value, Json.parse(body.at("/result/content/0/text").asText()));
        assertFalse(body.at("/result/isError").asBoolean());
        verify(runtime, times(1)).callTool(eq("scada_read_tags"), any(), eq(false), isNull());
    }

    @Test
    public void absentVersionHeaderUsesCompatibleTextOutput() throws Exception {
        when(runtime.callTool(eq("scada_list_tags"), any(), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(Json.newObject()));
        Result result = call(request(toolCall("scada_list_tags", Json.newObject()).toString()));
        assertTrue(body(result).at("/result/structuredContent").isMissingNode());
        assertEquals("{}", body(result).at("/result/content/0/text").asText());
    }

    @Test
    public void commandRefusalIsAToolErrorAndNeverRetried() throws Exception {
        ScadaException refusal = new ScadaException("FORBIDDEN", 403, "Operator permission required");
        when(runtime.callTool(eq("scada_execute_command"), any(), eq(false), eq("command-01")))
                .thenReturn(CompletableFuture.failedFuture(refusal));
        Result result = call(request(toolCall("scada_execute_command",
                Json.parse("{\"commandId\":\"pump.start\",\"requestId\":\"command-01\"}")).toString()));
        assertEquals(200, result.status());
        assertTrue(body(result).at("/result/isError").asBoolean());
        assertEquals("FORBIDDEN", Json.parse(body(result).at("/result/content/0/text").asText()).path("code").asText());
        verify(runtime, times(1)).callTool(eq("scada_execute_command"), any(), eq(false), eq("command-01"));
    }

    @Test
    public void headerIdempotencyKeyAndOperatorReachSharedRuntimeExactlyOnce() throws Exception {
        when(access.isOperator(any())).thenReturn(true);
        JsonNode arguments = Json.parse("{\"commandId\":\"pump.start\"}");
        when(runtime.callTool("scada_execute_command", arguments, true, "command-02"))
                .thenReturn(CompletableFuture.completedFuture(Json.parse("{\"status\":\"ACCEPTED\"}")));
        Result result = call(request(toolCall("scada_execute_command", arguments).toString())
                .header("Idempotency-Key", "command-02"));
        assertFalse(body(result).at("/result/isError").asBoolean());
        verify(runtime, times(1)).callTool("scada_execute_command", arguments, true, "command-02");
    }

    @Test
    public void conflictingIdempotencyKeysNeverExecute() throws Exception {
        Result result = call(request(toolCall("scada_execute_command",
                Json.parse("{\"commandId\":\"pump.start\",\"requestId\":\"one\"}")).toString())
                .header("Idempotency-Key", "two"));
        assertEquals(-32602, body(result).at("/error/code").asInt());
        verify(runtime, never()).callTool(anyString(), any(), anyBoolean(), any());
    }

    @Test
    public void internalToolFailuresDoNotExposeExceptionDetails() throws Exception {
        when(runtime.callTool(eq("scada_read_tags"), any(), eq(false), isNull()))
                .thenThrow(new IllegalStateException("opc.tcp://private-host:4840 credentials"));
        Result result = call(request(toolCall("scada_read_tags", Json.newObject()).toString()));
        assertTrue(body(result).at("/result/isError").asBoolean());
        assertFalse(contentAsString(result).contains("private-host"));
        verify(runtime, times(1)).callTool(eq("scada_read_tags"), any(), eq(false), isNull());
    }

    @Test
    public void getAndDeleteAdvertiseNoStreamOrSessionAfterAuthorization() {
        for (String method : new String[]{"GET", "DELETE"}) {
            Result result = controller.unsupported(new Http.RequestBuilder().method(method).uri("/mcp").build());
            assertEquals(405, result.status());
            assertEquals("POST", result.header("Allow").orElseThrow());
        }
        when(access.isAllowedOrigin(any())).thenReturn(false);
        assertEquals(403, controller.unsupported(new Http.RequestBuilder().method("GET").uri("/mcp").build()).status());
    }

    private static String initialize(String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"init-01\",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}}";
    }

    private static ObjectNode toolCall(String name, JsonNode arguments) {
        ObjectNode body = Json.newObject().put("jsonrpc", "2.0").put("id", "tool-01").put("method", "tools/call");
        body.putObject("params").put("name", name).set("arguments", arguments);
        return body;
    }

    private static Http.RequestBuilder request(String body) {
        return new Http.RequestBuilder().method("POST").uri("/mcp").bodyText(body)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
    }

    private Result call(Http.RequestBuilder request) throws Exception {
        return controller.handle(request.build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static String contentAsString(Result result) {
        if (result.body().isKnownEmpty()) return "";
        return ((HttpEntity.Strict) result.body()).data().utf8String();
    }

    private static JsonNode body(Result result) { return Json.parse(contentAsString(result)); }
}
