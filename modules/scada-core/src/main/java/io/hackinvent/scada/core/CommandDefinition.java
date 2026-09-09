package io.hackinvent.scada.core;

import java.util.Objects;

/** Commands, including fixed-value actions, are explicitly declared by the integrator. */
public record CommandDefinition(String id, String label, String description, String tagId,
                                boolean requiresValue, Object fixedValue, Double min, Double max) {
    public CommandDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(label);
        Objects.requireNonNull(description); Objects.requireNonNull(tagId);
        if (id.isBlank() || tagId.isBlank()) throw new IllegalArgumentException("Command identifiers are required");
        if (!requiresValue && fixedValue == null) throw new IllegalArgumentException("Fixed commands require a value");
        if ((min != null && !Double.isFinite(min)) || (max != null && !Double.isFinite(max)))
            throw new IllegalArgumentException("Command range bounds must be finite");
        if (min != null && max != null && min > max) throw new IllegalArgumentException("Invalid command range");
    }
}
