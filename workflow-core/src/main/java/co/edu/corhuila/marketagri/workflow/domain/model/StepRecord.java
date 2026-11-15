package co.edu.corhuila.marketagri.workflow.domain.model;

import java.time.Instant;

/** One row of {@code saga_step}: where the step is and how many times it was tried and undone. */
public record StepRecord(StepStatus status, int attempts, int compensationAttempts, Instant startedAt,
                         Instant updatedAt) {

    static StepRecord started(Instant now) {
        return new StepRecord(StepStatus.STARTED, 1, 0, now, now);
    }

    StepRecord restarted(Instant now) {
        return new StepRecord(StepStatus.STARTED, attempts + 1, compensationAttempts, startedAt, now);
    }

    StepRecord with(StepStatus newStatus, Instant now) {
        return new StepRecord(newStatus, attempts, compensationAttempts, startedAt, now);
    }

    StepRecord withCompensationAttempt(Instant now) {
        return new StepRecord(status, attempts, compensationAttempts + 1, startedAt, now);
    }
}
