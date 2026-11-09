package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import static co.edu.corhuila.marketagri.workflow.adapter.out.http.ParticipantAnswers.text;
import static co.edu.corhuila.marketagri.workflow.adapter.out.http.ParticipantAnswers.uuid;

import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.application.port.out.StockReservations;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Step 2 and its compensation over HTTP: catalog's I-03 and I-04. */
public final class HttpStockReservations implements StockReservations {

    private static final Logger log = LoggerFactory.getLogger(HttpStockReservations.class);

    private final ServiceCaller caller;
    private final URI catalogApiUrl;
    private final ObjectMapper json;

    public HttpStockReservations(ServiceCaller caller, URI catalogApiUrl, ObjectMapper json) {
        this.caller = caller;
        this.catalogApiUrl = catalogApiUrl;
        this.json = json;
    }

    @Override
    public StepCall<Reservation> reserve(String idempotencyKey, UUID productId, UUID transactionId,
                                         Quantity quantity) {
        String body = json.createObjectNode()
                .put("transactionId", transactionId.toString())
                .put("quantity", quantity.value())
                .toString();
        Answer answer = caller.call("POST",
                catalogApiUrl.resolve("/internal/v1/catalog/products/" + productId + "/reservations"),
                idempotencyKey, body);
        return ParticipantAnswers.toStepCall(answer, json, view -> new Reservation(uuid(view, "transactionId"),
                uuid(view, "productId"), text(view, "productName"), uuid(view, "producerId"),
                decimal(view, "quantity"), text(view, "unit"), cents(view, "unitPriceCents")));
    }

    /** I-04 answers {@code 204} always (idempotent); no success after the retries means "not released yet". */
    @Override
    public boolean release(UUID transactionId) {
        Answer answer = caller.call("DELETE",
                catalogApiUrl.resolve("/internal/v1/catalog/reservations/" + transactionId), null, null);
        boolean released = answer instanceof Answer.Response response && response.status() / 100 == 2;
        if (!released) {
            log.warn("Reservation of transaction {} not released: {}", transactionId, answer);
        }
        return released;
    }

    private static BigDecimal decimal(JsonNode view, String field) {
        JsonNode value = view.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " is not a number");
        }
        return value.decimalValue().setScale(2, RoundingMode.UNNECESSARY);   // numeric(12,2), exact
    }

    private static long cents(JsonNode view, String field) {
        JsonNode value = view.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " is not an integer of centavos");
        }
        return value.longValue();
    }
}
