package io.hackinvent.scada.core;

import java.time.Instant;

/** Accepted means the OPC UA server acknowledged the write, not physical completion. */
public record CommandResult(String commandId, String requestId, String status, Object value, Instant timestamp) { }
