package co.edu.corhuila.marketagri.workflow.domain.model;

import static co.edu.corhuila.marketagri.workflow.domain.model.SagaStep.AWAIT_PAYMENT;
import static co.edu.corhuila.marketagri.workflow.domain.model.SagaStep.CREATE_PAYMENT;
import static co.edu.corhuila.marketagri.workflow.domain.model.SagaStep.READ_CONVERSATION;
import static co.edu.corhuila.marketagri.workflow.domain.model.SagaStep.RESERVE_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutSagaTest {

    private static final Instant T0 = Instant.parse("2026-11-09T15:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final CheckoutFailure WHY = new CheckoutFailure(CheckoutError.CATALOG_UNAVAILABLE, null, "I-03 …");

    private final UUID buyer = UUID.randomUUID();
    private final CheckoutRequest request = new CheckoutRequest(buyer, "key-12345", UUID.randomUUID(),
            new Quantity(new BigDecimal("2.5")));
    private final CheckoutSaga saga = CheckoutSaga.start(request, T0);

    @Test
    void startsRunningAtReadConversationWithItsOwnIdAndTheTransactionIdFixed() {
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.currentStep()).isEqualTo(READ_CONVERSATION);
        assertThat(saga.id()).isNotNull().isNotEqualTo(saga.transactionId());
        assertThat(saga.request()).isEqualTo(request);
        assertThat(saga.failedStep()).isEmpty();
        assertThat(saga.failure()).isEmpty();
        assertThat(saga.payment()).isEmpty();
        assertThat(saga.createdAt()).isEqualTo(T0);
        assertThat(CheckoutSaga.start(request, T0).transactionId()).isNotEqualTo(saga.transactionId());
    }

    @Test
    void theKeyOfAStepIsSagaIdColonStepName() {
        assertThat(saga.idempotencyKeyFor(RESERVE_STOCK)).isEqualTo(saga.id() + ":reserve-stock");
        assertThat(saga.idempotencyKeyFor(CREATE_PAYMENT)).isEqualTo(saga.id() + ":create-payment");
    }

    @Test
    void stepsRunInOrderAndEachAnswerIsKeptUntilAwaitPayment() {
        openCheckout();

        assertThat(saga.currentStep()).isEqualTo(AWAIT_PAYMENT);
        assertThat(saga.hasStepToRun()).isFalse();
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.step(READ_CONVERSATION)).get().extracting(StepRecord::status).isEqualTo(StepStatus.DONE);
        assertThat(saga.step(RESERVE_STOCK)).get().extracting(StepRecord::status).isEqualTo(StepStatus.DONE);
        assertThat(saga.step(CREATE_PAYMENT)).get().extracting(StepRecord::status).isEqualTo(StepStatus.DONE);
        assertThat(saga.conversation().buyerId()).isEqualTo(buyer);
        assertThat(saga.reservation().unitPriceCents()).isEqualTo(8_500_000L);
        assertThat(saga.payment()).get().extracting(PaymentCheckout::checkoutUrl)
                .isEqualTo(URI.create("https://checkout.wompi.co/p/?reference=x"));
        assertThat(saga.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aStepCannotRunOutOfOrderNorTwiceNorFinishWithoutStarting() {
        assertThatThrownBy(() -> saga.beginStep(RESERVE_STOCK, T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.conversationRead(conversation(), T1)).isInstanceOf(IllegalStateException.class);
        saga.beginStep(READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        assertThatThrownBy(() -> saga.beginStep(READ_CONVERSATION, T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aResumedStepCountsOneMoreAttemptAndKeepsWhenItFirstStarted() {
        saga.beginStep(READ_CONVERSATION, T0);
        saga.beginStep(READ_CONVERSATION, T1);

        StepRecord read = saga.step(READ_CONVERSATION).orElseThrow();
        assertThat(read.attempts()).isEqualTo(2);
        assertThat(read.status()).isEqualTo(StepStatus.STARTED);
        assertThat(read.startedAt()).isEqualTo(T0);
        assertThat(read.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aRefusedReadOrReservationHoldsNoStockSoTheSagaEndsCompensatedAtOnce() {
        saga.beginStep(READ_CONVERSATION, T1);
        saga.fail(READ_CONVERSATION, WHY, true, T1);
        assertCompensatedWithoutRelease(READ_CONVERSATION);

        CheckoutSaga refusedReservation = readConversation(CheckoutSaga.start(request, T0));
        refusedReservation.beginStep(RESERVE_STOCK, T1);
        refusedReservation.fail(RESERVE_STOCK, WHY, false, T1);
        assertThat(refusedReservation.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(refusedReservation.mustReleaseStock()).isFalse();
    }

    @Test
    void anUnansweredReservationIsPossiblyDoneSoItsStockMustBeReleased() {
        readConversation(saga);
        saga.beginStep(RESERVE_STOCK, T1);
        saga.fail(RESERVE_STOCK, WHY, true, T1);

        assertCompensating(RESERVE_STOCK);
        saga.stockReleased(T1);
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.step(RESERVE_STOCK)).get().extracting(StepRecord::status).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void aFailedPaymentAlwaysReleasesTheReservationEvenWhenRefused() {
        readConversation(saga);
        reserve(saga);
        saga.beginStep(CREATE_PAYMENT, T1);
        saga.fail(CREATE_PAYMENT, WHY, false, T1);

        assertCompensating(CREATE_PAYMENT);
        assertThat(saga.step(CREATE_PAYMENT)).get().extracting(StepRecord::status).isEqualTo(StepStatus.REFUSED);
        saga.stockReleased(T1);
        assertThat(saga.step(RESERVE_STOCK)).get().extracting(StepRecord::status).isEqualTo(StepStatus.COMPENSATED);
        assertThat(saga.failedStep()).contains(CREATE_PAYMENT);
    }

    @Test
    void aFailedReleaseKeepsTheSagaCompensatingUntilTheTenthAttemptMakesItFailed() {
        readConversation(saga);
        reserve(saga);
        saga.beginStep(CREATE_PAYMENT, T1);
        saga.fail(CREATE_PAYMENT, WHY, true, T1);

        for (int attempt = 1; attempt < CheckoutSaga.MAX_RELEASE_ATTEMPTS; attempt++) {
            saga.stockReleaseFailed(T1);
            assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(saga.step(RESERVE_STOCK).orElseThrow().compensationAttempts()).isEqualTo(attempt);
        }
        saga.stockReleaseFailed(T1);

        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.mustReleaseStock()).isFalse();
        assertThat(saga.failedStep()).contains(CREATE_PAYMENT);
        assertThatThrownBy(() -> saga.stockReleased(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nothingRunsWhileCompensatingAndNothingIsReleasedWhenNothingIsHeld() {
        assertThatThrownBy(() -> saga.stockReleased(T1)).isInstanceOf(IllegalStateException.class);
        readConversation(saga);
        saga.beginStep(RESERVE_STOCK, T1);
        saga.fail(RESERVE_STOCK, WHY, true, T1);
        assertThat(saga.hasStepToRun()).isFalse();
        assertThatThrownBy(() -> saga.beginStep(RESERVE_STOCK, T1)).isInstanceOf(IllegalStateException.class);
    }

    private void assertCompensatedWithoutRelease(SagaStep failed) {
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.mustReleaseStock()).isFalse();
        assertThat(saga.hasStepToRun()).isFalse();
        assertThat(saga.failedStep()).contains(failed);
        assertThat(saga.failure()).contains(WHY);
        assertThat(saga.step(failed)).get().extracting(StepRecord::status).isEqualTo(StepStatus.REFUSED);
    }

    private void assertCompensating(SagaStep failed) {
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.mustReleaseStock()).isTrue();
        assertThat(saga.hasStepToRun()).isFalse();
        assertThat(saga.failedStep()).contains(failed);
    }

    private void openCheckout() {
        readConversation(saga);
        reserve(saga);
        saga.beginStep(CREATE_PAYMENT, T1);
        saga.paymentCreated(new PaymentCheckout("{}", URI.create("https://checkout.wompi.co/p/?reference=x")), T1);
    }

    private CheckoutSaga readConversation(CheckoutSaga target) {
        target.beginStep(READ_CONVERSATION, T1);
        target.conversationRead(conversation(), T1);
        return target;
    }

    private void reserve(CheckoutSaga target) {
        target.beginStep(RESERVE_STOCK, T1);
        target.stockReserved(new Reservation(target.transactionId(), UUID.randomUUID(), "Café", UUID.randomUUID(),
                new BigDecimal("2.50"), "KILOGRAMO", 8_500_000L), T1);
    }

    private Conversation conversation() {
        return new Conversation(request.conversationId(), UUID.randomUUID(), "Café", buyer, "Ana",
                UUID.randomUUID(), "Luis", PurchaseMethod.PLATFORM);
    }
}
