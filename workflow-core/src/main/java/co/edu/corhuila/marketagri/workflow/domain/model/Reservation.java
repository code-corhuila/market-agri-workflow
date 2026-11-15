package co.edu.corhuila.marketagri.workflow.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/** I-03's snapshot that step 3 sends to I-06; the unit travels as catalog wrote it (no rule here). */
public record Reservation(UUID transactionId, UUID productId, String productName, UUID producerId,
                          BigDecimal quantity, String unit, long unitPriceCents) {
}
