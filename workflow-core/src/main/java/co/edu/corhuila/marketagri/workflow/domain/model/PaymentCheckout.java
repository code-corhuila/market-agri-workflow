package co.edu.corhuila.marketagri.workflow.domain.model;

import java.net.URI;

/**
 * I-06's answer, relayed as E-40's body. {@code transactionView} is the {@code TransactionView}
 * JSON exactly as transactions wrote it: the workflow does not interpret another domain's view.
 */
public record PaymentCheckout(String transactionView, URI checkoutUrl) {
}
