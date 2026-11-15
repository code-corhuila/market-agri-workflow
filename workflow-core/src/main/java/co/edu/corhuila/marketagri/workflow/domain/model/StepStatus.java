package co.edu.corhuila.marketagri.workflow.domain.model;

/** {@code STARTED}: called, no answer stored yet (the recovery sweep resumes it). */
public enum StepStatus {
    STARTED,
    DONE,
    REFUSED,
    COMPENSATED
}
