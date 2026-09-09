package io.hackinvent.scada.opcua;

import io.hackinvent.scada.core.NodeDescriptor;
import io.hackinvent.scada.core.ScadaConnector;
import io.hackinvent.scada.core.TagDefinition;
import io.hackinvent.scada.core.TagValue;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.UaSession;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/** One long-lived client per endpoint, shared by HTTP, MCP, and browser tools. */
public final class MiloScadaConnector implements ScadaConnector {
  private static final Logger log = LoggerFactory.getLogger(MiloScadaConnector.class);
  private final OpcUaConnectorConfig settings;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "scada-opcua-lifecycle"); thread.setDaemon(true); return thread;
  });
  private volatile OpcUaClient client;
  private volatile boolean connected;
  private volatile boolean running;
  private FileBasedTrustListManager trustList;
  private CompletableFuture<Void> opening;
  private CompletableFuture<Void> shuttingDown;

  public MiloScadaConnector(OpcUaConnectorConfig settings) { this.settings = settings; }

  @Override public synchronized CompletionStage<Void> connect() {
    if (shuttingDown != null) return CompletableFuture.failedFuture(new IllegalStateException("Connector has shut down"));
    if (opening != null && running) return opening;
    running = true;
    opening = new CompletableFuture<>();
    executor.execute(this::openClient);
    return opening;
  }

  private void openClient() {
    if (!running) return;
    try {
      OpcUaClient created = createClient();
      synchronized (this) {
        if (!running) {
          created.disconnectAsync().whenComplete((ignored, failure) -> closeTrustList());
          return;
        }
        client = created;
        created.addSessionActivityListener(new SessionActivityListener() {
          @Override public void onSessionActive(UaSession session) {
            if (running && client == created) {
              connected = true;
              opening.complete(null);
            }
          }
          @Override public void onSessionInactive(UaSession session) {
            if (client == created) connected = false;
          }
        });
        created.connectAsync().whenComplete((ignored, error) -> {
          if (error != null) log.warn("OPC UA session unavailable; Milo will reconnect: {}", error.getClass().getSimpleName());
        });
      }
    } catch (Exception failure) {
      // Discovery occurs before Milo owns a transport, so retry it ourselves.
      log.warn("OPC UA discovery unavailable; retrying in 2 seconds: {}", failure.getClass().getSimpleName());
      closeTrustList();
      if (running) executor.schedule(this::openClient, 2, TimeUnit.SECONDS);
    }
  }

  private OpcUaClient createClient() throws Exception {
    SecurityPolicy policy = SecurityPolicy.valueOf(settings.securityPolicy());
    MessageSecurityMode mode = MessageSecurityMode.valueOf(settings.securityMode());
    KeyPair keyPair;
    X509Certificate[] certificateChain;
    DefaultClientCertificateValidator validator;
    if (policy != SecurityPolicy.None) {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      char[] password = settings.keyStorePassword().toCharArray();
      try (InputStream input = Files.newInputStream(settings.keyStorePath())) { keyStore.load(input, password); }
      X509Certificate certificate = (X509Certificate) keyStore.getCertificate(settings.keyAlias());
      PrivateKey privateKey = (PrivateKey) keyStore.getKey(settings.keyAlias(), password);
      Arrays.fill(password, '\0');
      if (certificate == null || privateKey == null) throw new IllegalArgumentException("Missing OPC UA client key alias");
      keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
      certificateChain = Arrays.stream(keyStore.getCertificateChain(settings.keyAlias()))
          .map(X509Certificate.class::cast).toArray(X509Certificate[]::new);
      trustList = FileBasedTrustListManager.createAndInitialize(settings.trustDirectory());
      validator = certificateValidator(trustList, settings.trustDirectory());
    } else { keyPair = null; certificateChain = null; validator = null; }

    return OpcUaClient.create(settings.endpointUrl(), endpoints -> endpoints.stream()
        .filter(e -> policy.getUri().equals(e.getSecurityPolicyUri()) && mode == e.getSecurityMode())
        .findFirst(), transport -> {}, config -> {
          config.setApplicationUri(settings.applicationUri())
              .setApplicationName(LocalizedText.english("HackInvent SCADA"))
              .setRequestTimeout(uint(5000));
          if (keyPair != null) config.setKeyPair(keyPair).setCertificate(certificateChain[0])
              .setCertificateChain(certificateChain).setCertificateValidator(validator);
          if (settings.username() != null && !settings.username().isBlank()) {
            config.setIdentityProvider(new UsernameProvider(settings.username(), settings.password()));
          }
        });
  }

  static DefaultClientCertificateValidator certificateValidator(FileBasedTrustListManager trust, Path directory)
      throws IOException {
    return new DefaultClientCertificateValidator(trust, ValidationCheck.ALL_OPTIONAL_CHECKS,
        FileBasedCertificateQuarantine.create(directory.resolve("rejected/certs")));
  }

  @Override public boolean isConnected() { return connected; }

  private OpcUaClient requireClient() {
    OpcUaClient current = client;
    if (!running || !connected || current == null) throw new IllegalStateException("OPC UA is disconnected");
    return current;
  }

  private NodeId resolve(OpcUaClient current, String id) {
    return ExpandedNodeId.parse(id).toNodeId(current.getNamespaceTable())
        .orElseThrow(() -> new IllegalArgumentException("Unknown namespace or remote OPC UA NodeId: " + id));
  }

  @Override public CompletionStage<Map<String, TagValue>> read(List<TagDefinition> tags) {
    try {
      OpcUaClient current = requireClient();
      List<NodeId> ids = tags.stream().map(t -> resolve(current, t.nodeId())).toList();
      return current.readValuesAsync(0, TimestampsToReturn.Both, ids).thenApply(values -> {
        Map<String, TagValue> result = new LinkedHashMap<>();
        for (int i = 0; i < tags.size(); i++) result.put(tags.get(i).id(), convert(values.get(i)));
        return result;
      });
    } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
  }

  @Override public CompletionStage<Void> write(TagDefinition tag, Object value) {
    try {
      if (!tag.writable()) throw new IllegalArgumentException("Tag is read-only: " + tag.id());
      OpcUaClient current = requireClient();
      DataValue data = DataValue.valueOnly(new Variant(typedValue(tag.dataType(), value)));
      return current.writeValuesAsync(List.of(resolve(current, tag.nodeId())), List.of(data))
          .thenApply(results -> {
            if (results.isEmpty() || !results.get(0).isGood()) {
              throw new java.util.concurrent.CompletionException(new UaException(
                  results.isEmpty() ? StatusCode.BAD : results.get(0), "OPC UA write was rejected"));
            }
            return null;
          });
    } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
  }

  private static Object typedValue(String dataType, Object value) {
    if (value == null) throw new IllegalArgumentException("A command value is required");
    return switch (dataType.toLowerCase(Locale.ROOT)) {
      case "boolean" -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean"); yield value; }
      case "double" -> ((Number) value).doubleValue();
      case "float" -> ((Number) value).floatValue();
      case "int32", "integer" -> ((Number) value).intValue();
      case "int64", "long" -> ((Number) value).longValue();
      case "string" -> { if (!(value instanceof String)) throw new IllegalArgumentException("Expected string"); yield value; }
      default -> throw new IllegalArgumentException("Unsupported command data type: " + dataType);
    };
  }

  @Override public CompletionStage<Void> subscribe(List<TagDefinition> tags, BiConsumer<String, TagValue> listener) {
    return CompletableFuture.runAsync(() -> {
      OpcUaClient current = requireClient();
      OpcUaSubscription subscription = new OpcUaSubscription(current);
      subscription.setPublishingInterval(settings.publishingIntervalMillis());
      AtomicBoolean recovering = new AtomicBoolean();
      List<TagDefinition> requestedTags = List.copyOf(tags);
      subscription.setSubscriptionListener(new OpcUaSubscription.SubscriptionListener() {
        @Override public void onTransferFailed(OpcUaSubscription lost, StatusCode status) {
          recover(lost, requestedTags, listener, recovering);
        }
        @Override public void onStatusChanged(OpcUaSubscription lost, StatusCode status) {
          if (status.getValue() == StatusCodes.Bad_Timeout) recover(lost, requestedTags, listener, recovering);
        }
      });
      addMonitoredItems(subscription, current, requestedTags, listener);
      try {
        subscription.create();
        subscription.synchronizeMonitoredItems();
      } catch (Exception failure) {
        subscription.deleteAsync();
        throw new java.util.concurrent.CompletionException(failure);
      }
    }, executor);
  }

  private void addMonitoredItems(OpcUaSubscription subscription, OpcUaClient current,
      List<TagDefinition> tags, BiConsumer<String, TagValue> listener) {
    for (TagDefinition tag : tags) {
      OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(resolve(current, tag.nodeId()));
      item.setSamplingInterval(settings.publishingIntervalMillis());
      item.setDataValueListener((ignored, data) -> listener.accept(tag.id(), convert(data)));
      subscription.addMonitoredItem(item);
    }
  }

  private void recover(OpcUaSubscription subscription, List<TagDefinition> tags,
      BiConsumer<String, TagValue> listener, AtomicBoolean recovering) {
    if (!running || !recovering.compareAndSet(false, true)) return;
    executor.schedule(() -> {
      if (!running) { recovering.set(false); return; }
      try {
        OpcUaClient current = requireClient();
        subscription.removeMonitoredItems(subscription.getMonitoredItems());
        // Namespace indexes may change after a server restart: resolve configured URIs again.
        addMonitoredItems(subscription, current, tags, listener);
        subscription.create();
        subscription.synchronizeMonitoredItems();
        recovering.set(false);
      } catch (Exception failure) {
        subscription.deleteAsync().whenComplete((ignored, deleteError) -> {
          subscription.reset();
          recovering.set(false);
          recover(subscription, tags, listener, recovering);
        });
      }
    }, 2, TimeUnit.SECONDS);
  }

  @Override public CompletionStage<List<NodeDescriptor>> browse(String nodeId) {
    try {
      OpcUaClient current = requireClient();
      NodeId root = nodeId == null || nodeId.isBlank() ? NodeIds.ObjectsFolder : resolve(current, nodeId);
      BrowseDescription description = new BrowseDescription(root, BrowseDirection.Forward,
          NodeIds.HierarchicalReferences, true, uint(0), uint(BrowseResultMask.All.getValue()));
      return current.browseAsync(description).thenCompose(result -> browsePage(current, result, new ArrayList<>()));
    } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
  }

  private CompletionStage<List<NodeDescriptor>> browsePage(OpcUaClient current, BrowseResult page,
      List<NodeDescriptor> accumulated) {
    if (!page.getStatusCode().isGood()) return CompletableFuture.failedFuture(new UaException(page.getStatusCode()));
    if (page.getReferences() != null) for (ReferenceDescription reference : page.getReferences()) {
      accumulated.add(new NodeDescriptor(reference.getNodeId().toParseableString(),
          reference.getBrowseName().getName(), reference.getDisplayName().text(), reference.getNodeClass().name()));
    }
    ByteString continuation = page.getContinuationPoint();
    if (continuation == null || continuation.isNullOrEmpty()) return CompletableFuture.completedFuture(List.copyOf(accumulated));
    if (accumulated.size() > 10_000) {
      return current.browseNextAsync(true, List.of(continuation)).thenCompose(ignored ->
          CompletableFuture.failedFuture(new IllegalStateException("Browse result exceeds 10000 nodes")));
    }
    return current.browseNextAsync(false, List.of(continuation))
        .thenCompose(next -> browsePage(current, next.getResults()[0], accumulated));
  }

  private static TagValue convert(DataValue value) {
    StatusCode status = value.statusCode();
    String quality = status.isGood() ? "GOOD" : status.isUncertain() ? "UNCERTAIN" : "BAD";
    return new TagValue(value.value().value(), quality, status.getValue(),
        instant(value.sourceTime()), instant(value.serverTime()), Instant.now());
  }

  private static Instant instant(DateTime time) { return time == null || time.isNull() ? null : time.getJavaInstant(); }

  @Override public synchronized CompletionStage<Void> disconnect() {
    running = false;
    connected = false;
    if (opening != null && !opening.isDone()) opening.completeExceptionally(new IllegalStateException("Connector stopped"));
    OpcUaClient current = client;
    client = null;
    return (current == null ? CompletableFuture.<Void>completedFuture(null)
        : current.disconnectAsync().thenApply(ignored -> (Void) null))
        .whenComplete((ignored, failure) -> closeTrustList());
  }

  private void closeTrustList() {
    if (trustList != null) {
      try { trustList.close(); } catch (Exception ignored) { log.debug("Could not close OPC UA trust store watcher"); }
      trustList = null;
    }
  }

  /** Permanently stop this connector without blocking a Play lifecycle thread. */
  public synchronized CompletionStage<Void> shutdown() {
    if (shuttingDown != null) return shuttingDown;
    shuttingDown = new CompletableFuture<>();
    disconnect().whenComplete((ignored, failure) -> {
      executor.shutdownNow();
      if (failure == null) shuttingDown.complete(null);
      else shuttingDown.completeExceptionally(failure);
    });
    return shuttingDown;
  }

  @Override public void close() { shutdown().toCompletableFuture().join(); }
}
