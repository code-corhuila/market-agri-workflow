package co.edu.corhuila.marketagri.workflow.application.usecase;

import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.port.out.ConversationReader;
import co.edu.corhuila.marketagri.workflow.application.port.out.PaymentCreator;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.application.port.out.StockReservations;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutFailure;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import java.time.Clock;

/**
 * Orchestrator of steps 1–3 (ADR-015, E-40): calls each participant, stores the answer before the
 * next call, maps a refusal to its row of the card and gives the stock back when it may be held.
 * It starts from the saga's stored step, so the recovery sweep can resume a saga with it.
 */
public final class CheckoutSteps {

    private static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";
    private static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    private static final String PRODUCT_NOT_AVAILABLE = "PRODUCT_NOT_AVAILABLE";
    private static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    private static final String TRANSACTION_IN_PROGRESS = "TRANSACTION_IN_PROGRESS";
    private static final String PAYMENT_GATEWAY_ERROR = "PAYMENT_GATEWAY_ERROR";

    private final ConversationReader conversations;
    private final StockReservations reservations;
    private final PaymentCreator payments;
    private final CheckoutSagas sagas;
    private final Clock clock;

    public CheckoutSteps(ConversationReader conversations, StockReservations reservations, PaymentCreator payments,
                         CheckoutSagas sagas, Clock clock) {
        this.conversations = conversations;
        this.reservations = reservations;
        this.payments = payments;
        this.sagas = sagas;
        this.clock = clock;
    }

    /** Runs until the checkout is open or a step fails; then releases the stock if it may be held. */
    public void runFrom(CheckoutSaga saga) {
        while (saga.hasStepToRun()) {
            switch (saga.currentStep()) {
                case READ_CONVERSATION -> readConversation(saga);
                case RESERVE_STOCK -> reserveStock(saga);
                case CREATE_PAYMENT -> createPayment(saga);
                case AWAIT_PAYMENT -> throw new IllegalStateException("await-payment is not a call");
            }
        }
        if (saga.mustReleaseStock()) {
            releaseStock(saga);
        }
    }

    private void readConversation(CheckoutSaga saga) {
        SagaStep step = begin(saga, SagaStep.READ_CONVERSATION);
        switch (conversations.read(saga.request().conversationId())) {
            case StepCall.Done<Conversation>(Conversation conversation) -> check(saga, conversation);
            case StepCall.Refused<Conversation> refused -> refuse(saga, step,
                    CONVERSATION_NOT_FOUND.equals(refused.code())
                            ? CheckoutError.CONVERSATION_NOT_FOUND : CheckoutError.CHAT_UNAVAILABLE,
                    null, "I-05", refused);
            case StepCall.NoAnswer<Conversation> none -> unanswered(saga, step, CheckoutError.CHAT_UNAVAILABLE, "I-05", none);
        }
    }

    /** Rows 5 and 6: the workflow's only checks; every other rule belongs to its participant. */
    private void check(CheckoutSaga saga, Conversation conversation) {
        if (!conversation.buyerId().equals(saga.request().buyerId())) {
            fail(saga, SagaStep.READ_CONVERSATION, new CheckoutFailure(CheckoutError.NOT_CONVERSATION_BUYER, null,
                    "caller " + saga.request().buyerId() + " is not the buyer " + conversation.buyerId()), false);
        } else if (!conversation.agreedOnPlatform()) {
            fail(saga, SagaStep.READ_CONVERSATION, new CheckoutFailure(CheckoutError.PURCHASE_METHOD_NOT_PLATFORM,
                    null, "agreedPurchaseMethod is " + conversation.agreedPurchaseMethod()), false);
        } else {
            saga.conversationRead(conversation, clock.instant());
            sagas.save(saga);
        }
    }

    private void reserveStock(CheckoutSaga saga) {
        SagaStep step = begin(saga, SagaStep.RESERVE_STOCK);
        StepCall<Reservation> call = reservations.reserve(saga.idempotencyKeyFor(step),
                saga.conversation().productId(), saga.transactionId(), saga.request().quantity());
        switch (call) {
            case StepCall.Done<Reservation>(Reservation reservation) -> {
                saga.stockReserved(reservation, clock.instant());
                sagas.save(saga);
            }
            case StepCall.Refused<Reservation> refused -> refuse(saga, step, reservationError(refused.code()),
                    INSUFFICIENT_STOCK.equals(refused.code()) ? refused.message() : null, "I-03", refused);
            case StepCall.NoAnswer<Reservation> none -> unanswered(saga, step, CheckoutError.CATALOG_UNAVAILABLE, "I-03", none);
        }
    }

    private void createPayment(CheckoutSaga saga) {
        SagaStep step = begin(saga, SagaStep.CREATE_PAYMENT);
        StepCall<PaymentCheckout> call = payments.create(saga.idempotencyKeyFor(step), saga.transactionId(),
                saga.conversation(), saga.reservation());
        switch (call) {
            case StepCall.Done<PaymentCheckout>(PaymentCheckout checkout) -> {
                saga.paymentCreated(checkout, clock.instant());
                sagas.save(saga);
            }
            case StepCall.Refused<PaymentCheckout> refused ->
                    refuse(saga, step, paymentError(refused.code()), null, "I-06", refused);
            case StepCall.NoAnswer<PaymentCheckout> none ->
                    unanswered(saga, step, CheckoutError.TRANSACTIONS_UNAVAILABLE, "I-06", none);
        }
    }

    private void releaseStock(CheckoutSaga saga) {
        if (reservations.release(saga.transactionId())) {
            saga.stockReleased(clock.instant());
        } else {
            saga.stockReleaseFailed(clock.instant());
        }
        sagas.save(saga);
    }

    /** Rows 8 and 9; any other refusal of I-03 is row 7 ("I-03 … failed"). */
    private static CheckoutError reservationError(String code) {
        return switch (code) {
            case PRODUCT_NOT_FOUND, PRODUCT_NOT_AVAILABLE -> CheckoutError.PRODUCT_NOT_AVAILABLE;
            case INSUFFICIENT_STOCK -> CheckoutError.INSUFFICIENT_STOCK;
            default -> CheckoutError.CATALOG_UNAVAILABLE;
        };
    }

    /** Rows 11 and 12; any other refusal of I-06 is row 10 ("I-06 … failed"). */
    private static CheckoutError paymentError(String code) {
        return switch (code) {
            case TRANSACTION_IN_PROGRESS -> CheckoutError.TRANSACTION_IN_PROGRESS;
            case PAYMENT_GATEWAY_ERROR -> CheckoutError.PAYMENT_GATEWAY_ERROR;
            default -> CheckoutError.TRANSACTIONS_UNAVAILABLE;
        };
    }

    private SagaStep begin(CheckoutSaga saga, SagaStep step) {
        saga.beginStep(step, clock.instant());
        sagas.save(saga);
        return step;
    }

    private void refuse(CheckoutSaga saga, SagaStep step, CheckoutError error, String message, String card,
                        StepCall.Refused<?> refused) {
        fail(saga, step, new CheckoutFailure(error, message,
                card + " answered " + refused.status() + " " + refused.code()), false);
    }

    private void unanswered(CheckoutSaga saga, SagaStep step, CheckoutError error, String card,
                            StepCall.NoAnswer<?> none) {
        fail(saga, step, new CheckoutFailure(error, null, card + " did not answer: " + none.reason()), true);
    }

    private void fail(CheckoutSaga saga, SagaStep step, CheckoutFailure failure, boolean possiblyDone) {
        saga.fail(step, failure, possiblyDone, clock.instant());
        sagas.save(saga);
    }
}
