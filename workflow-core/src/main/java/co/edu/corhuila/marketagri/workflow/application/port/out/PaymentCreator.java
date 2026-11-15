package co.edu.corhuila.marketagri.workflow.application.port.out;

import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import java.util.UUID;

/** Step 3: transactions' I-06, with the snapshot of the conversation and the reservation. */
public interface PaymentCreator {

    StepCall<PaymentCheckout> create(String idempotencyKey, UUID transactionId, Conversation conversation,
                                     Reservation reservation);
}
