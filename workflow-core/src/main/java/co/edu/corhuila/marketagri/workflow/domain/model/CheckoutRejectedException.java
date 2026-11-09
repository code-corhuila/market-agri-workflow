package co.edu.corhuila.marketagri.workflow.domain.model;

/** E-40 answers one of its error rows: the code, and the message when it carries values. */
public class CheckoutRejectedException extends DomainException {

    private final transient CheckoutFailure failure;

    public CheckoutRejectedException(CheckoutFailure failure) {
        super(failure.error().name(), failure.detail());
        this.failure = failure;
    }

    public CheckoutFailure failure() {
        return failure;
    }
}
