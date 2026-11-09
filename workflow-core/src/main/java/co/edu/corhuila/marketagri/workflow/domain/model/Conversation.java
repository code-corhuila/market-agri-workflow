package co.edu.corhuila.marketagri.workflow.domain.model;

import java.util.UUID;

/** What step 1 needs from I-05's {@code ConversationView}; {@code agreedPurchaseMethod} may be {@code null}. */
public record Conversation(UUID id, UUID productId, String productName, UUID buyerId, String buyerName,
                           UUID producerId, String producerName, PurchaseMethod agreedPurchaseMethod) {

    public boolean agreedOnPlatform() {
        return agreedPurchaseMethod == PurchaseMethod.PLATFORM;
    }
}
