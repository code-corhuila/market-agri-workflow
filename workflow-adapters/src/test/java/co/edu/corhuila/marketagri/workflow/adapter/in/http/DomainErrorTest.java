package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Every error the saga can answer has its row of E-40, with the card's status. */
class DomainErrorTest {

    @ParameterizedTest
    @EnumSource(CheckoutError.class)
    void everyCheckoutErrorHasARow(CheckoutError error) {
        assertThat(DomainError.of(error.name())).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"QUANTITY_NOT_POSITIVE, 422", "CHECKOUT_IN_PROGRESS, 409", "CHAT_UNAVAILABLE, 503",
            "CONVERSATION_NOT_FOUND, 404", "NOT_CONVERSATION_BUYER, 403", "PURCHASE_METHOD_NOT_PLATFORM, 409",
            "CATALOG_UNAVAILABLE, 503", "PRODUCT_NOT_AVAILABLE, 409", "INSUFFICIENT_STOCK, 409",
            "TRANSACTIONS_UNAVAILABLE, 503", "TRANSACTION_IN_PROGRESS, 409", "PAYMENT_GATEWAY_ERROR, 502"})
    void theStatusIsTheOneOfTheCard(String code, int status) {
        assertThat(DomainError.of(code).orElseThrow().status().value()).isEqualTo(status);
    }

    @Test
    void anUnknownCodeHasNoRow() {
        assertThat(DomainError.of("SOMETHING_ELSE")).isEmpty();
    }
}
