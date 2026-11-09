package co.edu.corhuila.marketagri.workflow.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much the buyer wants: greater than zero (E-40 row 2), at most two decimals and at most what
 * {@code numeric(12,2)} holds (row 1). Kept with scale 2, so a resumed step sends the same value.
 */
public record Quantity(BigDecimal value) {

    private static final int SCALE = 2;
    private static final BigDecimal MAX = new BigDecimal("9999999999.99");

    public Quantity {
        if (value.signum() <= 0) {
            throw new CheckoutRejectedException(new CheckoutFailure(
                    CheckoutError.QUANTITY_NOT_POSITIVE, null, "quantity is not positive: " + value));
        }
        if (value.stripTrailingZeros().scale() > SCALE || value.compareTo(MAX) > 0) {
            throw new DomainException("VALIDATION_ERROR", "quantity out of numeric(12,2): " + value);
        }
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
