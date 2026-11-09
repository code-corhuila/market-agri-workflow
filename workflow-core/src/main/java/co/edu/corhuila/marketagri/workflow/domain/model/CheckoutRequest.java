package co.edu.corhuila.marketagri.workflow.domain.model;

import java.util.UUID;

/** What the buyer asked in E-40: the owner and the key of a replay (D-C35), the conversation and the quantity. */
public record CheckoutRequest(UUID buyerId, String idempotencyKey, UUID conversationId, Quantity quantity) {
}
