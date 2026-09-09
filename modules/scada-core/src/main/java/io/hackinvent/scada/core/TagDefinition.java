package io.hackinvent.scada.core;

import java.util.Objects;

/** A stable application identifier mapped to an OPC UA node. */
public record TagDefinition(String id, String nodeId, String label, String unit,
                            String dataType, boolean writable) {
    public TagDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(nodeId);
        Objects.requireNonNull(label); Objects.requireNonNull(dataType);
        if (id.isBlank() || nodeId.isBlank()) throw new IllegalArgumentException("Tag id and nodeId are required");
        unit = unit == null ? "" : unit;
    }
}
