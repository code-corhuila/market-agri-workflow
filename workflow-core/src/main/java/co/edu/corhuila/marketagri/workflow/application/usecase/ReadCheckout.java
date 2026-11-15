package co.edu.corhuila.marketagri.workflow.application.usecase;

import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.domain.model.DomainException;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStatus;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import co.edu.corhuila.marketagri.workflow.domain.model.StepStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record ReadCheckout(CheckoutSagas sagas) {

    public View read(UUID id, UUID buyer, boolean admin) {
        var saga = sagas.findById(id).filter(found -> admin || found.request().buyerId().equals(buyer))
                .orElseThrow(() -> new DomainException("CHECKOUT_NOT_FOUND", "Checkout " + id + " is not visible"));
        List<String> completed = Arrays.stream(SagaStep.values()).filter(step -> saga.step(step)
                .filter(record -> record.status() == StepStatus.DONE
                        || record.status() == StepStatus.COMPENSATED && saga.reservation() != null)
                .isPresent()).map(SagaStep::wireName).toList();
        return new View(saga.id(), saga.transactionId(), saga.status(), completed,
                saga.failedStep().map(SagaStep::wireName).orElse(null), saga.createdAt(), saga.updatedAt());
    }

    public record View(UUID id, UUID transactionId, SagaStatus status, List<String> completedSteps,
                       String failedStep, Instant createdAt, Instant updatedAt) {
    }
}
