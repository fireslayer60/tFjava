package io.testkit.queue;

import io.testkit.core.TestKitException;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Produces and consumes Kafka messages in tests with assertion helpers. */
public final class KafkaTestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTestClient.class);

    private final String bootstrapServers;
    private KafkaProducer<String, String> producer;

    public KafkaTestClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    // ── Produce ───────────────────────────────────────────────────────────────

    public void produce(String topic, String value) {
        produce(topic, null, value, Map.of());
    }

    public void produce(String topic, String key, String value) {
        produce(topic, key, value, Map.of());
    }

    public void produce(String topic, String key, String value, Map<String, String> headers) {
        KafkaProducer<String, String> p = getOrCreateProducer();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((k, v) ->
                record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            p.send(record).get(10, TimeUnit.SECONDS);
            log.debug("Produced to topic={} key={}", topic, key);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new TestKitException("Failed to produce Kafka message: " + e.getMessage(), e);
        }
    }

    // ── Consume ───────────────────────────────────────────────────────────────

    /**
     * Consume up to {@code maxMessages} from {@code topic} within {@code timeout}.
     * Resets offsets to the beginning for deterministic test behaviour.
     */
    public List<QueueMessage> consume(String topic, int maxMessages, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "testkit-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<QueueMessage> messages = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (messages.size() < maxMessages && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(Math.min(500, deadline - System.currentTimeMillis())));
                for (ConsumerRecord<String, String> record : records) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (Header h : record.headers()) {
                        headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
                    }
                    messages.add(new QueueMessage(
                            record.topic(), record.key(), record.value(),
                            headers, record.offset(), record.partition()));
                    if (messages.size() >= maxMessages) break;
                }
            }
        }
        return messages;
    }

    /** Wait until at least one message appears on the topic within the timeout. */
    public QueueMessage consumeOne(String topic, Duration timeout) {
        List<QueueMessage> msgs = consume(topic, 1, timeout);
        if (msgs.isEmpty()) {
            throw new TestKitException(
                    "No message arrived on topic '" + topic + "' within " + timeout);
        }
        return msgs.get(0);
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    public void assertMessageOnTopic(String topic, String bodyContains, Duration timeout) {
        QueueMessage msg = consumeOne(topic, timeout);
        if (!msg.value().contains(bodyContains)) {
            throw new TestKitException(
                    "Expected message on topic '" + topic + "' to contain '" + bodyContains +
                    "' but got: " + msg.value());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private synchronized KafkaProducer<String, String> getOrCreateProducer() {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }
}
