package co.edu.corhuila.marketagri.workflow.app;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The three participants and the saga store in memory, for the HTTP tests of E-40 ({@code @Primary}). */
final class InMemorySaga implements ConversationReader, StockReservations, PaymentCreator, CheckoutSagas {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, CheckoutSaga> stored = new ConcurrentHashMap<>();
    volatile StepCall<Conversation> conversationAnswer;
    volatile StepCall<Reservation> reservationAnswer;
    volatile StepCall<PaymentCheckout> paymentAnswer;

    void reset() {
        calls.clear();
        stored.clear();
    }

    @Override
    public StepCall<Conversation> read(UUID conversationId) {
        calls.add("I-05");
        return conversationAnswer;
    }

    @Override
    public StepCall<Reservation> reserve(String idempotencyKey, UUID productId, UUID transactionId, Quantity quantity) {
        calls.add("I-03 " + idempotencyKey);
        return reservationAnswer;
    }

    @Override
    public boolean release(UUID transactionId) {
        calls.add("I-04");
        return true;
    }

    @Override
    public StepCall<PaymentCheckout> create(String idempotencyKey, UUID transactionId, Conversation conversation,
                                            Reservation reservation) {
        calls.add("I-06 " + idempotencyKey);
        return paymentAnswer;
    }

    @Override
    public Optional<CheckoutSaga> findByKey(UUID buyerId, String idempotencyKey) {
        return Optional.ofNullable(stored.get(buyerId + "/" + idempotencyKey));
    }

    @Override
    public boolean create(CheckoutSaga saga) {
        return stored.putIfAbsent(saga.request().buyerId() + "/" + saga.request().idempotencyKey(), saga) == null;
    }

    @Override
    public void save(CheckoutSaga saga) {
        // the saga lives in the map already
    }
}
