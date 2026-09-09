package io.hackinvent.scada.play;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.hackinvent.scada.core.ScadaException;
import org.junit.Test;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScadaAssistantTest {
    private static final class TestLifecycle {
        final ApplicationLifecycle delegate = mock(ApplicationLifecycle.class);
        final java.util.List<java.util.concurrent.Callable<?>> hooks = new java.util.ArrayList<>();
        TestLifecycle() {
            doAnswer(invocation -> { hooks.add(invocation.getArgument(0)); return null; })
                .when(delegate).addStopHook(any());
        }
        CompletionStage<Void> stop() throws Exception {
            for (var hook : hooks) ((CompletionStage<?>) hook.call()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        }
    }
    private Config config(int port, String key) {
        return ConfigFactory.parseString("scada.openai { api-key=\"" + key + "\", model=\"test-model\", "
            + "endpoint=\"http://127.0.0.1:" + port + "/responses\", timeout=5s, max-tool-rounds=3 }");
    }
    private ScadaRuntime runtime() {
        ScadaRuntime runtime = mock(ScadaRuntime.class);
        when(runtime.mcpTools(false)).thenReturn(Json.parse("[{\"name\":\"scada_read_tags\",\"description\":\"Read measurements\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}}]"));
        return runtime;
    }
    @Test public void executesReadToolLocallyAndReturnsProviderAnswer() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requests.add(Json.parse(exchange.getRequestBody().readAllBytes()));
            String response = requests.size() == 1
                ? "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"scada_read_tags\",\"call_id\":\"call1\",\"arguments\":\"{}\"}]}"
                : "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"La cuve est à 24 °C.\"}]}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); TestLifecycle lifecycle = new TestLifecycle();
        try {
            ScadaRuntime runtime = runtime();
            when(runtime.callTool(eq("scada_read_tags"), any(), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(Json.parse("{\"temperature\":24}")));
            ScadaAssistant assistant = new ScadaAssistant(config(server.getAddress().getPort(), "test-key"), runtime, lifecycle.delegate);
            JsonNode result = assistant.ask("Quelle température ?").toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("La cuve est à 24 °C.", result.get("answer").asText());
            assertTrue(result.get("readOnly").asBoolean()); assertEquals(2, requests.size());
            assertFalse(requests.get(0).get("store").asBoolean());
            assertEquals("function_call_output", requests.get(1).path("input").get(2).get("type").asText());
            assertTrue(requests.get(1).path("input").get(2).get("output").asText().contains("24"));
            verify(runtime).callTool(eq("scada_read_tags"), any(), eq(false), isNull());
        } finally { lifecycle.stop().toCompletableFuture().get(5, TimeUnit.SECONDS); server.stop(0); }
    }
    @Test public void providerCannotEscalateToCommandExecution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"scada_execute_command\",\"call_id\":\"bad\",\"arguments\":\"{}\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); TestLifecycle lifecycle = new TestLifecycle();
        try {
            ScadaRuntime runtime = runtime();
            ScadaAssistant assistant = new ScadaAssistant(config(server.getAddress().getPort(), "test-key"), runtime, lifecycle.delegate);
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> assistant.ask("Démarre la pompe").toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ScadaException);
            assertEquals("assistant_tool_forbidden", ((ScadaException) failure.getCause()).getCode());
            verify(runtime, never()).callTool(anyString(), any(), anyBoolean(), any());
        } finally { lifecycle.stop().toCompletableFuture().get(5, TimeUnit.SECONDS); server.stop(0); }
    }
    @Test public void incompleteProviderAnswerIsNeverPresentedAsSuccess() throws Exception {
        HttpServer server = provider(request -> Json.parse("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
            + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial statement\"}]}]}"));
        TestLifecycle lifecycle = new TestLifecycle();
        try {
            ScadaRuntime runtime = runtime();
            ScadaAssistant assistant = new ScadaAssistant(config(server.getAddress().getPort(), "test-key"), runtime, lifecycle.delegate);
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> assistant.ask("État ?").toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals("assistant_incomplete", ((ScadaException) failure.getCause()).getCode());
            verify(runtime, never()).callTool(anyString(), any(), anyBoolean(), any());
        } finally { lifecycle.stop(); server.stop(0); }
    }

    @Test public void stalledLocalToolsTimeOutReleaseCapacityAndCannotContinueLater() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean answerDirectly = new AtomicBoolean();
        HttpServer server = provider(request -> {
            requests.incrementAndGet();
            return answerDirectly.get() ? answer() : twoReadCalls();
        });
        TestLifecycle lifecycle = new TestLifecycle();
        List<CompletableFuture<JsonNode>> pendingTools = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(4);
        try {
            ScadaRuntime runtime = runtime();
            when(runtime.callTool(eq("scada_read_tags"), any(), eq(false), isNull())).thenAnswer(invocation -> {
                // Some async libraries cannot cancel the underlying operation. Its late completion
                // must still be prevented from starting another tool or provider round.
                CompletableFuture<JsonNode> pending = new CompletableFuture<>() {
                    @Override public boolean cancel(boolean interrupt) { return false; }
                };
                pendingTools.add(pending);
                started.countDown();
                return pending;
            });
            Config shortDeadline = ConfigFactory.parseString("scada.openai.timeout=3s")
                .withFallback(config(server.getAddress().getPort(), "test-key"));
            ScadaAssistant assistant = new ScadaAssistant(shortDeadline, runtime, lifecycle.delegate);
            List<CompletionStage<JsonNode>> asks = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) asks.add(assistant.ask("État ?"));
            assertEquals("assistant_busy", assertThrows(ScadaException.class, () -> assistant.ask("Cinquième demande")).getCode());
            assertTrue("All four local reads must begin", started.await(4, TimeUnit.SECONDS));
            for (CompletionStage<JsonNode> ask : asks) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> ask.toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("assistant_timeout", ((ScadaException) failure.getCause()).getCode());
            }
            answerDirectly.set(true);
            assertEquals("Réponse", assistant.ask("Nouvelle demande").toCompletableFuture().get(5, TimeUnit.SECONDS).path("answer").asText());
            for (CompletableFuture<JsonNode> pending : pendingTools) pending.complete(Json.newObject());
            assertEquals("Late completion must not cause another provider request", 5, requests.get());
            verify(runtime, times(4)).callTool(eq("scada_read_tags"), any(), eq(false), isNull());
        } finally { lifecycle.stop(); server.stop(0); }
    }

    @Test public void lifecycleStopCancelsOutstandingAssistantCalls() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = provider(request -> { requests.incrementAndGet(); return twoReadCalls(); });
        TestLifecycle lifecycle = new TestLifecycle();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<JsonNode> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { return false; }
        };
        try {
            ScadaRuntime runtime = runtime();
            when(runtime.callTool(eq("scada_read_tags"), any(), eq(false), isNull())).thenAnswer(invocation -> {
                started.countDown(); return pending;
            });
            ScadaAssistant assistant = new ScadaAssistant(config(server.getAddress().getPort(), "test-key"), runtime, lifecycle.delegate);
            CompletionStage<JsonNode> ask = assistant.ask("État ?");
            assertTrue(started.await(4, TimeUnit.SECONDS));
            lifecycle.stop();
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> ask.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("assistant_stopped", ((ScadaException) failure.getCause()).getCode());
            pending.complete(Json.newObject());
            assertEquals(1, requests.get());
            verify(runtime, times(1)).callTool(eq("scada_read_tags"), any(), eq(false), isNull());
            assertEquals("assistant_stopped", assertThrows(ScadaException.class, () -> assistant.ask("Encore ?")).getCode());
        } finally { lifecycle.stop(); server.stop(0); }
    }

    private static JsonNode answer() {
        return Json.parse("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Réponse\"}]}]}");
    }

    private static JsonNode twoReadCalls() {
        return Json.parse("{\"status\":\"completed\",\"output\":["
            + "{\"type\":\"function_call\",\"name\":\"scada_read_tags\",\"call_id\":\"one\",\"arguments\":\"{}\"},"
            + "{\"type\":\"function_call\",\"name\":\"scada_read_tags\",\"call_id\":\"two\",\"arguments\":\"{}\"}]}");
    }

    private static HttpServer provider(java.util.function.Function<JsonNode, JsonNode> response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            JsonNode request = Json.parse(exchange.getRequestBody().readAllBytes());
            byte[] body = response.apply(request).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test public void absentKeyDisablesAssistantWithoutCallingProvider() throws Exception {
        TestLifecycle lifecycle = new TestLifecycle();
        try {
            ScadaAssistant assistant = new ScadaAssistant(config(1, ""), runtime(), lifecycle.delegate);
            ScadaException failure = assertThrows(ScadaException.class, () -> assistant.ask("État ?"));
            assertEquals("assistant_disabled", failure.getCode());
        } finally { lifecycle.stop().toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }
}
