package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.util.UUID;

/**
 * Who calls, taken from a verified token (authentication.md §2). {@code subject} is the only
 * source of the caller's identity; {@code name} is absent in service tokens.
 */
public record Caller(String subject, String role, String name) {

    /** Request attribute where {@link AuthInterceptor} leaves the caller for the controllers. */
    public static final String ATTRIBUTE = "workflow.caller";

    static final String SERVICE_ROLE = "SERVICE";

    public boolean isService() {
        return SERVICE_ROLE.equals(role);
    }

    /** A user token has a UUID {@code sub} and a {@code name} (authentication.md §2); otherwise it is not valid. */
    public UUID userId() {
        if (name == null || name.isBlank()) {
            throw new UnauthorizedException("user token without name");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new UnauthorizedException("user token whose sub is not a UUID");
        }
    }
}
