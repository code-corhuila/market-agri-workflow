package co.edu.corhuila.marketagri.workflow.application.port.out;

import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import java.util.Optional;
import java.util.UUID;

/** {@code saga_instance} and {@code saga_step}: each change is stored before the next call (§5.8). */
public interface CheckoutSagas {

    Optional<CheckoutSaga> findByKey(UUID buyerId, String idempotencyKey);

    /** Stores a new saga; false when a concurrent request with the same buyer and key stored one first. */
    boolean create(CheckoutSaga saga);

    void save(CheckoutSaga saga);
}
