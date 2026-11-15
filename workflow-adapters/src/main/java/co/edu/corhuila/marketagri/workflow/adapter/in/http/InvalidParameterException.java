package co.edu.corhuila.marketagri.workflow.adapter.in.http;

/** A header out of range ({@code Idempotency-Key}): {@code 400 VALIDATION_ERROR} naming it (D-C35). */
final class InvalidParameterException extends RuntimeException {

    private final String field;

    InvalidParameterException(String field, String message) {
        super(message);
        this.field = field;
    }

    String field() {
        return field;
    }
}
