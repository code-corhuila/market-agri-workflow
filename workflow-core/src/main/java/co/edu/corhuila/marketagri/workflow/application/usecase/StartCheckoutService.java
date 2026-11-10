package co.edu.corhuila.marketagri.workflow.application.usecase;

import co.edu.corhuila.marketagri.workflow.application.port.in.StartCheckout;
import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutFailure;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRejectedException;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRequest;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import java.time.Clock;

/**
 * E-40: a new key starts a saga and runs its steps; a known key runs nothing (ADR-015 §5) and
 * answers the open checkout ({@code 200}), the same error, or {@code CHECKOUT_IN_PROGRESS}.
 */
public final class StartCheckoutService implements StartCheckout {

    private final CheckoutSagas sagas;
    private final CheckoutSteps steps;
    private final Clock clock;

    public StartCheckoutService(CheckoutSagas sagas, CheckoutSteps steps, Clock clock) {
        this.sagas = sagas;
        this.steps = steps;
        this.clock = clock;
    }

    @Override
    public Checkout start(Command command) {
        var request = new CheckoutRequest(command.buyerId(), command.idempotencyKey(), command.conversationId(),
                new Quantity(command.quantity()));
        var earlier = sagas.findByKey(command.buyerId(), command.idempotencyKey());
        if (earlier.isPresent()) {
            return answer(earlier.get(), false);
        }
        CheckoutSaga saga = CheckoutSaga.start(request, clock.instant());
        if (!sagas.create(saga)) {
            return answer(sagas.findByKey(command.buyerId(), command.idempotencyKey()).orElseThrow(), false);
        }
        return answer(steps.runFrom(saga), true);
    }

    /** An open checkout wins over a later failure of its payment: the body is the original one. */
    private static Checkout answer(CheckoutSaga saga, boolean created) {
        if (saga.payment().isPresent()) {
            return new Checkout(saga, created);
        }
        CheckoutFailure failure = saga.failure().orElseGet(() -> new CheckoutFailure(
                CheckoutError.CHECKOUT_IN_PROGRESS, null,
                "saga " + saga.id() + " is still at " + saga.currentStep().wireName()));
        throw new CheckoutRejectedException(failure);
    }
}
