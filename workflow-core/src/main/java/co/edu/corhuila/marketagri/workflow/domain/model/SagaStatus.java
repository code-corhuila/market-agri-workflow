package co.edu.corhuila.marketagri.workflow.domain.model;

/** ADR-015 §3: {@code FAILED} means a compensation could not be done and a person decides. */
public enum SagaStatus {
    RUNNING,
    COMPLETED,
    COMPENSATED,
    FAILED
}
