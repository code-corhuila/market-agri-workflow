package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.util.List;

/**
 * The only error body of the service (api-contract D-C21): {@code error} is the stable code,
 * {@code message} is Spanish and user-safe, {@code details} is {@code []} except on
 * {@code VALIDATION_ERROR}, and {@code traceId} equals the request's {@code X-Correlation-Id}.
 */
public record ApiError(String error, String message, List<FieldDetail> details, String traceId) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** One failing field (or header, such as {@code Idempotency-Key}). */
    public record FieldDetail(String field, String message) {
    }
}
