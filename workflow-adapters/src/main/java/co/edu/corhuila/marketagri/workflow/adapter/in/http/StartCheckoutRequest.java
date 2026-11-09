package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of E-40: no price, amount, product or producer (they come from the conversation and the
 * reservation). A quantity of zero or less is not rejected here: it is row 2, a {@code 422} of the
 * core, after the {@code 400} of row 1.
 */
public record StartCheckoutRequest(
        @NotNull(message = "Campo obligatorio.") UUID conversationId,
        @NotNull(message = "Campo obligatorio.")
        @Digits(integer = 10, fraction = 2, message = "Debe ser un número con máximo dos decimales.")
        BigDecimal quantity) {
}
