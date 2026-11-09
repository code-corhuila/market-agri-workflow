package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.PurchaseMethod;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The three participants over HTTP: path, key and body sent, the view read, and each answer mapped. */
class HttpParticipantsTest {

    final ObjectMapper json = new ObjectMapper();
    final StubParticipant stub;
    final ServiceCaller caller = new ServiceCaller(ServiceCallerTest.TOKEN, Duration.ofSeconds(3), 3,
            Duration.ofMillis(200), ServiceCallerTest.CEILING, duration -> { });
    final UUID conversationId = UUID.randomUUID();
    final UUID productId = UUID.randomUUID();
    final UUID buyerId = UUID.randomUUID();
    final UUID producerId = UUID.randomUUID();
    final UUID transactionId = UUID.randomUUID();

    HttpParticipantsTest() throws Exception {
        stub = new StubParticipant();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void i05ReadsTheConversationViewWithANullPurchaseMethod() {
        stub.answer(200, conversationJson("null"));

        StepCall<Conversation> call = new HttpConversationReader(caller, stub.url(), json).read(conversationId);

        assertThat(call).isEqualTo(new StepCall.Done<>(conversation(null)));
        assertThat(stub.received.get(0).method()).isEqualTo("GET");
        assertThat(stub.received.get(0).path()).isEqualTo("/internal/v1/chat/conversations/" + conversationId);
    }

    @Test
    void i05ReadsAnAgreedMethodAndAnswers404AsARefusalWithChatsCode() {
        stub.answer(200, conversationJson("\"PLATFORM\"")).answer(404,
                "{\"error\":\"CONVERSATION_NOT_FOUND\",\"message\":\"La conversación no existe.\"}");
        var reader = new HttpConversationReader(caller, stub.url(), json);

        assertThat(reader.read(conversationId)).isEqualTo(new StepCall.Done<>(conversation(PurchaseMethod.PLATFORM)));
        assertThat(reader.read(conversationId))
                .isEqualTo(new StepCall.Refused<>(404, "CONVERSATION_NOT_FOUND", "La conversación no existe."));
        assertThat(stub.received).hasSize(2);
    }

    @Test
    void aServerErrorLeftAfterTheRetriesOrAnUnreadableViewIsNoAnswer() {
        stub.answer(503, "{\"error\":\"SERVICE_UNAVAILABLE\"}");
        assertThat(new HttpConversationReader(caller, stub.url(), json).read(conversationId))
                .isInstanceOf(StepCall.NoAnswer.class);
        assertThat(stub.received).hasSize(3);

        try (StubParticipant broken = new StubParticipant().answer(200, "{\"id\":\"not-a-uuid\"}")) {
            assertThat(new HttpConversationReader(caller, broken.url(), json).read(conversationId))
                    .isInstanceOfSatisfying(StepCall.NoAnswer.class,
                            none -> assertThat(none.reason()).startsWith("unreadable 200 answer"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void i03SendsTheTransactionIdTheQuantityAndTheStepKeyAndReadsTheReservation() throws Exception {
        stub.answer(201, reservationJson());

        StepCall<Reservation> call = new HttpStockReservations(caller, stub.url(), json)
                .reserve("saga:reserve-stock", productId, transactionId, new Quantity(new BigDecimal("2.5")));

        assertThat(call).isEqualTo(new StepCall.Done<>(new Reservation(transactionId, productId, "Café", producerId,
                new BigDecimal("2.50"), "KILOGRAMO", 8_500_000L)));
        StubParticipant.Received request = stub.received.get(0);
        assertThat(request.path()).isEqualTo("/internal/v1/catalog/products/" + productId + "/reservations");
        assertThat(request.idempotencyKey()).isEqualTo("saga:reserve-stock");
        JsonNode body = json.readTree(request.body());
        assertThat(body.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(body.get("quantity").isNumber()).isTrue();
        assertThat(body.get("quantity").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(body.size()).isEqualTo(2);
    }

    @Test
    void i03KeepsCatalogsCodeAndMessageOfARefusal() {
        stub.answer(409, "{\"error\":\"INSUFFICIENT_STOCK\",\"message\":\"Cantidad insuficiente: hay 2 disponibles\"}");

        assertThat(new HttpStockReservations(caller, stub.url(), json)
                .reserve("k", productId, transactionId, new Quantity(BigDecimal.ONE)))
                .isEqualTo(new StepCall.Refused<>(409, "INSUFFICIENT_STOCK", "Cantidad insuficiente: hay 2 disponibles"));
        assertThat(stub.received).hasSize(1);
    }

    @Test
    void i03KeepsTheLargestQuantityOfNumeric12Of2ExactWithItsScale() {
        stub.answer(201, reservationJson().replace("\"quantity\":2.50", "\"quantity\":9999999999.99"));

        assertThat(new HttpStockReservations(caller, stub.url(), json)
                .reserve("k", productId, transactionId, new Quantity(BigDecimal.ONE)))
                .isInstanceOfSatisfying(StepCall.Done.class, done -> assertThat(((Reservation) done.value()).quantity())
                        .hasToString("9999999999.99"));
    }

    @Test
    void i03AQuantityWithMoreThanTwoDecimalsIsNotAReservationTheWorkflowCanUse() {
        stub.answer(201, reservationJson().replace("\"quantity\":2.50", "\"quantity\":2.505"));

        assertThat(new HttpStockReservations(caller, stub.url(), json)
                .reserve("k", productId, transactionId, new Quantity(BigDecimal.ONE)))
                .isInstanceOf(StepCall.NoAnswer.class);
    }

    @Test
    void i03APriceInDecimalsIsNotAReservationTheWorkflowCanUse() {
        stub.answer(201, reservationJson().replace("8500000", "85000.5"));

        assertThat(new HttpStockReservations(caller, stub.url(), json)
                .reserve("k", productId, transactionId, new Quantity(BigDecimal.ONE)))
                .isInstanceOf(StepCall.NoAnswer.class);
    }

    @Test
    void i04ReleasesWithASuccessAndReportsAServerErrorLeftAfterTheRetriesAsNotReleased() throws Exception {
        stub.answer(204, "");
        var reservations = new HttpStockReservations(caller, stub.url(), json);

        assertThat(reservations.release(transactionId)).isTrue();
        assertThat(stub.received.get(0).method()).isEqualTo("DELETE");
        assertThat(stub.received.get(0).path()).isEqualTo("/internal/v1/catalog/reservations/" + transactionId);

        try (StubParticipant down = new StubParticipant().answer(503, "")) {
            assertThat(new HttpStockReservations(caller, down.url(), json).release(transactionId)).isFalse();
            assertThat(down.received).hasSize(3);
        }
    }

    @Test
    void i06SendsTheSnapshotAndKeepsTheTransactionViewAsTransactionsWroteIt() throws Exception {
        String view = "{\"id\":\"" + transactionId + "\",\"status\":\"PENDING\",\"amountCents\":21250000}";
        stub.answer(201, "{\"transaction\":" + view + ",\"checkoutUrl\":\"https://checkout.wompi.co/p/?reference=r\"}");

        StepCall<PaymentCheckout> call = new HttpPaymentCreator(caller, stub.url(), json)
                .create("saga:create-payment", transactionId, conversation(PurchaseMethod.PLATFORM), reservation());

        assertThat(call).isEqualTo(new StepCall.Done<>(new PaymentCheckout(view,
                URI.create("https://checkout.wompi.co/p/?reference=r"))));
        StubParticipant.Received request = stub.received.get(0);
        assertThat(request.path()).isEqualTo("/internal/v1/transactions");
        assertThat(request.idempotencyKey()).isEqualTo("saga:create-payment");
        JsonNode body = json.readTree(request.body());
        assertThat(body.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(body.get("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(body.get("buyerId").asText()).isEqualTo(buyerId.toString());
        assertThat(body.get("buyerName").asText()).isEqualTo("Ana");
        assertThat(body.get("producerId").asText()).isEqualTo(producerId.toString());
        assertThat(body.get("producerName").asText()).isEqualTo("Luis");
        assertThat(body.get("productId").asText()).isEqualTo(productId.toString());
        assertThat(body.get("productName").asText()).isEqualTo("Café");
        assertThat(body.get("quantity").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(body.get("unit").asText()).isEqualTo("KILOGRAMO");
        assertThat(body.get("unitPriceCents").isIntegralNumber()).isTrue();
        assertThat(body.get("unitPriceCents").longValue()).isEqualTo(8_500_000L);
        assertThat(body.size()).isEqualTo(11);
    }

    @Test
    void i06PaymentGatewayError502IsAnAnswerOfItsCardButInternalError500IsNoAnswer() throws Exception {
        stub.answer(502, "{\"error\":\"PAYMENT_GATEWAY_ERROR\",\"message\":\"No fue posible iniciar el pago.\"}");

        assertThat(new HttpPaymentCreator(caller, stub.url(), json)
                .create("k", transactionId, conversation(PurchaseMethod.PLATFORM), reservation()))
                .isEqualTo(new StepCall.Refused<>(502, "PAYMENT_GATEWAY_ERROR", "No fue posible iniciar el pago."));
        assertThat(stub.received).hasSize(3);

        try (StubParticipant failing = new StubParticipant().answer(500, "{\"error\":\"INTERNAL_ERROR\"}")) {
            assertThat(new HttpPaymentCreator(caller, failing.url(), json)
                    .create("k", transactionId, conversation(PurchaseMethod.PLATFORM), reservation()))
                    .isInstanceOf(StepCall.NoAnswer.class);
        }
    }

    private Conversation conversation(PurchaseMethod method) {
        return new Conversation(conversationId, productId, "Café", buyerId, "Ana", producerId, "Luis", method);
    }

    private Reservation reservation() {
        return new Reservation(transactionId, productId, "Café", producerId, new BigDecimal("2.50"), "KILOGRAMO",
                8_500_000L);
    }

    private String conversationJson(String method) {
        return "{\"id\":\"" + conversationId + "\",\"productId\":\"" + productId + "\",\"productName\":\"Café\","
                + "\"buyerId\":\"" + buyerId + "\",\"buyerName\":\"Ana\",\"producerId\":\"" + producerId + "\","
                + "\"producerName\":\"Luis\",\"agreedPurchaseMethod\":" + method + ",\"lastMessageAt\":null,"
                + "\"createdAt\":\"2026-11-09T15:00:00Z\"}";
    }

    private String reservationJson() {
        return "{\"transactionId\":\"" + transactionId + "\",\"productId\":\"" + productId + "\","
                + "\"productName\":\"Café\",\"producerId\":\"" + producerId + "\",\"quantity\":2.50,"
                + "\"unit\":\"KILOGRAMO\",\"unitPriceCents\":8500000,\"currency\":\"COP\","
                + "\"remainingQuantity\":7.50,\"createdAt\":\"2026-11-09T15:00:00Z\"}";
    }
}
