import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import java.net.ServerSocket;
import static org.junit.Assert.*;

/** Exercise real Guice wiring, routes, Twirl and tool generation rather than a mocked runtime. */
public class ScadaApplicationTest {
    @Test public void pageAndMcpShareTheActualConfiguredCatalog() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Application app = new GuiceApplicationBuilder()
            .configure(java.util.Map.of("scada.simulator.enabled", true, "scada.simulator.port", port,
                "scada.security.local-demo-access", true, "scada.openai.api-key", "", "scada.openai.model", "",
                "scada.security.reader-token", "reader-test", "scada.security.operator-token", "operator-test"))
            .build();
        Helpers.start(app);
        try {
            Result page = Helpers.route(app, request("GET", "/"));
            assertEquals(200, page.status());
            String html = Helpers.contentAsString(page);
            assertTrue(html.contains("data-play-webmcp"));
            assertTrue(html.contains("scada_read_tags"));
            assertTrue(html.contains("lib/play-scada/play-scada.js"));
            Result catalog = Helpers.route(app, request("GET", "/api/scada/catalog"));
            assertEquals(200, catalog.status());
            assertEquals(3, Json.parse(Helpers.contentAsString(catalog)).get("tags").size());
            Result tools = Helpers.route(app, request("POST", "/mcp")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .bodyText("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").header("Content-Type", "application/json"));
            assertEquals(200, tools.status());
            assertEquals(4, Json.parse(Helpers.contentAsString(tools)).path("result").path("tools").size());
            Result csrf = Helpers.route(app, request("POST", "/api/scada/commands/pump.start")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "csrf-denied")
                .bodyJson(Json.newObject()));
            assertEquals("Browser commands require a CSRF token", 403, csrf.status());
            Result anonymousRemote = Helpers.route(app, request("GET", "/api/scada/catalog").remoteAddress("192.0.2.1"));
            assertEquals("Remote callers do not inherit demo permission", 401, anonymousRemote.status());
            Result readerCatalog = Helpers.route(app, request("GET", "/api/scada/catalog").remoteAddress("192.0.2.1")
                .header("Authorization", "Bearer reader-test"));
            assertEquals(200, readerCatalog.status());
            assertFalse(Json.parse(Helpers.contentAsString(readerCatalog)).path("access").path("canCommand").asBoolean());
            Result deniedMcp = Helpers.route(app, request("POST", "/mcp").remoteAddress("192.0.2.1")
                .header("Authorization", "Bearer reader-test")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .bodyText("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"scada_execute_command\",\"arguments\":{\"commandId\":\"pump.start\",\"requestId\":\"reader-denied\"}}}").header("Content-Type", "application/json"));
            assertEquals(200, deniedMcp.status());
            assertTrue(Json.parse(Helpers.contentAsString(deniedMcp)).path("result").path("isError").asBoolean());
            assertEquals("forbidden", Json.parse(Helpers.contentAsString(deniedMcp)).path("result").path("structuredContent").path("code").asText());
        } finally { Helpers.stop(app); }
    }
    private Http.RequestBuilder request(String method, String uri) {
        return Helpers.fakeRequest(method, uri).remoteAddress("127.0.0.1").header("Host", "localhost");
    }
}
