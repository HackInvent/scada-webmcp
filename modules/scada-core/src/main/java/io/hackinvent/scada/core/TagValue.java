package io.hackinvent.scada.core;

import java.time.Instant;

/** Preserve OPC UA quality and timestamps through every exposure surface. */
public record TagValue(Object value, String quality, Long statusCode,
                       Instant sourceTimestamp, Instant serverTimestamp, Instant receivedAt) { }
