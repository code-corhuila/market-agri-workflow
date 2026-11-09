package co.edu.corhuila.marketagri.workflow.application.usecase;

import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.port.out.ConversationReader;
import co.edu.corhuila.marketagri.workflow.application.port.out.PaymentCreator;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.application.port.out.StockReservations;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The three participants and the saga store in memory. Every call and every save goes to one
 * {@link #log}, so a test can check that each answer was stored before the next call (§5.8).
 */
final class FakeSagaWorld implements ConversationReader, StockReservations, PaymentCreator, CheckoutSagas {

    final List<String> log = new ArrayList<>();
    final Map<String, CheckoutSaga> stored = new HashMap<>();
    StepCall<Conversation> conversationAnswer;
    StepCall<Reservation> reservationAnswer;
    StepCall<PaymentCheckout> paymentAnswer;
    boolean releaseAnswer = true;
    CheckoutSaga raceWinner;
    UUID reservedTransactionId;
    Quantity reservedQuantity;
    UUID paidTransactionId;
    Reservation paidReservation;

    @Override
    public StepCall<Conversation> read(UUID conversationId) {
        log.add("I-05 " + conversationId);
        return conversationAnswer;
    }

    @Override
    public StepCall<Reservation> reserve(String idempotencyKey, UUID productId, UUID transactionId, Quantity quantity) {
        log.add("I-03 " + idempotencyKey + " " + productId);
        reservedTransactionId = transactionId;
        reservedQuantity = quantity;
        return reservationAnswer;
    }

    @Override
    public boolean release(UUID transactionId) {
        log.add("I-04 " + transactionId);
        return releaseAnswer;
    }

    @Override
    public StepCall<PaymentCheckout> create(String idempotencyKey, UUID transactionId, Conversation conversation,
                                            Reservation reservation) {
        log.add("I-06 " + idempotencyKey);
        paidTransactionId = transactionId;
        paidReservation = reservation;
        return paymentAnswer;
    }

    @Override
    public Optional<CheckoutSaga> findByKey(UUID buyerId, String idempotencyKey) {
        return Optional.ofNullable(stored.get(buyerId + "/" + idempotencyKey));
    }

    @Override
    public boolean create(CheckoutSaga saga) {
        if (raceWinner != null) {
            stored.put(key(raceWinner), raceWinner);
            return false;
        }
        log.add("create");
        stored.put(key(saga), saga);
        return true;
    }

    @Override
    public void save(CheckoutSaga saga) {
        log.add("save " + steps(saga) + (saga.mustReleaseStock() ? " (release)" : "") + " " + saga.status());
    }

    List<String> calls() {
        return log.stream().filter(line -> line.startsWith("I-")).toList();
    }

    private static String key(CheckoutSaga saga) {
        return saga.request().buyerId() + "/" + saga.request().idempotencyKey();
    }

    static String steps(CheckoutSaga saga) {
        return Arrays.stream(SagaStep.values())
                .map(step -> saga.step(step).map(r -> step.wireName() + "=" + r.status()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
    }
}
