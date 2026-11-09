package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import co.edu.corhuila.marketagri.workflow.application.port.in.StartCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-40 (HU-TXN-001, ADR-015): the buyer starts a purchase from a conversation. */
@RestController
@RequestMapping("/api/v1/workflow/checkouts")
public class CheckoutController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int KEY_MIN = 8;
    private static final int KEY_MAX = 128;

    private final StartCheckout startCheckout;
    private final ObjectMapper json;

    public CheckoutController(StartCheckout startCheckout, ObjectMapper json) {
        this.startCheckout = startCheckout;
        this.json = json;
    }

    /**
     * {@code 201} with {@code Location} to E-46 the first time; {@code 200} with the original body on
     * a replay (D-C35). The body is {@code { transaction, checkoutUrl }} as I-06 answered it.
     */
    @PostMapping
    @RequiredRole("BUYER")
    public ResponseEntity<JsonNode> start(@RequestAttribute(Caller.ATTRIBUTE) Caller caller,
                                          @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                          @Valid @RequestBody StartCheckoutRequest body) {
        if (idempotencyKey.length() < KEY_MIN || idempotencyKey.length() > KEY_MAX) {
            throw new InvalidParameterException(IDEMPOTENCY_KEY,
                    "Debe tener entre " + KEY_MIN + " y " + KEY_MAX + " caracteres.");
        }
        StartCheckout.Checkout checkout = startCheckout.start(new StartCheckout.Command(caller.userId(),
                idempotencyKey, body.conversationId(), body.quantity()));
        JsonNode view = view(checkout.saga().payment().orElseThrow());
        if (!checkout.created()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create("/api/v1/workflow/checkouts/" + checkout.saga().id())).body(view);
    }

    private JsonNode view(PaymentCheckout payment) {
        try {
            ObjectNode view = json.createObjectNode();
            view.set("transaction", json.readTree(payment.transactionView()));
            return view.put("checkoutUrl", payment.checkoutUrl().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the TransactionView of I-06 is not JSON", e);
        }
    }
}
