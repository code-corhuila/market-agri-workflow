package co.edu.corhuila.marketagri.workflow.adapter.in.http;

/** No credential, or one that is not valid: {@code 401 UNAUTHORIZED}. The message goes only to the log. */
final class UnauthorizedException extends RuntimeException {

    UnauthorizedException(String reason) {
        super(reason);
    }
}
