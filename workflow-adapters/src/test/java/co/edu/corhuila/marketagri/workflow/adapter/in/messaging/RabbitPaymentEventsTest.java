package co.edu.corhuila.marketagri.workflow.adapter.in.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcCheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.usecase.CheckoutSteps;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RabbitPaymentEventsTest {
    private static final String CONFIRMED = "TransactionConfirmed";
    private final JdbcCheckoutSagas sagas = mock(JdbcCheckoutSagas.class);
    private final Channel channel = mock(Channel.class);
    private final RabbitPaymentEvents events = new RabbitPaymentEvents(new ConnectionFactory(), new ObjectMapper(),
            sagas, mock(CheckoutSteps.class));

    @Test
    void acknowledgesAfterTheStoreReturns() throws Exception {
        UUID event = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        events.receive(channel, delivery("""
                {"eventId":"%s","eventType":"TransactionConfirmed","version":1,"payload":{"transactionId":"%s"}}
                """.formatted(event, transaction)), CONFIRMED);
        var order = inOrder(sagas, channel);
        order.verify(sagas).applyPaymentEvent(eq(event), eq(CONFIRMED), eq(transaction), any());
        order.verify(channel).basicAck(1L, false);
    }

    @Test
    void rejectsAnInvalidEnvelopeWithoutRequeueing() throws Exception {
        events.receive(channel, delivery("{}"), CONFIRMED);
        verifyNoInteractions(sagas);
        verify(channel).basicReject(1L, false);
    }

    private static Delivery delivery(String body) {
        return new Delivery(new Envelope(1L, false, "market-agri.events", "transactions.transaction-confirmed"),
                new AMQP.BasicProperties.Builder().build(), body.getBytes(StandardCharsets.UTF_8));
    }
}
