package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** The rows of E-40 (api-contract §4.4, ADR-015), with their status and Spanish message. */
enum DomainError {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.message()),
    QUANTITY_NOT_POSITIVE(HttpStatus.UNPROCESSABLE_ENTITY, "La cantidad debe ser mayor que cero."),
    CHECKOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "La compra no existe."),
    CHECKOUT_IN_PROGRESS(HttpStatus.CONFLICT, "La compra se está procesando. Intente de nuevo en unos segundos."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "El chat no está disponible. Intente de nuevo."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "La conversación no existe."),
    NOT_CONVERSATION_BUYER(HttpStatus.FORBIDDEN, "Solo el comprador de la conversación puede iniciar el pago."),
    PURCHASE_METHOD_NOT_PLATFORM(HttpStatus.CONFLICT, "La forma de compra acordada no es por la plataforma."),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "El catálogo no está disponible. Intente de nuevo."),
    PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT, "El producto no está disponible para la venta."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Cantidad insuficiente para la compra."),
    TRANSACTIONS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de pagos no está disponible. Intente de nuevo."),
    TRANSACTION_IN_PROGRESS(HttpStatus.CONFLICT, "Ya hay un pago en curso para esta conversación."),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "No fue posible iniciar el pago. Intente de nuevo.");

    private final HttpStatus status;
    private final String message;

    DomainError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    HttpStatus status() {
        return status;
    }

    String message() {
        return message;
    }

    static Optional<DomainError> of(String code) {
        return Arrays.stream(values()).filter(error -> error.name().equals(code)).findFirst();
    }
}
