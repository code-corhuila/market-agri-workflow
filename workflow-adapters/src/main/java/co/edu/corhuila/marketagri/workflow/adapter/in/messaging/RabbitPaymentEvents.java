package co.edu.corhuila.marketagri.workflow.adapter.in.messaging;

import co.edu.corhuila.marketagri.workflow.adapter.correlation.Correlation;
import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcCheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import co.edu.corhuila.marketagri.workflow.application.usecase.CheckoutSteps;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.CannotCreateTransactionException;

/** ADR-014: infra owns topology. Acknowledge only after the database transaction commits. */
public final class RabbitPaymentEvents implements ReadinessCheck, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RabbitPaymentEvents.class);
    private static final int MAX_ATTEMPTS = 5;

    private final ConnectionFactory factory;
    private final ObjectMapper json;
    private final JdbcCheckoutSagas sagas;
    private final CheckoutSteps steps;
    private Connection connection;
    private Channel channel;

    public RabbitPaymentEvents(ConnectionFactory factory, ObjectMapper json, JdbcCheckoutSagas sagas,
                               CheckoutSteps steps) {
        this.factory = factory;
        this.json = json;
        this.sagas = sagas;
        this.steps = steps;
    }

    @Scheduled(fixedDelayString = "PT5S")
    public synchronized void connect() {
        if (isUp()) {
            return;
        }
        close();
        try {
            connection = factory.newConnection("workflow payment events");
            Channel consumer = connection.createChannel();
            consumer.basicQos(1);
            subscribe(consumer, "workflow.transaction-confirmed", "TransactionConfirmed");
            subscribe(consumer, "workflow.transaction-failed", "TransactionFailed");
            channel = consumer;
        } catch (IOException | TimeoutException | RuntimeException error) {
            close();
            LOG.warn("Could not subscribe to payment events: {}", error.toString());
        }
    }

    private void subscribe(Channel consumer, String queue, String type) throws IOException {
        consumer.basicConsume(queue, false, (tag, delivery) -> receive(consumer, delivery, type),
                tag -> consumer.abort());
    }

    void receive(Channel consumer, Delivery delivery, String type) throws IOException {
        long tag = delivery.getEnvelope().getDeliveryTag();
        try {
            var event = json.readTree(delivery.getBody());
            if (!type.equals(event.path("eventType").asText()) || event.path("version").asInt(-1) != 1) {
                throw new IllegalArgumentException("Unexpected payment event type or version");
            }
            UUID id = UUID.fromString(event.path("eventId").asText());
            UUID transaction = UUID.fromString(event.path("payload").path("transactionId").asText());
            String correlation = event.path("metadata").path("correlationId").asText(id.toString());
            try (var ignored = MDC.putCloseable(Correlation.MDC_KEY, correlation)) {
                apply(id, type, transaction);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            consumer.abort();
            return;
        } catch (IOException | RuntimeException error) {
            LOG.error("Rejecting payment event {}", delivery.getProperties().getMessageId(), error);
            consumer.basicReject(tag, false);
            return;
        }
        consumer.basicAck(tag, false);
    }

    private void apply(UUID id, String type, UUID transaction) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                sagas.applyPaymentEvent(id, type, transaction,
                        saga -> steps.finishPayment(saga, "TransactionConfirmed".equals(type)));
                return;
            } catch (TransientDataAccessException | DataAccessResourceFailureException
                     | CannotCreateTransactionException | CheckoutSteps.PaymentNotRecovered error) {
                if (attempt + 1 == MAX_ATTEMPTS) {
                    throw error;
                }
                Thread.sleep(ThreadLocalRandom.current().nextLong((1000L << attempt) + 1));
            }
        }
    }

    @Override
    public String name() {
        return "broker";
    }

    @Override
    public synchronized boolean isUp() {
        return channel != null && channel.isOpen();
    }

    @Override
    public synchronized void close() {
        channel = null;
        if (connection != null) {
            connection.abort();
            connection = null;
        }
    }
}
