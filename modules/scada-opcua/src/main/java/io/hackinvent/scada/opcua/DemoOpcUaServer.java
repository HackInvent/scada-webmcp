package io.hackinvent.scada.opcua;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;

/** Opt-in, real OPC UA simulator. Its unsecured endpoint always binds to IPv4 loopback. */
public final class DemoOpcUaServer implements AutoCloseable {
  public static final String NAMESPACE_URI = "urn:hackinvent:scada:demo";
  public static final String TEMPERATURE_NODE_ID = "nsu=" + NAMESPACE_URI + ";s=tank.temperature";
  public static final String PUMP_NODE_ID = "nsu=" + NAMESPACE_URI + ";s=pump.running";
  public static final String SETPOINT_NODE_ID = "nsu=" + NAMESPACE_URI + ";s=tank.setpoint";
  public static final String ROOT_NODE_ID = "nsu=" + NAMESPACE_URI + ";s=Demo";
  private final int port;
  private CompletableFuture<Void> starting;
  private CompletableFuture<Void> stopping;
  private final OpcUaServer server;
  private final DemoNamespace namespace;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "scada-demo-process"); thread.setDaemon(true); return thread;
  });

  public DemoOpcUaServer(int port) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid OPC UA port");
    this.port = port;
    EndpointConfig endpoint = EndpointConfig.newBuilder()
        .setBindAddress("127.0.0.1").setHostname("127.0.0.1").setBindPort(port)
        .setPath("/scada").setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
        .setSecurityPolicy(SecurityPolicy.None).setSecurityMode(MessageSecurityMode.None)
        .addTokenPolicy(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS).build();
    OpcUaServerConfig config = OpcUaServerConfig.builder()
        .setApplicationUri("urn:hackinvent:scada:demo:server")
        .setApplicationName(LocalizedText.english("HackInvent SCADA simulator"))
        .setProductUri("urn:hackinvent:scada")
        .setCertificateManager(new DefaultCertificateManager(new MemoryCertificateQuarantine()))
        .setEndpoints(Set.of(endpoint)).build();
    server = new OpcUaServer(config, profile ->
        new OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()));
    namespace = new DemoNamespace(server);
  }

  public String endpointUrl() { return "opc.tcp://127.0.0.1:" + port + "/scada"; }

  public synchronized CompletionStage<Void> start() {
    if (stopping != null) return CompletableFuture.failedFuture(new IllegalStateException("Simulator has stopped"));
    if (starting != null) return starting;
    try {
      namespace.startup();
      starting = server.startup().thenAccept(ignored ->
          scheduler.scheduleAtFixedRate(namespace::tick, 250, 250, TimeUnit.MILLISECONDS));
    } catch (Exception failure) {
      starting = CompletableFuture.failedFuture(failure);
    }
    starting = starting.exceptionallyCompose(failure -> stop().handle((ignored, cleanupFailure) -> {
      if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
      throw new CompletionException(failure);
    }));
    return starting;
  }

  public synchronized CompletionStage<Void> stop() {
    if (stopping != null) return stopping;
    stopping = new CompletableFuture<>();
    scheduler.shutdownNow();
    Exception namespaceError = null;
    try { namespace.shutdown(); } catch (Exception failure) { namespaceError = failure; }
    Exception shutdownError = namespaceError;
    try {
      server.shutdown().whenComplete((ignored, failure) -> {
        if (failure != null) stopping.completeExceptionally(failure);
        else if (shutdownError != null) stopping.completeExceptionally(shutdownError);
        else stopping.complete(null);
      });
    } catch (Exception failure) { stopping.completeExceptionally(failure); }
    return stopping;
  }

  @Override public void close() { stop().toCompletableFuture().join(); }

  private static final class DemoNamespace extends ManagedNamespaceWithLifecycle {
    private final SubscriptionModel subscriptions;
    private UaVariableNode temperature;
    private UaVariableNode pump;
    private UaVariableNode setpoint;
    private double degrees = 24.0;

    DemoNamespace(OpcUaServer server) {
      super(server, NAMESPACE_URI);
      subscriptions = new SubscriptionModel(server, this);
      getLifecycleManager().addLifecycle(subscriptions);
      getLifecycleManager().addStartupTask(this::createNodes);
    }

    private void createNodes() {
      UaFolderNode folder = new UaFolderNode(getNodeContext(), newNodeId("Demo"),
          newQualifiedName("Demo"), LocalizedText.english("SCADA demo"));
      getNodeManager().addNode(folder);
      folder.addReference(new Reference(folder.getNodeId(), NodeIds.Organizes,
          NodeIds.ObjectsFolder.expanded(), false));
      temperature = variable(folder, "tank.temperature", "Tank temperature", NodeIds.Double, false, degrees);
      pump = variable(folder, "pump.running", "Pump running", NodeIds.Boolean, true, false);
      setpoint = variable(folder, "tank.setpoint", "Tank temperature setpoint", NodeIds.Double, true, 22.0);
    }

    private UaVariableNode variable(UaFolderNode folder, String id, String label, NodeId type,
        boolean writable, Object initial) {
      UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
          .setNodeId(newNodeId(id)).setBrowseName(newQualifiedName(id))
          .setDisplayName(LocalizedText.english(label)).setDataType(type)
          .setTypeDefinition(NodeIds.BaseDataVariableType)
          .setAccessLevel(writable ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY)
          .setUserAccessLevel(writable ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY).build();
      node.setValue(sample(initial));
      getNodeManager().addNode(node);
      folder.addOrganizes(node);
      return node;
    }

    private static DataValue sample(Object value) {
      DateTime now = DateTime.now();
      return new DataValue(new Variant(value), StatusCode.GOOD, now, now);
    }

    void tick() {
      boolean running = Boolean.TRUE.equals(pump.getValue().value().value());
      double target = running ? ((Number) setpoint.getValue().value().value()).doubleValue() : 30.0;
      degrees += (target - degrees) * 0.02;
      temperature.setValue(sample(Math.round(degrees * 100.0) / 100.0));
    }

    @Override public void onDataItemsCreated(List<DataItem> items) { subscriptions.onDataItemsCreated(items); }
    @Override public void onDataItemsModified(List<DataItem> items) { subscriptions.onDataItemsModified(items); }
    @Override public void onDataItemsDeleted(List<DataItem> items) { subscriptions.onDataItemsDeleted(items); }
    @Override public void onMonitoringModeChanged(List<MonitoredItem> items) { subscriptions.onMonitoringModeChanged(items); }
  }
}
