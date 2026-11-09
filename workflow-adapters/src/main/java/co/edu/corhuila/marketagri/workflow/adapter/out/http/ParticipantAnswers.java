package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;

/**
 * A participant's answer as the core's {@link StepCall}: {@code 2xx} is done, {@code 4xx} a refusal
 * with its code. A {@code 5xx}/{@code 429} left after the retries, or an unreadable {@code 2xx}, is
 * no answer (possibly done, ADR-015 §4), unless it carries a code of the participant's own card
 * (I-06 {@code 502 PAYMENT_GATEWAY_ERROR}): that is an answer.
 */
final class ParticipantAnswers {

    private static final Set<String> SHARED_SERVER_CODES =
            Set.of("", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE", "TOO_MANY_REQUESTS");

    @FunctionalInterface
    interface ViewReader<T> {
        T read(JsonNode view);
    }

    private ParticipantAnswers() {
    }

    static <T> StepCall<T> toStepCall(Answer answer, ObjectMapper json, ViewReader<T> reader) {
        return switch (answer) {
            case Answer.Unreachable(String reason) -> new StepCall.NoAnswer<>(reason);
            case Answer.Response response when response.status() / 100 == 2 -> read(response, json, reader);
            case Answer.Response response -> refusedOrUnanswered(response, json);
        };
    }

    private static <T> StepCall<T> read(Answer.Response response, ObjectMapper json, ViewReader<T> reader) {
        try {
            return new StepCall.Done<>(reader.read(json.readTree(response.body())));
        } catch (Exception e) {
            return new StepCall.NoAnswer<>("unreadable " + response.status() + " answer: " + e.getMessage());
        }
    }

    private static <T> StepCall<T> refusedOrUnanswered(Answer.Response response, ObjectMapper json) {
        JsonNode envelope = envelope(response.body(), json);
        String code = envelope.path("error").asText("");
        boolean serverSide = ServiceCaller.retryable(response);
        if (serverSide && SHARED_SERVER_CODES.contains(code)) {
            return new StepCall.NoAnswer<>("status " + response.status() + " after the retries");
        }
        return new StepCall.Refused<>(response.status(), code, envelope.path("message").asText(""));
    }

    private static JsonNode envelope(String body, ObjectMapper json) {
        try {
            JsonNode node = json.readTree(body == null ? "" : body);
            return node == null ? json.createObjectNode() : node;
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    static String text(JsonNode view, String field) {
        JsonNode value = view.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " is not a string");
        }
        return value.asText();
    }

    static UUID uuid(JsonNode view, String field) {
        return UUID.fromString(text(view, field));
    }
}
