package co.edu.corhuila.marketagri.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.marketagri.workflow.application.port.in.StartCheckout.Checkout;
import co.edu.corhuila.marketagri.workflow.application.port.in.StartCheckout.Command;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRejectedException;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRequest;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.PurchaseMethod;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStatus;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EnumSource;

class StartCheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    private static final String STOCK_MESSAGE = "Cantidad insuficiente: hay 2.00 disponibles y se solicitaron 3.00.";

    private final UUID buyer = UUID.randomUUID();
    private final UUID producer = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Reservation reservation = new Reservation(UUID.randomUUID(), product, "Café", producer,
            new BigDecimal("2.50"), "KILOGRAMO", 8_500_000L);
    private final FakeSagaWorld world = new FakeSagaWorld();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StartCheckoutService service = new StartCheckoutService(world,
            new CheckoutSteps(world, world, world, world, clock), clock);
    private final PaymentCheckout opened = new PaymentCheckout("{\"status\":\"PENDING\"}",
            URI.create("https://checkout.wompi.co/p/?reference=r"));

    StartCheckoutServiceTest() {
        world.conversationAnswer = new StepCall.Done<>(conversation(buyer, PurchaseMethod.PLATFORM));
        world.paymentAnswer = new StepCall.Done<>(opened);
    }

    @Test
    void opensTheCheckoutStoringEachAnswerBeforeTheNextCallWithTheStepKeysAndOneTransactionId() {
        world.reservationAnswer = new StepCall.Done<>(reservation());

        Checkout checkout = service.start(command("2.5"));

        CheckoutSaga saga = checkout.saga();
        assertThat(checkout.created()).isTrue();
        assertThat(saga.payment()).contains(opened);
        assertThat(saga.currentStep()).isEqualTo(SagaStep.AWAIT_PAYMENT);
        assertThat(world.log).containsExactly(
                "create",
                "save read-conversation=STARTED RUNNING",
                "I-05 " + conversationId,
                "save read-conversation=DONE RUNNING",
                "save read-conversation=DONE,reserve-stock=STARTED RUNNING",
                "I-03 " + saga.id() + ":reserve-stock " + product,
                "save read-conversation=DONE,reserve-stock=DONE RUNNING",
                "save read-conversation=DONE,reserve-stock=DONE,create-payment=STARTED RUNNING",
                "I-06 " + saga.id() + ":create-payment",
                "save read-conversation=DONE,reserve-stock=DONE,create-payment=DONE RUNNING");
        assertThat(world.reservedTransactionId).isEqualTo(saga.transactionId()).isEqualTo(world.paidTransactionId);
        assertThat(world.reservedQuantity).isEqualTo(new Quantity(new BigDecimal("2.50")));
        assertThat(world.paidReservation).isEqualTo(reservation());
    }

    @ParameterizedTest
    @CsvSource({"0, QUANTITY_NOT_POSITIVE", "-1, QUANTITY_NOT_POSITIVE"})
    void row2IsCheckedBeforeAnySagaOrReplay(String quantity, String code) {
        assertRejected(() -> service.start(command(quantity)), code);
        assertThat(world.log).isEmpty();
    }

    @Test
    void row3ChatWithoutAnswerEndsCompensatedAtReadConversationAndCallsNobodyElse() {
        world.conversationAnswer = new StepCall.NoAnswer<>("timeout after 3 attempts");

        assertRejected(() -> service.start(command("1")), "CHAT_UNAVAILABLE");

        CheckoutSaga saga = onlySaga();
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).contains(SagaStep.READ_CONVERSATION);
        assertThat(saga.failure().orElseThrow().detail()).isEqualTo("I-05 did not answer: timeout after 3 attempts");
        assertThat(world.calls()).containsExactly("I-05 " + conversationId);
    }

    @ParameterizedTest
    @CsvSource({"404, CONVERSATION_NOT_FOUND, CONVERSATION_NOT_FOUND", "401, UNAUTHORIZED, CHAT_UNAVAILABLE",
            "400, VALIDATION_ERROR, CHAT_UNAVAILABLE"})
    void row4OnlyForChatsOwn404AnyOtherRefusalOfI05IsRow3(int status, String chatCode, String answered) {
        world.conversationAnswer = new StepCall.Refused<>(status, chatCode, "…");

        assertRejected(() -> service.start(command("1")), answered);

        assertThat(world.calls()).hasSize(1);
        assertThat(onlySaga().failure().orElseThrow().detail()).isEqualTo("I-05 answered " + status + " " + chatCode);
    }

    @Test
    void row5OnlyTheBuyerOfTheConversationCanPay() {
        world.conversationAnswer = new StepCall.Done<>(conversation(UUID.randomUUID(), PurchaseMethod.PLATFORM));

        assertRejected(() -> service.start(command("1")), "NOT_CONVERSATION_BUYER");

        assertThat(world.calls()).hasSize(1);
        assertThat(onlySaga().failedStep()).contains(SagaStep.READ_CONVERSATION);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = PurchaseMethod.class, names = "OFF_PLATFORM")
    void row6TheAgreedMethodMustBePlatformNullIncluded(PurchaseMethod method) {
        world.conversationAnswer = new StepCall.Done<>(conversation(buyer, method));

        assertRejected(() -> service.start(command("1")), "PURCHASE_METHOD_NOT_PLATFORM");

        assertThat(world.calls()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"404, PRODUCT_NOT_FOUND, PRODUCT_NOT_AVAILABLE", "409, PRODUCT_NOT_AVAILABLE, PRODUCT_NOT_AVAILABLE",
            "409, RESERVATION_MISMATCH, CATALOG_UNAVAILABLE", "422, QUANTITY_NOT_POSITIVE, CATALOG_UNAVAILABLE"})
    void rows7And8ARefusedReservationHeldNothingSoNothingIsReleased(int status, String catalogCode, String answered) {
        world.reservationAnswer = new StepCall.Refused<>(status, catalogCode, "…");

        assertRejected(() -> service.start(command("1")), answered);

        CheckoutSaga saga = onlySaga();
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).contains(SagaStep.RESERVE_STOCK);
        assertThat(world.calls()).hasSize(2).noneMatch(call -> call.startsWith("I-04") || call.startsWith("I-06"));
    }

    @Test
    void row9KeepsCatalogsMessageWithItsQuantitiesAndRepeatsItOnAReplay() {
        world.reservationAnswer = new StepCall.Refused<>(409, "INSUFFICIENT_STOCK", STOCK_MESSAGE);

        assertThatThrownBy(() -> service.start(command("3")))
                .isInstanceOfSatisfying(CheckoutRejectedException.class, e -> {
                    assertThat(e.failure().error()).isEqualTo(CheckoutError.INSUFFICIENT_STOCK);
                    assertThat(e.failure().message()).isEqualTo(STOCK_MESSAGE);
                });
        assertThatThrownBy(() -> service.start(command("3")))
                .isInstanceOfSatisfying(CheckoutRejectedException.class,
                        e -> assertThat(e.failure().message()).isEqualTo(STOCK_MESSAGE));
        assertThat(world.calls()).hasSize(2);
    }

    @Test
    void row7AnUnansweredReservationMayHaveBeenMadeSoItIsReleasedBeforeAnswering() {
        world.reservationAnswer = new StepCall.NoAnswer<>("connection refused");

        assertRejected(() -> service.start(command("1")), "CATALOG_UNAVAILABLE");

        CheckoutSaga saga = onlySaga();
        assertThat(world.calls()).last().isEqualTo("I-04 " + saga.transactionId());
        assertThat(world.log).last().isEqualTo("save read-conversation=DONE,reserve-stock=COMPENSATED COMPENSATED");
        assertThat(saga.failedStep()).contains(SagaStep.RESERVE_STOCK);
    }

    @ParameterizedTest
    @CsvSource({"409, TRANSACTION_IN_PROGRESS, TRANSACTION_IN_PROGRESS",
            "502, PAYMENT_GATEWAY_ERROR, PAYMENT_GATEWAY_ERROR",
            "409, TRANSACTION_MISMATCH, TRANSACTIONS_UNAVAILABLE"})
    void rows10To12AFailedPaymentReleasesTheReservationBeforeAnswering(int status, String txCode, String answered) {
        world.reservationAnswer = new StepCall.Done<>(reservation());
        world.paymentAnswer = new StepCall.Refused<>(status, txCode, "…");

        assertRejected(() -> service.start(command("1")), answered);

        CheckoutSaga saga = onlySaga();
        assertThat(world.calls()).last().isEqualTo("I-04 " + saga.transactionId());
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).contains(SagaStep.CREATE_PAYMENT);
        assertThat(world.log).last()
                .isEqualTo("save read-conversation=DONE,reserve-stock=COMPENSATED,create-payment=REFUSED COMPENSATED");
    }

    @Test
    void row10AnUnansweredPaymentIsRow10AndAlsoReleases() {
        world.reservationAnswer = new StepCall.Done<>(reservation());
        world.paymentAnswer = new StepCall.NoAnswer<>("read timed out");

        assertRejected(() -> service.start(command("1")), "TRANSACTIONS_UNAVAILABLE");

        assertThat(world.calls()).last().asString().startsWith("I-04 ");
        assertThat(onlySaga().status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    void aReleaseWithoutAnswerStillAnswersTheRowAndLeavesTheSagaCompensatingForTheSweep() {
        world.reservationAnswer = new StepCall.Done<>(reservation());
        world.paymentAnswer = new StepCall.Refused<>(502, "PAYMENT_GATEWAY_ERROR", "…");
        world.releaseAnswer = false;

        assertRejected(() -> service.start(command("1")), "PAYMENT_GATEWAY_ERROR");

        CheckoutSaga saga = onlySaga();
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.mustReleaseStock()).isTrue();
        assertThat(saga.step(SagaStep.RESERVE_STOCK).orElseThrow().compensationAttempts()).isEqualTo(1);
        assertThat(world.log).last().asString().endsWith("(release) RUNNING");
    }

    @Test
    void aReplayOfAnOpenCheckoutAnswersTheOriginalAndCallsNobody() {
        world.reservationAnswer = new StepCall.Done<>(reservation());
        Checkout first = service.start(command("1"));
        world.log.clear();

        Checkout replay = service.start(command("1"));

        assertThat(replay.created()).isFalse();
        assertThat(replay.saga()).isSameAs(first.saga());
        assertThat(world.log).isEmpty();
    }

    @Test
    void aReplayOfAFailedCheckoutAnswersTheSameErrorAndCallsNobody() {
        world.conversationAnswer = new StepCall.Refused<>(404, "CONVERSATION_NOT_FOUND", "…");
        assertRejected(() -> service.start(command("1")), "CONVERSATION_NOT_FOUND");
        world.log.clear();

        assertRejected(() -> service.start(command("1")), "CONVERSATION_NOT_FOUND");

        assertThat(world.log).isEmpty();
    }

    @Test
    void row2aAReplayWhileTheSagaIsStillInSteps1To3IsCheckoutInProgress() {
        CheckoutSaga running = CheckoutSaga.start(new CheckoutRequest(buyer, "key-12345", conversationId,
                new Quantity(BigDecimal.ONE)), NOW);
        running.beginStep(SagaStep.READ_CONVERSATION, NOW);
        world.stored.put(buyer + "/key-12345", running);

        assertRejected(() -> service.start(command("1")), "CHECKOUT_IN_PROGRESS");

        assertThat(world.log).isEmpty();
    }

    @Test
    void aRequestThatLosesTheRaceForItsKeyAnswersTheWinnersSaga() {
        CheckoutSaga winner = CheckoutSaga.start(new CheckoutRequest(buyer, "key-12345", conversationId,
                new Quantity(BigDecimal.ONE)), NOW);
        world.raceWinner = winner;

        assertRejected(() -> service.start(command("1")), "CHECKOUT_IN_PROGRESS");

        assertThat(world.calls()).isEmpty();
    }

    private Command command(String quantity) {
        return new Command(buyer, "key-12345", conversationId, new BigDecimal(quantity));
    }

    private CheckoutSaga onlySaga() {
        assertThat(world.stored).hasSize(1);
        return world.stored.values().iterator().next();
    }

    private Conversation conversation(UUID buyerId, PurchaseMethod method) {
        return new Conversation(conversationId, product, "Café", buyerId, "Ana", producer, "Luis", method);
    }

    private Reservation reservation() {
        return reservation;
    }

    private static void assertRejected(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CheckoutRejectedException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
