package io.testkit.queue;

import com.rabbitmq.client.*;
import io.testkit.core.TestKitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Produces and consumes RabbitMQ messages in tests. */
public final class RabbitTestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitTestClient.class);

    private final ConnectionFactory factory;
    private Connection connection;
    private Channel channel;

    public RabbitTestClient(String host, int port, String username, String password) {
        factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
    }

    public RabbitTestClient(String host) {
        this(host, 5672, "guest", "guest");
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    public void publish(String exchange, String routingKey, String message) {
        try {
            getChannel().basicPublish(exchange, routingKey,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    message.getBytes(StandardCharsets.UTF_8));
            log.debug("Published to exchange={} routing={}", exchange, routingKey);
        } catch (IOException e) {
            throw new TestKitException("RabbitMQ publish failed: " + e.getMessage(), e);
        }
    }

    public void publishToQueue(String queue, String message) {
        publish("", queue, message);
    }

    // ── Consume ───────────────────────────────────────────────────────────────

    public List<QueueMessage> consumeQueue(String queue, int maxMessages, Duration timeout) {
        List<QueueMessage> messages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        try {
            Channel ch = getChannel();
            while (messages.size() < maxMessages && System.currentTimeMillis() < deadline) {
                GetResponse response = ch.basicGet(queue, true);
                if (response != null) {
                    String body = new String(response.getBody(), StandardCharsets.UTF_8);
                    messages.add(new QueueMessage(queue, null, body, Map.of(),
                            response.getMessageCount(), 0));
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new TestKitException("RabbitMQ consume failed: " + e.getMessage(), e);
        }
        return messages;
    }

    public QueueMessage consumeOne(String queue, Duration timeout) {
        List<QueueMessage> msgs = consumeQueue(queue, 1, timeout);
        if (msgs.isEmpty()) {
            throw new TestKitException("No message on queue '" + queue + "' within " + timeout);
        }
        return msgs.get(0);
    }

    // ── Queue management ──────────────────────────────────────────────────────

    public void declareQueue(String queue) {
        try {
            getChannel().queueDeclare(queue, true, false, false, null);
        } catch (IOException e) {
            throw new TestKitException("Queue declare failed: " + e.getMessage(), e);
        }
    }

    public void purgeQueue(String queue) {
        try {
            getChannel().queuePurge(queue);
        } catch (IOException e) {
            throw new TestKitException("Queue purge failed: " + e.getMessage(), e);
        }
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    public void assertMessageOnQueue(String queue, String bodyContains, Duration timeout) {
        QueueMessage msg = consumeOne(queue, timeout);
        if (!msg.value().contains(bodyContains)) {
            throw new TestKitException(
                    "Expected message on queue '" + queue + "' to contain '" + bodyContains +
                    "' but got: " + msg.value());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private synchronized Channel getChannel() {
        try {
            if (connection == null || !connection.isOpen()) {
                connection = factory.newConnection();
            }
            if (channel == null || !channel.isOpen()) {
                channel = connection.createChannel();
            }
            return channel;
        } catch (IOException | TimeoutException e) {
            throw new TestKitException("RabbitMQ connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
        } catch (IOException | TimeoutException e) {
            log.warn("Error closing RabbitMQ resources: {}", e.getMessage());
        }
    }
}
