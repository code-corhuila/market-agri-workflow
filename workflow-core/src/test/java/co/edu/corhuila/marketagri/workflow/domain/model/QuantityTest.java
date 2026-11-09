package co.edu.corhuila.marketagri.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuantityTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1"})
    void zeroOrLessIsRow2QuantityNotPositive(String value) {
        assertThatThrownBy(() -> new Quantity(new BigDecimal(value)))
                .isInstanceOfSatisfying(CheckoutRejectedException.class, e -> {
                    assertThat(e.code()).isEqualTo("QUANTITY_NOT_POSITIVE");
                    assertThat(e.failure().message()).isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234", "10000000000.00"})
    void moreThanTwoDecimalsOrAboveNumeric12Of2IsValidationError(String value) {
        assertThatThrownBy(() -> new Quantity(new BigDecimal(value)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"))
                .isNotInstanceOf(CheckoutRejectedException.class);
    }

    @Test
    void keepsScaleTwoSoFiveAndFivePointZeroAreTheSameQuantity() {
        assertThat(new Quantity(new BigDecimal("5"))).isEqualTo(new Quantity(new BigDecimal("5.00")));
        assertThat(new Quantity(new BigDecimal("12.5")).value().toPlainString()).isEqualTo("12.50");
        assertThat(new Quantity(new BigDecimal("9999999999.99")).value()).isEqualByComparingTo("9999999999.99");
    }
}
