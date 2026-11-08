package co.edu.corhuila.marketagri.workflow.adapter.in.http;

/** A valid credential that may not use this route: {@code 403 FORBIDDEN}. The message goes only to the log. */
final class ForbiddenException extends RuntimeException {

    ForbiddenException(String reason) {
        super(reason);
    }
}
