package io.hackinvent.scada.play;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.hackinvent.scada.opcua.DemoOpcUaServer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import play.inject.ApplicationLifecycle;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScadaRuntimeTest {
    private static final class TestLifecycle implements AutoCloseable {
        final ApplicationLifecycle delegate = mock(ApplicationLifecycle.class);
        final List<Callable<?>> hooks = new ArrayList<>();
        TestLifecycle() {
            doAnswer(call -> { hooks.add(call.getArgument(0)); return null; }).when(delegate).addStopHook(any());
        }
        @Override public void close() throws Exception {
            for (Callable<?> hook : hooks) ((CompletionStage<?>) hook.call()).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private Config config(int port) {
        return ConfigFactory.parseMap(Map.of(
            "scada.simulator.enabled", true, "scada.simulator.port", port,
            "scada.tags", List.of(Map.of("id", "pump.running", "nodeId", DemoOpcUaServer.PUMP_NODE_ID,
                "label", "Pump", "dataType", "Boolean", "writable", true)),
            "scada.commands", List.of(Map.of("id", "pump.start", "label", "Start", "description", "Start the pump",
                "tagId", "pump.running", "requiresValue", false, "fixedValue", true))))
            .withFallback(ConfigFactory.parseResources("scada-reference.conf")).resolve();
    }

    @Test public void restCommandIdMustMatchThePathBeforeAnyWrite() throws Exception {
        int port = freePort();
        try (TestLifecycle lifecycle = new TestLifecycle()) {
            ScadaRuntime runtime = new ScadaRuntime(config(port), lifecycle.delegate);
            var args = play.libs.Json.newObject().put("commandId", "pump.stop").put("requestId", "conflicting-command");
            io.hackinvent.scada.core.ScadaException failure = assertThrows(io.hackinvent.scada.core.ScadaException.class,
                () -> runtime.executeCommand("pump.start", args, true, null));
            assertEquals("invalid_arguments", failure.getCode());
            assertFalse(lifecycle.hooks.isEmpty());
        }
        assertPortFree(port);
    }

    @Test public void invalidRefreshConfigurationNeverStartsTheSimulator() throws Exception {
        int port = freePort();
        try (TestLifecycle lifecycle = new TestLifecycle()) {
            Config invalid = ConfigFactory.parseString("scada.refresh-interval = nonsense").withFallback(config(port));
            assertThrows(com.typesafe.config.ConfigException.class, () -> new ScadaRuntime(invalid, lifecycle.delegate));
            assertTrue(lifecycle.hooks.isEmpty());
            assertPortFree(port);
        }
    }

    @Test public void constructorFailureDuringHookRegistrationClosesAllocatedResources() throws Exception {
        int port = freePort();
        ApplicationLifecycle broken = mock(ApplicationLifecycle.class);
        doThrow(new IllegalStateException("Lifecycle registration failed")).when(broken).addStopHook(any());
        assertThrows(IllegalStateException.class, () -> new ScadaRuntime(config(port), broken));
        assertPortFree(port);
    }

    @Test public void externalRuntimeDoesNotDemandBuiltInAuthenticationTokens() throws Exception {
        int port = freePort();
        try (DemoOpcUaServer simulator = new DemoOpcUaServer(port); TestLifecycle lifecycle = new TestLifecycle()) {
            simulator.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            Config external = ConfigFactory.parseMap(Map.of(
                "scada.simulator.enabled", false, "scada.opcua.endpoint", simulator.endpointUrl(),
                "scada.opcua.security-policy", "None", "scada.opcua.security-mode", "None",
                "scada.security.reader-token", "", "scada.security.operator-token", ""))
                .withFallback(config(port));
            ScadaRuntime runtime = new ScadaRuntime(external, lifecycle.delegate);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!runtime.catalog(false).path("connection").path("connected").asBoolean() && System.nanoTime() < deadline)
                Thread.sleep(25);
            assertTrue(runtime.catalog(false).path("connection").path("connected").asBoolean());
            assertFalse(runtime.simulated());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static void assertPortFree(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))) { assertEquals(port, socket.getLocalPort()); }
    }
}
