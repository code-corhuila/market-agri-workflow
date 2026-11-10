package co.edu.corhuila.marketagri.workflow.application.port.out;

import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** {@code saga_instance} and {@code saga_step}: each change is stored before the next call (§5.8). */
public interface CheckoutSagas {

    Optional<CheckoutSaga> findByKey(UUID buyerId, String idempotencyKey);

    Optional<CheckoutSaga> findById(UUID id);

    /** Run one step against the latest state, serialized with recovery and payment events. */
    CheckoutSaga runLocked(UUID id, Consumer<CheckoutSaga> action);

    /** Stores a new saga; false when a concurrent request with the same buyer and key stored one first. */
    boolean create(CheckoutSaga saga);

    void save(CheckoutSaga saga);
}
