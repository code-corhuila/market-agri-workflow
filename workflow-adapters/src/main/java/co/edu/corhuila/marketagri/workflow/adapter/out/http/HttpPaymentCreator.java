package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import static co.edu.corhuila.marketagri.workflow.adapter.out.http.ParticipantAnswers.text;

import co.edu.corhuila.marketagri.workflow.application.port.out.PaymentCreator;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;

/** Step 3 over HTTP: I-06 with the parties of the conversation and the reservation's snapshot. */
public final class HttpPaymentCreator implements PaymentCreator {

    private final ServiceCaller caller;
    private final URI transactionsApiUrl;
    private final ObjectMapper json;

    public HttpPaymentCreator(ServiceCaller caller, URI transactionsApiUrl, ObjectMapper json) {
        this.caller = caller;
        this.transactionsApiUrl = transactionsApiUrl;
        this.json = json;
    }

    @Override
    public StepCall<PaymentCheckout> create(String idempotencyKey, UUID transactionId, Conversation conversation,
                                            Reservation reservation) {
        String body = json.createObjectNode()
                .put("transactionId", transactionId.toString())
                .put("conversationId", conversation.id().toString())
                .put("buyerId", conversation.buyerId().toString())
                .put("buyerName", conversation.buyerName())
                .put("producerId", conversation.producerId().toString())
                .put("producerName", conversation.producerName())
                .put("productId", reservation.productId().toString())
                .put("productName", reservation.productName())
                .put("quantity", reservation.quantity())
                .put("unit", reservation.unit())
                .put("unitPriceCents", reservation.unitPriceCents())
                .toString();
        Answer answer = caller.call("POST", transactionsApiUrl.resolve("/internal/v1/transactions"),
                idempotencyKey, body);
        return ParticipantAnswers.toStepCall(answer, json, view -> new PaymentCheckout(transaction(view),
                URI.create(text(view, "checkoutUrl"))));
    }

    private static String transaction(JsonNode view) {
        JsonNode transaction = view.get("transaction");
        if (transaction == null || !transaction.isObject()) {
            throw new IllegalArgumentException("transaction is not an object");
        }
        return transaction.toString();
    }
}
