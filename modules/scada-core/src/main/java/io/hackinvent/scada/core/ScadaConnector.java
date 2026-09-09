package io.hackinvent.scada.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

public interface ScadaConnector extends AutoCloseable {
    CompletionStage<Void> connect();
    CompletionStage<List<NodeDescriptor>> browse(String nodeId);
    CompletionStage<Map<String, TagValue>> read(List<TagDefinition> tags);
    CompletionStage<Void> subscribe(List<TagDefinition> tags, BiConsumer<String, TagValue> listener);
    CompletionStage<Void> write(TagDefinition tag, Object value);
    boolean isConnected();
    CompletionStage<Void> disconnect();
    @Override default void close() { disconnect(); }
}
