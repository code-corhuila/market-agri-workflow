package co.edu.corhuila.marketagri.workflow.domain.model;

/** The errors of E-40 that belong to the checkout (rows 2–12 of the card), named as the contract names them. */
public enum CheckoutError {
    QUANTITY_NOT_POSITIVE,
    CHECKOUT_IN_PROGRESS,
    CHAT_UNAVAILABLE,
    CONVERSATION_NOT_FOUND,
    NOT_CONVERSATION_BUYER,
    PURCHASE_METHOD_NOT_PLATFORM,
    CATALOG_UNAVAILABLE,
    PRODUCT_NOT_AVAILABLE,
    INSUFFICIENT_STOCK,
    TRANSACTIONS_UNAVAILABLE,
    TRANSACTION_IN_PROGRESS,
    PAYMENT_GATEWAY_ERROR
}
