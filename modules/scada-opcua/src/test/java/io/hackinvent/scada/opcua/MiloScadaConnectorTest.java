package io.hackinvent.scada.opcua;

import io.hackinvent.scada.core.TagDefinition;
import io.hackinvent.scada.core.TagValue;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** End-to-end protocol checks: real opc.tcp sockets, no mocks. */
public class MiloScadaConnectorTest {
  private static final TagDefinition TEMPERATURE = new TagDefinition("tank.temperature",
      DemoOpcUaServer.TEMPERATURE_NODE_ID, "Temperature", "°C", "Double", false);
  private static final TagDefinition PUMP = new TagDefinition("pump.running",
      DemoOpcUaServer.PUMP_NODE_ID, "Pump", "", "Boolean", true);
  private static final TagDefinition SETPOINT = new TagDefinition("tank.setpoint",
      DemoOpcUaServer.SETPOINT_NODE_ID, "Setpoint", "°C", "Double", true);

  @Test public void discoversReadsSubscribesAndWritesOverOpcTcp() throws Exception {
    try (DemoOpcUaServer server = new DemoOpcUaServer(freePort())) {
      await(server.start());
      try (MiloScadaConnector connector = new MiloScadaConnector(OpcUaConnectorConfig.localDemo(server.endpointUrl()))) {
        await(connector.connect());
        assertTrue(connector.isConnected());
        assertTrue(await(connector.browse(null)).stream().anyMatch(n -> "Demo".equals(n.browseName())));
        assertEquals(3, await(connector.browse(DemoOpcUaServer.ROOT_NODE_ID)).size());
        Map<String, TagValue> initial = await(connector.read(List.of(TEMPERATURE, PUMP, SETPOINT)));
        assertEquals(Boolean.FALSE, initial.get(PUMP.id()).value());
        assertEquals("GOOD", initial.get(TEMPERATURE.id()).quality());
        assertNotNull(initial.get(TEMPERATURE.id()).sourceTimestamp());
        assertNotNull(initial.get(TEMPERATURE.id()).serverTimestamp());
        LinkedBlockingQueue<TagValue> changes = new LinkedBlockingQueue<>();
        await(connector.subscribe(List.of(PUMP), (id, value) -> changes.add(value)));
        assertNotNull("Initial monitored value", changes.poll(5, TimeUnit.SECONDS));
        await(connector.write(PUMP, true));
        TagValue changed = changes.poll(5, TimeUnit.SECONDS);
        assertNotNull("Subscribed command result", changed);
        assertEquals(Boolean.TRUE, changed.value());
        await(connector.write(SETPOINT, 19.5));
        assertEquals(19.5, (Double) await(connector.read(List.of(SETPOINT))).get(SETPOINT.id()).value(), 0.001);
        assertEquals(Boolean.TRUE, await(connector.read(List.of(PUMP))).get(PUMP.id()).value());
        TagDefinition serverReadOnly = new TagDefinition(TEMPERATURE.id(), TEMPERATURE.nodeId(),
            TEMPERATURE.label(), TEMPERATURE.unit(), TEMPERATURE.dataType(), true);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(connector.write(serverReadOnly, 999.0)));
        await(connector.disconnect());
        assertFalse(connector.isConnected());
        await(connector.shutdown());
        await(connector.shutdown());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(connector.connect()));
      }
    }
  }

  @Test public void retriesDiscoveryWhenSimulatorStartsLater() throws Exception {
    int port = freePort();
    String endpoint = "opc.tcp://127.0.0.1:" + port + "/scada";
    try (MiloScadaConnector connector = new MiloScadaConnector(OpcUaConnectorConfig.localDemo(endpoint))) {
      CompletionStage<Void> connecting = connector.connect();
      try (DemoOpcUaServer server = new DemoOpcUaServer(port)) {
        await(server.start());
        await(connecting);
        assertEquals("GOOD", await(connector.read(List.of(TEMPERATURE))).get(TEMPERATURE.id()).quality());
      }
    }
  }

  @Test public void restoresSubscriptionsAfterServerRestart() throws Exception {
    int port = freePort();
    DemoOpcUaServer first = new DemoOpcUaServer(port);
    await(first.start());
    boolean firstRunning = true;
    try (MiloScadaConnector connector = new MiloScadaConnector(OpcUaConnectorConfig.localDemo(first.endpointUrl()))) {
      await(connector.connect());
      LinkedBlockingQueue<TagValue> changes = new LinkedBlockingQueue<>();
      await(connector.subscribe(List.of(PUMP), (id, value) -> changes.add(value)));
      assertNotNull(changes.poll(5, TimeUnit.SECONDS));
      await(connector.write(PUMP, true));
      assertEquals(Boolean.TRUE, changes.poll(5, TimeUnit.SECONDS).value());
      await(first.stop());
      firstRunning = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (connector.isConnected() && System.nanoTime() < deadline) Thread.sleep(50);
      assertFalse("Connection must become unavailable", connector.isConnected());
      changes.clear();
      try (DemoOpcUaServer restarted = new DemoOpcUaServer(port)) {
        await(restarted.start());
        TagValue initialAfterRestart = changes.poll(20, TimeUnit.SECONDS);
        assertNotNull("Subscription must recover on the new server", initialAfterRestart);
        assertEquals(Boolean.FALSE, initialAfterRestart.value());
        await(connector.write(PUMP, true));
        TagValue changed = changes.poll(5, TimeUnit.SECONDS);
        assertNotNull(changed);
        assertEquals(Boolean.TRUE, changed.value());
      }
    } finally { if (firstRunning) first.close(); }
  }

  @Test public void rejectsUnknownCertificatesAndWrongHostnames() throws Exception {
    java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("scada-opcua-pki-test");
    var keyPair = org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
    var certificate = new org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder(keyPair)
        .setCommonName("Test server").setApplicationUri("urn:hackinvent:test:server")
        .addDnsName("localhost").addIpAddress("127.0.0.1").build();
    try (var trust = org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager.createAndInitialize(directory)) {
      var validator = MiloScadaConnector.certificateValidator(trust, directory);
      assertThrows(org.eclipse.milo.opcua.stack.core.UaException.class, () ->
          validator.validateCertificateChain(List.of(certificate), "urn:hackinvent:test:server", new String[]{"localhost"}));
      trust.addTrustedCertificate(certificate);
      validator.validateCertificateChain(List.of(certificate), "urn:hackinvent:test:server", new String[]{"localhost"});
      assertThrows(org.eclipse.milo.opcua.stack.core.UaException.class, () ->
          validator.validateCertificateChain(List.of(certificate), "urn:hackinvent:test:server", new String[]{"wrong-host"}));
      assertThrows(org.eclipse.milo.opcua.stack.core.UaException.class, () ->
          validator.validateCertificateChain(List.of(certificate), "urn:hackinvent:wrong", new String[]{"localhost"}));
    } finally {
      try (var files = java.nio.file.Files.walk(directory)) {
        for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
      }
    }
  }

  @Test public void cleansUpAfterPortConflictAndSupportsRepeatedStop() throws Exception {
    int port = freePort();
    try (ServerSocket occupied = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))) {
      DemoOpcUaServer server = new DemoOpcUaServer(port);
      assertThrows(java.util.concurrent.ExecutionException.class, () -> await(server.start()));
      await(server.stop());
      await(server.stop());
    }
    try (DemoOpcUaServer server = new DemoOpcUaServer(port)) { await(server.start()); }
  }

  @Test public void rejectsCredentialExposureAndNonLocalDemoEndpoints() {
    assertThrows(IllegalArgumentException.class,
        () -> OpcUaConnectorConfig.localDemo("opc.tcp://operator:secret@127.0.0.1:4840/scada"));
    assertThrows(IllegalArgumentException.class,
        () -> OpcUaConnectorConfig.localDemo("opc.tcp://192.0.2.1:4840/scada"));
    assertThrows(IllegalArgumentException.class,
        () -> new OpcUaConnectorConfig("opc.tcp://localhost:4840", "None", "None", null, null, null,
            null, "urn:test", "operator", "secret", 250));
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
  }
  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
