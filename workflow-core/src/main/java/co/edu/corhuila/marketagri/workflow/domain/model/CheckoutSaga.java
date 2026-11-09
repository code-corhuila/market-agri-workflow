package co.edu.corhuila.marketagri.workflow.domain.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout saga (ADR-015): its step, each step's answer and whether the stock must be given
 * back. While compensating it stays {@code RUNNING} with a {@code failedStep}; after
 * {@value #MAX_RELEASE_ATTEMPTS} failed releases it is {@code FAILED} and a person decides (Annex E).
 */
public final class CheckoutSaga {

    static final int MAX_RELEASE_ATTEMPTS = 10;

    private final UUID id;
    private final UUID transactionId;
    private final CheckoutRequest request;
    private final Instant createdAt;
    private final Map<SagaStep, StepRecord> steps = new EnumMap<>(SagaStep.class);
    private SagaStatus status = SagaStatus.RUNNING;
    private SagaStep currentStep = SagaStep.READ_CONVERSATION;
    private SagaStep failedStep;
    private CheckoutFailure failure;
    private Conversation conversation;
    private Reservation reservation;
    private PaymentCheckout payment;
    private Instant updatedAt;

    private CheckoutSaga(UUID id, UUID transactionId, CheckoutRequest request, Instant now) {
        this.id = id;
        this.transactionId = transactionId;
        this.request = request;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new saga: its own id and the transaction id, fixed now for every retry of steps 2 and 3. */
    public static CheckoutSaga start(CheckoutRequest request, Instant now) {
        return new CheckoutSaga(UUID.randomUUID(), UUID.randomUUID(), request, now);
    }

    /**
     * A saga as it was stored ({@code saga_instance} and its {@code saga_step} rows), for the
     * repository only: every rule applies again from the next transition on.
     */
    public static CheckoutSaga restore(UUID id, UUID transactionId, CheckoutRequest request, SagaStatus status,
                                       SagaStep currentStep, SagaStep failedStep, CheckoutFailure failure,
                                       Map<SagaStep, StepRecord> steps, Conversation conversation,
                                       Reservation reservation, PaymentCheckout payment, Instant createdAt,
                                       Instant updatedAt) {
        CheckoutSaga saga = new CheckoutSaga(id, transactionId, request, createdAt);
        saga.status = status;
        saga.currentStep = currentStep;
        saga.failedStep = failedStep;
        saga.failure = failure;
        saga.steps.putAll(steps);
        saga.conversation = conversation;
        saga.reservation = reservation;
        saga.payment = payment;
        saga.updatedAt = updatedAt;
        return saga;
    }

    /** {@code <sagaId>:<step>}: the key a participant receives for this step (norm §5.8). */
    public String idempotencyKeyFor(SagaStep step) {
        return id + ":" + step.wireName();
    }

    /** True while steps 1–3 still have to run: the saga is neither waiting, compensating nor ended. */
    public boolean hasStepToRun() {
        return status == SagaStatus.RUNNING && failure == null && currentStep != SagaStep.AWAIT_PAYMENT;
    }

    /** The current step is about to be called (again, when the saga is resumed). */
    public void beginStep(SagaStep step, Instant now) {
        requireCurrent(step);
        StepRecord earlier = steps.get(step);
        steps.put(step, earlier == null ? StepRecord.started(now) : earlier.restarted(now));
        updatedAt = now;
    }

    public void conversationRead(Conversation answer, Instant now) {
        complete(SagaStep.READ_CONVERSATION, now);
        conversation = answer;
    }

    public void stockReserved(Reservation answer, Instant now) {
        complete(SagaStep.RESERVE_STOCK, now);
        reservation = answer;
    }

    /** Step 3 answered: the checkout is open and the saga waits for the payment's event. */
    public void paymentCreated(PaymentCheckout answer, Instant now) {
        complete(SagaStep.CREATE_PAYMENT, now);
        payment = answer;
    }

    /**
     * The step was refused, or not answered after the retries ({@code possiblyDone}: treated as
     * done, ADR-015 §4). The stock may be held when the payment failed after the reservation, or
     * when the reservation itself got no answer; otherwise there is nothing to undo.
     */
    public void fail(SagaStep step, CheckoutFailure why, boolean possiblyDone, Instant now) {
        requireStarted(step);
        steps.put(step, steps.get(step).with(StepStatus.REFUSED, now));
        failedStep = step;
        failure = why;
        boolean stockMayBeHeld = step == SagaStep.CREATE_PAYMENT
                || step == SagaStep.RESERVE_STOCK && possiblyDone;
        if (!stockMayBeHeld) {
            status = SagaStatus.COMPENSATED;
        }
        updatedAt = now;
    }

    /** True while the reservation may be held after a failure: I-04 must be called. */
    public boolean mustReleaseStock() {
        return status == SagaStatus.RUNNING && failure != null;
    }

    public void stockReleased(Instant now) {
        requireReleasePending();
        steps.put(SagaStep.RESERVE_STOCK, steps.get(SagaStep.RESERVE_STOCK).with(StepStatus.COMPENSATED, now));
        status = SagaStatus.COMPENSATED;
        updatedAt = now;
    }

    public void stockReleaseFailed(Instant now) {
        requireReleasePending();
        StepRecord reserve = steps.get(SagaStep.RESERVE_STOCK).withCompensationAttempt(now);
        steps.put(SagaStep.RESERVE_STOCK, reserve);
        if (reserve.compensationAttempts() >= MAX_RELEASE_ATTEMPTS) {
            status = SagaStatus.FAILED;
        }
        updatedAt = now;
    }

    private void complete(SagaStep step, Instant now) {
        requireStarted(step);
        steps.put(step, steps.get(step).with(StepStatus.DONE, now));
        currentStep = step.next();
        updatedAt = now;
    }

    private void requireCurrent(SagaStep step) {
        if (!hasStepToRun() || step != currentStep) {
            throw new IllegalStateException("saga " + id + " (" + status + " at " + currentStep.wireName()
                    + ") cannot run " + step.wireName());
        }
    }

    private void requireStarted(SagaStep step) {
        requireCurrent(step);
        if (steps.get(step) == null || steps.get(step).status() != StepStatus.STARTED) {
            throw new IllegalStateException("saga " + id + ": " + step.wireName() + " was not started");
        }
    }

    private void requireReleasePending() {
        if (!mustReleaseStock()) {
            throw new IllegalStateException("saga " + id + " has no reservation to release");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public CheckoutRequest request() {
        return request;
    }

    public SagaStatus status() {
        return status;
    }

    public SagaStep currentStep() {
        return currentStep;
    }

    public Optional<SagaStep> failedStep() {
        return Optional.ofNullable(failedStep);
    }

    public Optional<CheckoutFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public Optional<StepRecord> step(SagaStep step) {
        return Optional.ofNullable(steps.get(step));
    }

    public Conversation conversation() {
        return conversation;
    }

    public Reservation reservation() {
        return reservation;
    }

    public Optional<PaymentCheckout> payment() {
        return Optional.ofNullable(payment);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
