package co.edu.corhuila.marketagri.workflow.domain.model;

/** A broken rule with its contract code; the HTTP adapter gives the status and the Spanish text. */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
