package co.edu.corhuila.marketagri.workflow.application.port.out;

import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import java.util.UUID;

/** Step 2 and its compensation: catalog's I-03 and I-04. */
public interface StockReservations {

    StepCall<Reservation> reserve(String idempotencyKey, UUID productId, UUID transactionId, Quantity quantity);

    /** I-04, idempotent: true when catalog answered {@code 204}, false when it did not after the retries. */
    boolean release(UUID transactionId);
}
