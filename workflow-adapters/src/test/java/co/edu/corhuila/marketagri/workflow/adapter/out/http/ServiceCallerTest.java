package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.marketagri.workflow.adapter.correlation.Correlation;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class ServiceCallerTest {

    static final String TOKEN = "service-token-for-tests";
    /** Always the ceiling of the jitter, so the waits are exact: 400 ms, then 800 ms with a base of 200 ms. */
    static final RandomGenerator CEILING = new RandomGenerator() {
        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

    final List<Duration> waits = new ArrayList<>();
    final StubParticipant stub;

    ServiceCallerTest() throws Exception {
        stub = new StubParticipant();
    }

    @AfterEach
    void stop() {
        stub.close();
        MDC.clear();
    }

    ServiceCaller caller(Duration timeout) {
        return new ServiceCaller(TOKEN, timeout, 3, Duration.ofMillis(200), CEILING, waits::add);
    }

    @Test
    void sendsTheServiceTokenTheRequestsCorrelationIdTheKeyAndTheJsonBody() {
        stub.answer(201, "{\"ok\":true}");
        MDC.put(Correlation.MDC_KEY, "e2e-saga-1");

        Answer answer = caller(Duration.ofSeconds(3)).call("POST", stub.url().resolve("/internal/v1/x"), "s:reserve-stock",
                "{\"a\":1}");

        assertThat(answer).isEqualTo(new Answer.Response(201, "{\"ok\":true}"));
        assertThat(stub.received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/internal/v1/x");
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.correlationId()).isEqualTo("e2e-saga-1");
            assertThat(request.idempotencyKey()).isEqualTo("s:reserve-stock");
            assertThat(request.contentType()).isEqualTo("application/json");
            assertThat(request.body()).isEqualTo("{\"a\":1}");
        });
        assertThat(waits).isEmpty();
    }

    @Test
    void withoutARequestTheCallStillCarriesACorrelationIdAndAReadHasNoKeyNorBody() {
        stub.answer(200, "{}");

        caller(Duration.ofSeconds(3)).call("GET", stub.url().resolve("/r"), null, null);

        StubParticipant.Received request = stub.received.get(0);
        assertThat(UUID.fromString(request.correlationId())).isNotNull();
        assertThat(request.idempotencyKey()).isNull();
        assertThat(request.contentType()).isNull();
        assertThat(request.body()).isEmpty();
    }

    @Test
    void retries503And429WithFullJitterUnderTheCeilingOfEachAttempt() {
        stub.answer(503, "").answer(429, "").answer(201, "{}");

        Answer answer = caller(Duration.ofSeconds(3)).call("POST", stub.url().resolve("/r"), "k", "{}");

        assertThat(answer).isEqualTo(new Answer.Response(201, "{}"));
        assertThat(stub.received).hasSize(3);
        assertThat(stub.received).extracting(StubParticipant.Received::idempotencyKey).containsOnly("k");
        assertThat(waits).containsExactly(Duration.ofMillis(400), Duration.ofMillis(800));
    }

    @Test
    void theWaitIsRandomBetweenZeroAndTheCeiling() {
        stub.answer(503, "");
        List<Long> bounds = new ArrayList<>();
        RandomGenerator recording = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public long nextLong(long bound) {
                bounds.add(bound);
                return 0;
            }
        };

        new ServiceCaller(TOKEN, Duration.ofSeconds(3), 3, Duration.ofMillis(200), recording, waits::add)
                .call("GET", stub.url().resolve("/r"), null, null);

        assertThat(bounds).containsExactly(401L, 801L);
        assertThat(waits).containsExactly(Duration.ZERO, Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 409, 422})
    void aClientErrorIsNeverRetried(int status) {
        stub.answer(status, "{\"error\":\"X\"}");

        Answer answer = caller(Duration.ofSeconds(3)).call("GET", stub.url().resolve("/r"), null, null);

        assertThat(answer).isEqualTo(new Answer.Response(status, "{\"error\":\"X\"}"));
        assertThat(stub.received).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void afterTheLastAttemptItReturnsTheLastServerError() {
        stub.answer(503, "{\"error\":\"SERVICE_UNAVAILABLE\"}");

        Answer answer = caller(Duration.ofSeconds(3)).call("GET", stub.url().resolve("/r"), null, null);

        assertThat(answer).isEqualTo(new Answer.Response(503, "{\"error\":\"SERVICE_UNAVAILABLE\"}"));
        assertThat(stub.received).hasSize(3);
    }

    @Test
    void aSlowAnswerTimesOutEachAttemptAndEndsUnreachable() {
        stub.answerAfter(200, "{}", 1_000);

        Answer answer = caller(Duration.ofMillis(150)).call("GET", stub.url().resolve("/r"), null, null);

        assertThat(answer).isInstanceOfSatisfying(Answer.Unreachable.class,
                unreachable -> assertThat(unreachable.reason()).contains("HttpTimeoutException"));
        assertThat(stub.received).hasSize(3);
    }

    @Test
    void aClosedPortIsRetriedAndEndsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        Answer answer = caller(Duration.ofSeconds(1)).call("GET", URI.create("http://127.0.0.1:" + closedPort + "/r"),
                null, null);

        assertThat(answer).isInstanceOf(Answer.Unreachable.class);
        assertThat(waits).hasSize(2);
    }

    @Test
    void aTokenAndAtLeastOneAttemptAreRequired() {
        assertThatThrownBy(() -> new ServiceCaller(" ", Duration.ofSeconds(3), 3, Duration.ofMillis(200), CEILING,
                waits::add)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceCaller(TOKEN, Duration.ofSeconds(3), 0, Duration.ofMillis(200), CEILING,
                waits::add)).isInstanceOf(IllegalArgumentException.class);
    }
}
