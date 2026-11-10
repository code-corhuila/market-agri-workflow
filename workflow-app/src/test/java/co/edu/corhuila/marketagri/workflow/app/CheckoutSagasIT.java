package co.edu.corhuila.marketagri.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcCheckoutSagas;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutFailure;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRequest;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.PurchaseMethod;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStatus;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import co.edu.corhuila.marketagri.workflow.domain.model.StepRecord;
import co.edu.corhuila.marketagri.workflow.domain.model.StepStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The saga store against the real schema; each test rolls back (workflow_app has no DELETE), but one. */
@SpringBootTest
@Transactional
class CheckoutSagasIT extends WorkflowIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-09T15:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final Instant T2 = T0.plusSeconds(2);
    private static final String VIEW = "{\"id\":\"9d8e…\",\"status\":\"PENDING\",\"amountCents\":21250000}";

    @Autowired
    private JdbcCheckoutSagas sagas;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private final UUID buyer = UUID.randomUUID();
    private final CheckoutRequest request = new CheckoutRequest(buyer, "key-" + UUID.randomUUID(), UUID.randomUUID(),
            new Quantity(new BigDecimal("2.5")));

    @Test
    void aNewSagaIsStoredRunningAtReadConversationAndFoundByItsBuyerAndKey() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);

        assertThat(sagas.create(saga)).isTrue();

        CheckoutSaga found = sagas.findByKey(buyer, request.idempotencyKey()).orElseThrow();
        assertThat(found.id()).isEqualTo(saga.id());
        assertThat(found.transactionId()).isEqualTo(saga.transactionId());
        assertThat(found.request()).isEqualTo(request);
        assertThat(found.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(found.currentStep()).isEqualTo(SagaStep.READ_CONVERSATION);
        assertThat(found.failure()).isEmpty();
        assertThat(found.createdAt()).isEqualTo(T0);
        assertThat(sagas.findByKey(UUID.randomUUID(), request.idempotencyKey())).isEmpty();
        assertThat(sagas.findByKey(buyer, "another-key")).isEmpty();
    }

    @Test
    void aSecondSagaWithTheSameBuyerAndKeyIsNotStoredAndTheFirstOneStays() {
        CheckoutSaga first = CheckoutSaga.start(request, T0);
        sagas.create(first);

        assertThat(sagas.create(CheckoutSaga.start(request, T1))).isFalse();

        assertThat(sagas.findByKey(buyer, request.idempotencyKey())).get().extracting(CheckoutSaga::id)
                .isEqualTo(first.id());
    }

    @Test
    void anOpenCheckoutComesBackWithEveryStepAndEveryAnswer() throws Exception {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        sagas.save(saga);
        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        sagas.save(saga);
        saga.beginStep(SagaStep.RESERVE_STOCK, T1);
        saga.stockReserved(reservation(saga), T1);
        saga.beginStep(SagaStep.CREATE_PAYMENT, T2);
        saga.paymentCreated(new PaymentCheckout(VIEW, URI.create("https://checkout.wompi.co/p/?reference=r")), T2);
        sagas.save(saga);

        CheckoutSaga found = sagas.findByKey(buyer, request.idempotencyKey()).orElseThrow();

        assertThat(found.currentStep()).isEqualTo(SagaStep.AWAIT_PAYMENT);
        assertThat(found.updatedAt()).isEqualTo(T2);
        assertThat(found.step(SagaStep.READ_CONVERSATION)).contains(new StepRecord(StepStatus.DONE, 2, 0, T1, T1));
        assertThat(found.step(SagaStep.CREATE_PAYMENT)).contains(new StepRecord(StepStatus.DONE, 1, 0, T2, T2));
        assertThat(found.conversation()).isEqualTo(conversation());
        assertThat(found.reservation()).isEqualTo(reservation(saga));
        PaymentCheckout payment = found.payment().orElseThrow();
        assertThat(payment.checkoutUrl()).isEqualTo(URI.create("https://checkout.wompi.co/p/?reference=r"));
        assertThat(json.readTree(payment.transactionView())).isEqualTo(json.readTree(VIEW));
        assertThat(jdbc.sql("SELECT jsonb_typeof(result -> 'transaction') FROM saga_step WHERE saga_instance_id = :id"
                + " AND step = 'create-payment'").param("id", saga.id()).query(String.class).single())
                .isEqualTo("object");
    }

    @Test
    void aCompensatedSagaKeepsItsFailureForTheReplayAndTheReservationItGaveBack() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        saga.beginStep(SagaStep.RESERVE_STOCK, T1);
        saga.stockReserved(reservation(saga), T1);
        sagas.save(saga);
        saga.beginStep(SagaStep.CREATE_PAYMENT, T2);
        saga.fail(SagaStep.CREATE_PAYMENT, new CheckoutFailure(CheckoutError.PAYMENT_GATEWAY_ERROR, null,
                "I-06 answered 502 PAYMENT_GATEWAY_ERROR"), false, T2);
        saga.stockReleaseFailed(T2);
        saga.stockReleased(T2);
        sagas.save(saga);

        CheckoutSaga found = sagas.findByKey(buyer, request.idempotencyKey()).orElseThrow();

        assertThat(found.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(found.failedStep()).contains(SagaStep.CREATE_PAYMENT);
        assertThat(found.failure()).contains(new CheckoutFailure(CheckoutError.PAYMENT_GATEWAY_ERROR, null,
                "I-06 answered 502 PAYMENT_GATEWAY_ERROR"));
        assertThat(found.payment()).isEmpty();
        assertThat(found.step(SagaStep.CREATE_PAYMENT)).get().extracting(StepRecord::status)
                .isEqualTo(StepStatus.REFUSED);
        assertThat(found.step(SagaStep.RESERVE_STOCK)).contains(new StepRecord(StepStatus.COMPENSATED, 1, 1, T1, T2));
        assertThat(found.reservation()).isEqualTo(reservation(saga));
    }

    @Test
    void aMessageWithValuesIsStoredSoTheReplayRepeatsIt() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        saga.beginStep(SagaStep.RESERVE_STOCK, T1);
        saga.fail(SagaStep.RESERVE_STOCK, new CheckoutFailure(CheckoutError.INSUFFICIENT_STOCK,
                "Cantidad insuficiente: hay 2.00 disponibles y se solicitaron 2.50.", "I-03 answered 409"), false, T1);
        sagas.save(saga);

        assertThat(sagas.findByKey(buyer, request.idempotencyKey()).orElseThrow().failure().orElseThrow().message())
                .isEqualTo("Cantidad insuficiente: hay 2.00 disponibles y se solicitaron 2.50.");
    }

    /**
     * Out of the test's transaction on purpose: after the failed statement PostgreSQL aborts the
     * transaction, so the check has to read from a new one. It leaves one row with a random key.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aSaveIsAtomicAFailedStepLeavesTheSagaAsItWas() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        CheckoutSaga broken = CheckoutSaga.restore(saga.id(), saga.transactionId(), request, SagaStatus.COMPENSATED,
                SagaStep.READ_CONVERSATION, SagaStep.READ_CONVERSATION,
                new CheckoutFailure(CheckoutError.CHAT_UNAVAILABLE, null, "I-05 did not answer"),
                Map.of(SagaStep.READ_CONVERSATION, new StepRecord(StepStatus.REFUSED, -1, 0, T1, T1)),
                null, null, null, T0, T1);

        assertThatThrownBy(() -> sagas.save(broken)).isInstanceOf(DataIntegrityViolationException.class);

        CheckoutSaga stored = sagas.findByKey(buyer, request.idempotencyKey()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(stored.failure()).isEmpty();
        assertThat(stored.updatedAt()).isEqualTo(T0);
    }

    @Test
    void recoverySelectsStaleWorkAndSkipsSagasAwaitingPayment() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        assertThat(sagas.recoverable(T1)).contains(saga.id());
        assertThat(sagas.recoverable(T0)).doesNotContain(saga.id());

        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        saga.beginStep(SagaStep.RESERVE_STOCK, T1);
        saga.stockReserved(reservation(saga), T1);
        saga.beginStep(SagaStep.CREATE_PAYMENT, T2);
        saga.paymentCreated(new PaymentCheckout(VIEW, URI.create("https://checkout.wompi.co/p/")), T2);
        sagas.save(saga);
        assertThat(sagas.recoverable(T2.plusSeconds(60))).doesNotContain(saga.id());
    }

    @Test
    void paymentEventsDeduplicateByEventId() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        saga.beginStep(SagaStep.READ_CONVERSATION, T1);
        saga.conversationRead(conversation(), T1);
        saga.beginStep(SagaStep.RESERVE_STOCK, T1);
        saga.stockReserved(reservation(saga), T1);
        saga.beginStep(SagaStep.CREATE_PAYMENT, T2);
        saga.paymentCreated(new PaymentCheckout(VIEW, URI.create("https://checkout.wompi.co/p/")), T2);
        sagas.save(saga);

        UUID event = UUID.randomUUID();
        java.util.function.Consumer<CheckoutSaga> confirm = current -> {
            current.paymentFinished(true, T2);
            sagas.save(current);
        };
        sagas.applyPaymentEvent(event, "TransactionConfirmed", saga.transactionId(), confirm);
        sagas.applyPaymentEvent(event, "TransactionConfirmed", saga.transactionId(), ignored -> {
            throw new AssertionError("Redelivery applied a second time");
        });
        assertThat(sagas.findById(saga.id()).orElseThrow().status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anUncommittedEventCanBeRetriedAndAnUnknownTransactionIsAcknowledged() {
        CheckoutSaga saga = CheckoutSaga.start(request, T0);
        sagas.create(saga);
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> sagas.applyPaymentEvent(event, "TransactionFailed", saga.transactionId(), current -> {
            current.beginStep(SagaStep.READ_CONVERSATION, T1);
            sagas.save(current);
            throw new IllegalStateException("abort");
        })).hasMessage("abort");
        assertThat(sagas.findById(saga.id()).orElseThrow().step(SagaStep.READ_CONVERSATION)).isEmpty();
        sagas.applyPaymentEvent(event, "TransactionFailed", saga.transactionId(), current -> {
            current.beginStep(SagaStep.READ_CONVERSATION, T2);
            sagas.save(current);
        });
        assertThat(sagas.findById(saga.id()).orElseThrow().updatedAt()).isEqualTo(T2);

        UUID unknown = UUID.randomUUID();
        sagas.applyPaymentEvent(unknown, "TransactionConfirmed", UUID.randomUUID(), ignored -> {
            throw new AssertionError("Unknown transaction was applied");
        });
        assertThat(jdbc.sql("SELECT event_id FROM processed_event WHERE event_id = :id AND saga_instance_id IS NULL")
                .param("id", unknown).query(UUID.class).optional()).contains(unknown);
    }

    private Conversation conversation() {
        return new Conversation(request.conversationId(), UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "Café", buyer, "Ana", UUID.fromString("00000000-0000-4000-8000-000000000002"), "Luis",
                PurchaseMethod.PLATFORM);
    }

    private Reservation reservation(CheckoutSaga saga) {
        return new Reservation(saga.transactionId(), UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "Café", UUID.fromString("00000000-0000-4000-8000-000000000002"), new BigDecimal("2.50"), "KILOGRAMO",
                8_500_000L);
    }
}
