package io.hackinvent.scada.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Shared, framework-independent validation, live state and command execution. */
public final class ScadaEngine {
    private record Operation(String commandId, Object value, Instant created, CompletableFuture<CommandResult> result) { }
    private final ScadaConnector connector;
    private final Map<String, TagDefinition> tags;
    private final Map<String, CommandDefinition> commands;
    private final ConcurrentMap<String, TagValue> values = new ConcurrentHashMap<>();
    private final Map<String, Operation> operations = new HashMap<>();
    private final Clock clock;
    private final Duration staleAfter;
    private static final Duration RETENTION = Duration.ofMinutes(30);

    public ScadaEngine(ScadaConnector connector, List<TagDefinition> tags,
                       List<CommandDefinition> commands, Duration staleAfter) {
        this(connector, tags, commands, staleAfter, Clock.systemUTC());
    }

    public ScadaEngine(ScadaConnector connector, List<TagDefinition> tags,
                       List<CommandDefinition> commands, Duration staleAfter, Clock clock) {
        this.connector = Objects.requireNonNull(connector);
        this.clock = Objects.requireNonNull(clock);
        this.staleAfter = Objects.requireNonNull(staleAfter);
        if (staleAfter.isNegative() || staleAfter.isZero()) throw new IllegalArgumentException("staleAfter must be positive");
        Map<String, TagDefinition> tagMap = new LinkedHashMap<>();
        for (TagDefinition tag : tags) {
            if (tagMap.putIfAbsent(tag.id(), tag) != null) throw new IllegalArgumentException("Duplicate tag: " + tag.id());
        }
        Map<String, CommandDefinition> commandMap = new LinkedHashMap<>();
        for (CommandDefinition command : commands) {
            TagDefinition tag = tagMap.get(command.tagId());
            if (tag == null || !tag.writable()) throw new IllegalArgumentException("Command must target a writable configured tag: " + command.id());
            if (commandMap.putIfAbsent(command.id(), command) != null) throw new IllegalArgumentException("Duplicate command: " + command.id());
            if (!command.requiresValue()) validateValue(tag, command, command.fixedValue());
        }
        this.tags = Collections.unmodifiableMap(tagMap);
        this.commands = Collections.unmodifiableMap(commandMap);
    }

    public List<TagDefinition> tags() { return List.copyOf(tags.values()); }
    public List<CommandDefinition> commands() { return List.copyOf(commands.values()); }
    public boolean isConnected() { return connector.isConnected(); }

    public CompletionStage<Void> start() {
        return connector.connect().thenCompose(ignored -> connector.read(tags()))
            .thenCompose(initial -> {
                values.putAll(initial);
                return connector.subscribe(tags(), (id, value) -> {
                    if (tags.containsKey(id)) values.put(id, value);
                });
            });
    }

    public CompletionStage<Map<String, TagValue>> read(List<String> ids) {
        List<TagDefinition> selected = select(ids);
        if (!isConnected()) return CompletableFuture.failedFuture(new ScadaException("disconnected", 503, "OPC UA connection is unavailable"));
        return connector.read(selected).thenApply(current -> { values.putAll(current); return current; });
    }

    private List<TagDefinition> select(List<String> ids) {
        if (ids == null || ids.isEmpty()) return tags();
        if (ids.size() > 256) throw new ScadaException("invalid_arguments", 400, "At most 256 tags may be read at once");
        List<TagDefinition> selected = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            TagDefinition tag = tags.get(id);
            if (tag == null) throw new ScadaException("unknown_tag", 404, "Unknown configured tag: " + id);
            selected.add(tag);
        }
        return List.copyOf(selected);
    }

    public Map<String, TagValue> snapshot() {
        Map<String, TagValue> snapshot = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (TagDefinition tag : tags.values()) {
            TagValue value = values.get(tag.id());
            if (value == null) {
                snapshot.put(tag.id(), new TagValue(null, "UNAVAILABLE", null, null, null, null));
            } else {
                // Source values can remain unchanged for a long time. A successful read or
                // subscription keep-alive refresh is needed before declaring cached data fresh.
                boolean stale = !isConnected() || value.receivedAt() == null
                    || value.receivedAt().plus(staleAfter).isBefore(now);
                snapshot.put(tag.id(), stale ? new TagValue(value.value(), "STALE", value.statusCode(),
                    value.sourceTimestamp(), value.serverTimestamp(), value.receivedAt()) : value);
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public CompletionStage<CommandResult> execute(String commandId, Object suppliedValue,
                                                  boolean operator, String requestId) {
        if (!operator) throw new ScadaException("forbidden", 403, "Operator permission is required");
        CommandDefinition command = commands.get(commandId);
        if (command == null) throw new ScadaException("unknown_command", 404, "Unknown configured command: " + commandId);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new ScadaException("request_id_required", 400, "A requestId or Idempotency-Key (1–128 safe characters) is required");
        if (!command.requiresValue() && suppliedValue != null)
            throw new ScadaException("invalid_arguments", 400, "This command has a fixed value");
        Object value = validateValue(tags.get(command.tagId()), command,
            command.requiresValue() ? suppliedValue : command.fixedValue());
        Operation operation;
        synchronized (operations) {
            Operation previous = operations.get(requestId);
            if (previous != null) {
                if (!previous.commandId().equals(commandId) || !Objects.equals(previous.value(), value))
                    throw new ScadaException("idempotency_conflict", 409, "Request identifier was already used for a different operation");
                return previous.result();
            }
            Instant cutoff = clock.instant().minus(RETENTION);
            operations.entrySet().removeIf(entry -> entry.getValue().created().isBefore(cutoff) && entry.getValue().result().isDone());
            if (operations.size() >= 4096) throw new ScadaException("command_capacity", 503, "Command journal is full; retry later");
            if (!isConnected()) throw new ScadaException("disconnected", 503, "OPC UA connection is unavailable");
            operation = new Operation(commandId, value, clock.instant(), new CompletableFuture<>());
            operations.put(requestId, operation);
        }
        try {
            connector.write(tags.get(command.tagId()), value).whenComplete((ignored, error) -> {
                if (error != null) operation.result().completeExceptionally(new ScadaException("write_outcome_unknown", 502,
                    "Write was not acknowledged; inspect the equipment state before issuing another command"));
                else operation.result().complete(new CommandResult(commandId, requestId, "accepted", value, clock.instant()));
            });
        } catch (RuntimeException error) {
            operation.result().completeExceptionally(new ScadaException("write_outcome_unknown", 502,
                "Write outcome is unknown; inspect the equipment state before issuing another command"));
        }
        return operation.result();
    }

    private static Object validateValue(TagDefinition tag, CommandDefinition command, Object input) {
        if (input == null) throw new ScadaException("invalid_arguments", 400, "A value is required");
        Object value;
        switch (tag.dataType().toLowerCase(Locale.ROOT)) {
            case "boolean" -> {
                if (!(input instanceof Boolean)) throw new ScadaException("invalid_arguments", 400, "A boolean value is required");
                value = input;
            }
            case "double", "float", "integer", "int32" -> {
                if (!(input instanceof Number numeric) || !Double.isFinite(numeric.doubleValue()))
                    throw new ScadaException("invalid_arguments", 400, "A finite numeric value is required");
                double number = ((Number) input).doubleValue();
                if ((command.min() != null && number < command.min()) || (command.max() != null && number > command.max()))
                    throw new ScadaException("out_of_range", 400, "Value is outside the configured command range");
                if (tag.dataType().equalsIgnoreCase("integer") || tag.dataType().equalsIgnoreCase("int32")) {
                    if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
                        throw new ScadaException("invalid_arguments", 400, "An Int32 value is required");
                    value = (int) number;
                } else if (tag.dataType().equalsIgnoreCase("float")) {
                    if (!Float.isFinite((float) number)) throw new ScadaException("invalid_arguments", 400, "Float value is out of range");
                    value = (float) number;
                } else value = number;
            }
            case "string" -> {
                if (!(input instanceof String text) || text.length() > 4096)
                    throw new ScadaException("invalid_arguments", 400, "A string of at most 4096 characters is required");
                value = input;
            }
            default -> throw new ScadaException("unsupported_type", 400, "Unsupported writable data type");
        }
        return value;
    }
}
