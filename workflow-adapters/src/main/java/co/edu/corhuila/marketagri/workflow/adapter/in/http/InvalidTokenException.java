package co.edu.corhuila.marketagri.workflow.adapter.in.http;

/** A token that fails one of the checks of authentication.md §3; the message says which (logs only). */
public final class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String reason) {
        super(reason);
    }
}
