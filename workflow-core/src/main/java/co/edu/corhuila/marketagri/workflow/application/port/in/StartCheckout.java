package co.edu.corhuila.marketagri.workflow.application.port.in;

import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import java.math.BigDecimal;
import java.util.UUID;

/** The buyer starts a purchase (E-40, HU-TXN-001). Idempotent on the buyer and the key (D-C35). */
public interface StartCheckout {

    /** The opened checkout, or a {@code CheckoutRejectedException} with the row of the card. */
    Checkout start(Command command);

    /** {@code buyerId} is the caller's {@code sub}. */
    record Command(UUID buyerId, String idempotencyKey, UUID conversationId, BigDecimal quantity) {
    }

    /** {@code created} is false on a replay: the HTTP adapter answers 200 instead of 201. */
    record Checkout(CheckoutSaga saga, boolean created) {
    }
}
