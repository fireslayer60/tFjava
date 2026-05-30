package io.testkit.queue;

import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fluent DSL for queue-based assertions.
 *
 * <pre>{@code
 * // Kafka
 * .queue(q -> q
 *     .kafka("localhost:9092")
 *     .produce("orders", """{"orderId":"1","item":"Widget"}""")
 *     .assertMessageOnTopic("order-events", "\"status\":\"CREATED\"", Duration.ofSeconds(5)))
 *
 * // RabbitMQ
 * .queue(q -> q
 *     .rabbit("localhost")
 *     .publishToQueue("emails", """{"to":"user@example.com"}""")
 *     .assertMessageOnQueue("email-dlq", "failed", Duration.ofSeconds(3)))
 * }</pre>
 */
public final class QueueBuilder {

    private String kafkaBootstrapServers;
    private String rabbitHost;
    private final List<QueueOperation> operations = new ArrayList<>();

    // ── Kafka ─────────────────────────────────────────────────────────────────

    public QueueBuilder kafka(String bootstrapServers) {
        this.kafkaBootstrapServers = bootstrapServers;
        return this;
    }

    public QueueBuilder produce(String topic, String value) {
        operations.add(ctx -> kafkaClient().produce(topic, value));
        return this;
    }

    public QueueBuilder produce(String topic, String key, String value) {
        operations.add(ctx -> kafkaClient().produce(topic, key, value));
        return this;
    }

    public QueueBuilder assertMessageOnTopic(String topic, String bodyContains, Duration timeout) {
        operations.add(ctx -> kafkaClient().assertMessageOnTopic(topic, bodyContains, timeout));
        return this;
    }

    public QueueBuilder consumeToContext(String contextKey, String topic, Duration timeout) {
        operations.add(ctx -> {
            QueueMessage msg = kafkaClient().consumeOne(topic, timeout);
            ctx.put(contextKey, msg);
        });
        return this;
    }

    // ── RabbitMQ ──────────────────────────────────────────────────────────────

    public QueueBuilder rabbit(String host) {
        this.rabbitHost = host;
        return this;
    }

    public QueueBuilder publishToQueue(String queue, String message) {
        operations.add(ctx -> rabbitClient().publishToQueue(queue, message));
        return this;
    }

    public QueueBuilder publishToExchange(String exchange, String routingKey, String message) {
        operations.add(ctx -> rabbitClient().publish(exchange, routingKey, message));
        return this;
    }

    public QueueBuilder assertMessageOnQueue(String queue, String bodyContains, Duration timeout) {
        operations.add(ctx -> rabbitClient().assertMessageOnQueue(queue, bodyContains, timeout));
        return this;
    }

    public QueueBuilder purgeQueue(String queue) {
        operations.add(ctx -> rabbitClient().purgeQueue(queue));
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    void executeQueue(TestKitContext ctx) {
        for (QueueOperation op : operations) op.execute(ctx);
    }

    private KafkaTestClient kafkaClient() {
        if (kafkaBootstrapServers == null)
            throw new TestKitException("QueueBuilder: call .kafka(bootstrapServers) first");
        return new KafkaTestClient(kafkaBootstrapServers);
    }

    private RabbitTestClient rabbitClient() {
        if (rabbitHost == null)
            throw new TestKitException("QueueBuilder: call .rabbit(host) first");
        return new RabbitTestClient(rabbitHost);
    }

    @FunctionalInterface
    private interface QueueOperation {
        void execute(TestKitContext ctx);
    }
}
