package co.edu.corhuila.marketagri.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.marketagri.workflow.adapter.out.http.ServiceCaller;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.PurchaseMethod;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** E-40 over HTTP with the participants and the store in memory: status, headers, body and each row. */
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutHttpTest extends WorkflowSpringTest {

    private static final String ROUTE = "/api/v1/workflow/checkouts";
    private static final String VIEW = "{\"id\":\"7b1e2f0a-3c4d-4e5f-8a9b-0c1d2e3f4a5b\",\"status\":\"PENDING\","
            + "\"amountCents\":21250000,\"paymentMethod\":null}";
    private static final String CHECKOUT_URL = "https://checkout.wompi.co/p/?reference=7b1e2f0a";

    @TestConfiguration
    static class Participants {
        @Bean
        @Primary
        InMemorySaga inMemorySaga() {
            return new InMemorySaga();
        }
    }

    @MockitoBean
    private ServiceCaller unusedHttpClient;

    @Autowired
    private MockMvc http;

    @Autowired
    private InMemorySaga saga;

    private final UUID buyer = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void openableCheckout() {
        saga.reset();
        UUID product = UUID.randomUUID();
        UUID producer = UUID.randomUUID();
        saga.conversationAnswer = new StepCall.Done<>(new Conversation(conversationId, product, "Café", buyer, "Ana",
                producer, "Luis", PurchaseMethod.PLATFORM));
        saga.reservationAnswer = new StepCall.Done<>(new Reservation(UUID.randomUUID(), product, "Café", producer,
                new BigDecimal("2.50"), "KILOGRAMO", 8_500_000L));
        saga.paymentAnswer = new StepCall.Done<>(new PaymentCheckout(VIEW, URI.create(CHECKOUT_URL)));
    }

    @Test
    void theFirstRequestIs201WithTheLocationOfTheSagaAndTheCheckoutAsI06AnsweredIt() throws Exception {
        start("key-00000001", "2.5")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", ROUTE + "/" + onlySaga().id()))
                .andExpect(content().json("{\"transaction\":" + VIEW + ",\"checkoutUrl\":\"" + CHECKOUT_URL + "\"}", true));

        assertThat(saga.calls).containsExactly("I-05", "I-03 " + onlySaga().id() + ":reserve-stock",
                "I-06 " + onlySaga().id() + ":create-payment");
        assertThat(onlySaga().request().buyerId()).isEqualTo(buyer);
    }

    @Test
    void aReplayIs200WithTheSameBodyNoLocationAndNoStep() throws Exception {
        String first = start("key-00000001", "2.5").andReturn().getResponse().getContentAsString();
        saga.calls.clear();

        start("key-00000001", "2.5")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().json(first, true));
        assertThat(saga.calls).isEmpty();
    }

    @Test
    void onlyABuyerCanStartAPurchase() throws Exception {
        http.perform(post(ROUTE).header("Idempotency-Key", "key-00000001").contentType(MediaType.APPLICATION_JSON)
                        .content(body("1")))
                .andExpect(status().isUnauthorized());
        http.perform(post(ROUTE).header("Authorization", "Bearer " + TestTokens.user("PRODUCER")
                                .claim("sub", buyer.toString()).signed())
                        .header("Idempotency-Key", "key-00000001").contentType(MediaType.APPLICATION_JSON)
                        .content(body("1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        assertThat(saga.calls).isEmpty();
    }

    @Test
    void theIdempotencyKeyIsRequiredWithEightTo128Characters() throws Exception {
        http.perform(post(ROUTE).header("Authorization", "Bearer " + buyerToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body("1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        start("short", "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        start("k".repeat(129), "1").andExpect(status().isBadRequest());
        assertThat(saga.stored).isEmpty();
    }

    @Test
    void row1AMissingConversationOrAQuantityWithThreeDecimalsIs400NamingTheField() throws Exception {
        http.perform(post(ROUTE).header("Authorization", "Bearer " + buyerToken())
                        .header("Idempotency-Key", "key-00000001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("conversationId"));
        start("key-00000002", "1.234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("quantity"));
        assertThat(saga.stored).isEmpty();
    }

    @Test
    void row2AQuantityOfZeroIs422AfterRow1() throws Exception {
        start("key-00000001", "0")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("QUANTITY_NOT_POSITIVE"))
                .andExpect(jsonPath("$.message").value("La cantidad debe ser mayor que cero."));
        assertThat(saga.stored).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "chat, 503, CHAT_UNAVAILABLE, El chat no está disponible. Intente de nuevo.",
            "catalog, 503, CATALOG_UNAVAILABLE, El catálogo no está disponible. Intente de nuevo.",
            "transactions, 503, TRANSACTIONS_UNAVAILABLE, El servicio de pagos no está disponible. Intente de nuevo."})
    void anUnansweredParticipantIsItsRowWithoutSayingWhichOrHow(String participant, int status, String code,
                                                                  String message) throws Exception {
        StepCall.NoAnswer<Object> none = new StepCall.NoAnswer<>("connect timed out to " + participant + "-api:8080");
        switch (participant) {
            case "chat" -> saga.conversationAnswer = cast(none);
            case "catalog" -> saga.reservationAnswer = cast(none);
            default -> saga.paymentAnswer = cast(none);
        }

        start("key-00000001", "1")
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(content().string(not(containsString("api:8080"))))
                .andExpect(content().string(not(containsString("I-0"))));
    }

    @Test
    void row9RepeatsCatalogsMessageWithItsQuantities() throws Exception {
        saga.reservationAnswer = new StepCall.Refused<>(409, "INSUFFICIENT_STOCK",
                "Cantidad insuficiente: hay 2.00 disponibles y se solicitaron 3.00.");

        start("key-00000001", "3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Cantidad insuficiente: hay 2.00 disponibles y se solicitaron 3.00."));
    }

    @Test
    void row12IsA502AndTheReservationIsReleasedBeforeTheAnswer() throws Exception {
        saga.paymentAnswer = new StepCall.Refused<>(502, "PAYMENT_GATEWAY_ERROR", "…");

        start("key-00000001", "1")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PAYMENT_GATEWAY_ERROR"));
        assertThat(saga.calls).last().isEqualTo("I-04");
    }

    @Test
    void row2aAReplayOfASagaStillInSteps1To3IsCheckoutInProgress() throws Exception {
        start("key-00000001", "1");
        CheckoutSaga stuck = CheckoutSaga.start(onlySaga().request(), onlySaga().createdAt());
        saga.stored.replaceAll((key, value) -> stuck);

        start("key-00000001", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CHECKOUT_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("La compra se está procesando. Intente de nuevo en unos segundos."));
    }

    @Test
    void e46ShowsOnlyPublicProgressToTheBuyerAndAdmin() throws Exception {
        String location = start("key-00000001", "1").andReturn().getResponse().getHeader("Location");
        for (String token : new String[] {buyerToken(), TestTokens.user("ADMIN").signed()}) {
            http.perform(get(location).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(onlySaga().id().toString()))
                    .andExpect(jsonPath("$.transactionId").value(onlySaga().transactionId().toString()))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.completedSteps").value(
                            java.util.List.of("read-conversation", "reserve-stock", "create-payment")))
                    .andExpect(jsonPath("$.failedStep").isEmpty())
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.updatedAt").isString())
                    .andExpect(jsonPath("$.errorDetail").doesNotHaveJsonPath());
        }
        for (String route : new String[] {location, ROUTE + "/" + UUID.randomUUID()}) {
            http.perform(get(route).header("Authorization", "Bearer " + TestTokens.user("BUYER").signed()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("CHECKOUT_NOT_FOUND"));
        }
        http.perform(get(ROUTE + "/not-a-uuid").header("Authorization", "Bearer " + buyerToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private ResultActions start(String key, String quantity) throws Exception {
        return http.perform(post(ROUTE).header("Authorization", "Bearer " + buyerToken())
                .header("Idempotency-Key", key).header("X-Correlation-Id", "e2e-checkout-1")
                .contentType(MediaType.APPLICATION_JSON).content(body(quantity)));
    }

    private String body(String quantity) {
        return "{\"conversationId\":\"" + conversationId + "\",\"quantity\":" + quantity + "}";
    }

    private String buyerToken() {
        return TestTokens.user("BUYER").claim("sub", buyer.toString()).signed();
    }

    private CheckoutSaga onlySaga() {
        assertThat(saga.stored).hasSize(1);
        return saga.stored.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static <T> StepCall<T> cast(StepCall<Object> call) {
        return (StepCall<T>) (StepCall<?>) call;
    }
}
