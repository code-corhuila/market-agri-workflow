package co.edu.corhuila.marketagri.workflow.application.port.out;

/**
 * How a participant answered one step, after the adapter's retries (ADR-015 §4). {@code Refused}
 * is an answer with the contract's error code; {@code NoAnswer} means the step may or may not have
 * been done (timeout, network error, or still {@code 5xx}/{@code 429} after the last attempt).
 */
public sealed interface StepCall<T> {

    record Done<T>(T value) implements StepCall<T> {
    }

    record Refused<T>(int status, String code, String message) implements StepCall<T> {

        public Refused {
            code = code == null ? "" : code;
        }
    }

    record NoAnswer<T>(String reason) implements StepCall<T> {
    }
}
