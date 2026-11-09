package co.edu.corhuila.marketagri.workflow.domain.model;

/** The steps of the checkout saga, in the order they run (ADR-015 §1). */
public enum SagaStep {
    READ_CONVERSATION("read-conversation"),
    RESERVE_STOCK("reserve-stock"),
    CREATE_PAYMENT("create-payment"),
    AWAIT_PAYMENT("await-payment");

    private final String wireName;

    SagaStep(String wireName) {
        this.wireName = wireName;
    }

    /** The name in {@code saga_step}, in E-46 and in the step's {@code Idempotency-Key}. */
    public String wireName() {
        return wireName;
    }

    SagaStep next() {
        return values()[ordinal() + 1];
    }
}
