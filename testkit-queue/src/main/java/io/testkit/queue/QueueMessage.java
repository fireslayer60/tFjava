package io.testkit.queue;

import java.util.Collections;
import java.util.Map;

/** Represents a consumed message with its payload, headers, and metadata. */
public final class QueueMessage {

    private final String topic;
    private final String key;
    private final String value;
    private final Map<String, String> headers;
    private final long offset;
    private final int partition;

    public QueueMessage(String topic, String key, String value,
                        Map<String, String> headers, long offset, int partition) {
        this.topic     = topic;
        this.key       = key;
        this.value     = value;
        this.headers   = headers != null ? Collections.unmodifiableMap(headers) : Map.of();
        this.offset    = offset;
        this.partition = partition;
    }

    public String topic()           { return topic; }
    public String key()             { return key; }
    public String value()           { return value; }
    public Map<String, String> headers() { return headers; }
    public long offset()            { return offset; }
    public int partition()          { return partition; }

    @Override
    public String toString() {
        return "QueueMessage{topic='%s', key='%s', value='%s'}".formatted(topic, key, value);
    }
}
