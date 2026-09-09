package io.hackinvent.scada.core;

import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

public class ScadaEngineTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final TagDefinition PUMP = new TagDefinition("pump.running", "ns=2;s=Pump", "Pump", "", "Boolean", true);
    private static final TagDefinition LEVEL = new TagDefinition("tank.setpoint", "ns=2;s=Setpoint", "Setpoint", "%", "Double", true);
    private static final TagDefinition COUNT = new TagDefinition("counter", "ns=2;s=Count", "Count", "", "Int32", true);
    private static final TagDefinition TEMP = new TagDefinition("tank.temperature", "ns=2;s=Temperature", "Temperature", "°C", "Double", false);
    private FakeConnector connector;
    private MutableClock clock;
    private ScadaEngine engine;

    @Before
    public void setup() {
        connector = new FakeConnector();
        clock = new MutableClock();
        engine = new ScadaEngine(connector, List.of(PUMP, LEVEL, COUNT, TEMP), List.of(
                new CommandDefinition("pump.start", "Start", "Start pump", PUMP.id(), false, true, null, null),
                new CommandDefinition("pump.stop", "Stop", "Stop pump", PUMP.id(), false, false, null, null),
                new CommandDefinition("tank.set", "Setpoint", "Set setpoint", LEVEL.id(), true, null, 0.0, 100.0),
                new CommandDefinition("count.set", "Count", "Set count", COUNT.id(), true, null, null, null)),
                Duration.ofSeconds(10), clock);
    }

    @Test
    public void readerCannotExecuteEvenWithValidCommandAndRequestId() {
        expect("forbidden", () -> engine.execute("pump.start", null, false, "one"));
        assertEquals(0, connector.writes.get());
    }

    @Test
    public void validatesKnownCommandsFixedValuesAndRequestIdsBeforeWriting() {
        expect("unknown_command", () -> engine.execute("ns=2;s=arbitrary", null, true, "one"));
        expect("request_id_required", () -> engine.execute("pump.start", null, true, null));
        expect("request_id_required", () -> engine.execute("pump.start", null, true, "unsafe key"));
        expect("invalid_arguments", () -> engine.execute("pump.start", false, true, "one"));
        assertEquals(0, connector.writes.get());
    }

    @Test
    public void rejectsMissingWrongNonfiniteAndOutOfRangeValues() {
        for (Object value : new Object[]{null, "25", true, Double.NaN, Double.POSITIVE_INFINITY}) {
            expect("invalid_arguments", () -> engine.execute("tank.set", value, true, "invalid"));
        }
        expect("out_of_range", () -> engine.execute("tank.set", -0.01, true, "low"));
        expect("out_of_range", () -> engine.execute("tank.set", 100.01, true, "high"));
        expect("invalid_arguments", () -> engine.execute("count.set", 1.5, true, "fraction"));
        expect("invalid_arguments", () -> engine.execute("count.set", (long) Integer.MAX_VALUE + 1, true, "overflow"));
        assertEquals(0, connector.writes.get());
    }

    @Test
    public void normalizedValuesAndInclusiveBoundsReachConnector() {
        engine.execute("tank.set", 0, true, "min").toCompletableFuture().join();
        assertEquals(0.0, connector.lastValue);
        engine.execute("tank.set", 100, true, "max").toCompletableFuture().join();
        assertEquals(100.0, connector.lastValue);
        CommandResult result = engine.execute("pump.start", null, true, "start").toCompletableFuture().join();
        assertEquals(true, connector.lastValue);
        assertEquals("accepted", result.status());
        assertEquals("start", result.requestId());
        engine.execute("count.set", 12L, true, "int").toCompletableFuture().join();
        assertEquals(Integer.valueOf(12), connector.lastValue);
    }

    @Test
    public void identicalRetriesReturnOriginalResultAndWriteOnce() {
        CommandResult first = engine.execute("tank.set", 25, true, "same").toCompletableFuture().join();
        CommandResult retry = engine.execute("tank.set", 25.0, true, "same").toCompletableFuture().join();
        assertEquals(first, retry);
        assertEquals(1, connector.writes.get());
        // Retrieving an acknowledgement does not need a new OPC UA connection.
        connector.connected = false;
        assertEquals(first, engine.execute("tank.set", 25.0, true, "same").toCompletableFuture().join());
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void retriesStillRequireOperatorPermission() {
        engine.execute("pump.start", null, true, "same").toCompletableFuture().join();
        expect("forbidden", () -> engine.execute("pump.start", null, false, "same"));
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void conflictingRequestIdCannotChangeValueOrCommand() {
        engine.execute("tank.set", 25, true, "same").toCompletableFuture().join();
        expect("idempotency_conflict", () -> engine.execute("tank.set", 26, true, "same"));
        expect("idempotency_conflict", () -> engine.execute("pump.stop", null, true, "same"));
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void concurrentDuplicateRequestsShareExactlyOnePendingWrite() throws Exception {
        connector.pendingWrite = new CompletableFuture<>();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CompletionStage<CommandResult>>> submitted = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                submitted.add(threads.submit(() -> {
                    start.await();
                    return engine.execute("pump.start", null, true, "concurrent");
                }));
            }
            start.countDown();
            List<CompletionStage<CommandResult>> results = new ArrayList<>();
            for (Future<CompletionStage<CommandResult>> task : submitted) results.add(task.get(5, TimeUnit.SECONDS));
            assertEquals(1, connector.writes.get());
            for (CompletionStage<CommandResult> result : results) assertFalse(result.toCompletableFuture().isDone());
            connector.pendingWrite.complete(null);
            CommandResult first = results.get(0).toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (CompletionStage<CommandResult> result : results) assertEquals(first, result.toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void failedWriteHasUnknownOutcomeAndIsNeverRetriedWithSameKey() {
        connector.pendingWrite = CompletableFuture.failedFuture(new IllegalStateException("Connection lost after write"));
        expect("write_outcome_unknown", () -> engine.execute("pump.start", null, true, "failed").toCompletableFuture().join());
        connector.pendingWrite = CompletableFuture.completedFuture(null);
        expect("write_outcome_unknown", () -> engine.execute("pump.start", null, true, "failed").toCompletableFuture().join());
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void synchronousConnectorFailureAlsoRetainsUnknownOutcome() {
        connector.writeFailure = new IllegalStateException("Write outcome unavailable");
        expect("write_outcome_unknown", () -> engine.execute("pump.start", null, true, "failed").toCompletableFuture().join());
        connector.writeFailure = null;
        expect("write_outcome_unknown", () -> engine.execute("pump.start", null, true, "failed").toCompletableFuture().join());
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void disconnectedCommandsAndReadsDoNotCallConnector() {
        connector.connected = false;
        expect("disconnected", () -> engine.execute("pump.start", null, true, "offline"));
        expect("disconnected", () -> engine.read(List.of(TEMP.id())).toCompletableFuture().join());
        assertEquals(0, connector.writes.get());
        assertEquals(0, connector.reads.get());
        connector.connected = true;
        engine.execute("pump.start", null, true, "offline").toCompletableFuture().join();
        assertEquals(1, connector.writes.get());
    }

    @Test
    public void staleOrDisconnectedSnapshotsPreserveLastValueAndOpcQualityCode() {
        TagValue value = new TagValue(23.5, "GOOD", 0L, NOW.minusSeconds(100), NOW, NOW);
        connector.readValues = Map.of(TEMP.id(), value);
        engine.start().toCompletableFuture().join();
        assertEquals(value, engine.snapshot().get(TEMP.id()));
        assertEquals("UNAVAILABLE", engine.snapshot().get(PUMP.id()).quality());
        clock.now = NOW.plusSeconds(11);
        TagValue stale = engine.snapshot().get(TEMP.id());
        assertEquals("STALE", stale.quality());
        assertEquals(23.5, stale.value());
        assertEquals(Long.valueOf(0), stale.statusCode());
        assertEquals(value.sourceTimestamp(), stale.sourceTimestamp());
        clock.now = NOW;
        connector.connected = false;
        assertEquals("STALE", engine.snapshot().get(TEMP.id()).quality());
    }

    @Test
    public void refreshedReceiptTimeRestoresFreshnessEvenWhenSourceValueDidNotChange() {
        connector.readValues = Map.of(TEMP.id(), new TagValue(23.5, "GOOD", 0L, NOW.minusSeconds(100), NOW, NOW));
        engine.start().toCompletableFuture().join();
        clock.now = NOW.plusSeconds(11);
        assertEquals("STALE", engine.snapshot().get(TEMP.id()).quality());
        TagValue refresh = new TagValue(23.5, "GOOD", 0L, NOW.minusSeconds(100), clock.now, clock.now);
        connector.listener.accept(TEMP.id(), refresh);
        assertEquals(refresh, engine.snapshot().get(TEMP.id()));
        connector.listener.accept("unconfigured", refresh);
        assertFalse(engine.snapshot().containsKey("unconfigured"));
    }

    @Test
    public void readsOnlyConfiguredTagsAndRejectsOversizedSelection() {
        expect("unknown_tag", () -> engine.read(List.of("ns=2;s=arbitrary")));
        expect("invalid_arguments", () -> engine.read(java.util.Collections.nCopies(257, TEMP.id())));
        assertEquals(0, connector.reads.get());
        engine.read(List.of(TEMP.id(), TEMP.id())).toCompletableFuture().join();
        assertEquals(List.of(TEMP), connector.lastRead);
    }

    @Test
    public void configurationRejectsReadonlyTargetsAndNonfiniteBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ScadaEngine(connector, List.of(TEMP), List.of(
                new CommandDefinition("bad", "Bad", "Bad", TEMP.id(), true, null, null, null)), Duration.ofSeconds(10)));
        for (Double bound : new Double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CommandDefinition("bad", "Bad", "Bad", LEVEL.id(), true, null, bound, 100.0));
            assertThrows(IllegalArgumentException.class,
                    () -> new CommandDefinition("bad", "Bad", "Bad", LEVEL.id(), true, null, 0.0, bound));
        }
    }

    private static void expect(String code, Runnable action) {
        Throwable failure = assertThrows(RuntimeException.class, action::run);
        if (failure instanceof CompletionException) failure = failure.getCause();
        assertTrue("Expected ScadaException, got " + failure, failure instanceof ScadaException);
        assertEquals(code, ((ScadaException) failure).getCode());
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeConnector implements ScadaConnector {
        private volatile boolean connected = true;
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile Object lastValue;
        private List<TagDefinition> lastRead;
        private Map<String, TagValue> readValues = Map.of();
        private CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);
        private RuntimeException writeFailure;
        private BiConsumer<String, TagValue> listener;
        @Override public CompletionStage<Void> connect() { connected = true; return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<List<NodeDescriptor>> browse(String nodeId) { return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletionStage<Map<String, TagValue>> read(List<TagDefinition> tags) {
            reads.incrementAndGet(); lastRead = tags; return CompletableFuture.completedFuture(readValues);
        }
        @Override public CompletionStage<Void> subscribe(List<TagDefinition> tags, BiConsumer<String, TagValue> listener) {
            this.listener = listener; return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> write(TagDefinition tag, Object value) {
            writes.incrementAndGet(); lastValue = value;
            if (writeFailure != null) throw writeFailure;
            return pendingWrite;
        }
        @Override public boolean isConnected() { return connected; }
        @Override public CompletionStage<Void> disconnect() { connected = false; return CompletableFuture.completedFuture(null); }
    }
}
