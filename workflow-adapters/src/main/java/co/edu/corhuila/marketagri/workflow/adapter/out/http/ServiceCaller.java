package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import co.edu.corhuila.marketagri.workflow.adapter.correlation.Correlation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One call to another service as Anexo D and ADR-015 §4 ask: the workflow's {@code SERVICE_TOKEN},
 * the {@code X-Correlation-Id} of the request, a timeout per attempt, and at most {@code attempts}
 * tries, retried only on network errors, {@code 429} and {@code 5xx}, with full jitter: before
 * attempt {@code n} it waits a random time between 0 and {@code backoffBase × 2ⁿ⁻¹}.
 */
public class ServiceCaller {

    private static final Logger log = LoggerFactory.getLogger(ServiceCaller.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int FIRST_SERVER_ERROR = 500;

    /** The tests record the waits instead of sleeping. */
    @FunctionalInterface
    public interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient client;
    private final String serviceToken;
    private final Duration timeout;
    private final int attempts;
    private final Duration backoffBase;
    private final RandomGenerator random;
    private final Pause pause;

    public ServiceCaller(String serviceToken, Duration timeout, int attempts, Duration backoffBase,
                         RandomGenerator random, Pause pause) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("SERVICE_TOKEN is required");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("HTTP_ATTEMPTS must be at least 1: " + attempts);
        }
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.serviceToken = serviceToken;
        this.timeout = timeout;
        this.attempts = attempts;
        this.backoffBase = backoffBase;
        this.random = random;
        this.pause = pause;
    }

    /** {@code idempotencyKey} and {@code jsonBody} may be {@code null}. Returns the last answer. */
    public Answer call(String method, URI uri, String idempotencyKey, String jsonBody) {
        HttpRequest request = request(method, uri, idempotencyKey, jsonBody);
        Answer answer = once(request);
        for (int attempt = 2; attempt <= attempts && retryable(answer); attempt++) {
            log.warn("{} {} attempt {} of {} failed: {}", method, uri.getPath(), attempt - 1, attempts, answer);
            if (!waitBefore(attempt)) {
                return answer;
            }
            answer = once(request);
        }
        return answer;
    }

    private HttpRequest request(String method, URI uri, String idempotencyKey, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + serviceToken)
                .header("X-Correlation-Id", Correlation.current())
                .header("Accept", "application/json");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody)).build();
    }

    private Answer once(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Answer.Response(response.statusCode(), response.body());
        } catch (IOException e) {
            return new Answer.Unreachable(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer.Unreachable("interrupted");
        }
    }

    private boolean waitBefore(int attempt) {
        long ceilingMillis = backoffBase.toMillis() << (attempt - 1);
        try {
            pause.sleep(Duration.ofMillis(random.nextLong(ceilingMillis + 1)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean retryable(Answer answer) {
        return switch (answer) {
            case Answer.Unreachable unreachable -> true;
            case Answer.Response response ->
                    response.status() == TOO_MANY_REQUESTS || response.status() >= FIRST_SERVER_ERROR;
        };
    }
}
