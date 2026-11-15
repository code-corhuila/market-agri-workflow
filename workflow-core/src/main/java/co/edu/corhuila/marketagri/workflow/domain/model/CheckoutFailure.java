package co.edu.corhuila.marketagri.workflow.domain.model;

import java.util.Objects;

/**
 * Why a checkout failed. {@code message} is set only when the contract's text carries values
 * ({@code INSUFFICIENT_STOCK}), so a replay repeats it. {@code detail} (participant, status, code)
 * goes to the log and {@code error_detail}, never to a response (norm §5.8.3).
 */
public record CheckoutFailure(CheckoutError error, String message, String detail) {

    public CheckoutFailure {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(detail, "detail");
    }
}
