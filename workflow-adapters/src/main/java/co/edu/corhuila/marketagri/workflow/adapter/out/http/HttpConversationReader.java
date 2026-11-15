package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import static co.edu.corhuila.marketagri.workflow.adapter.out.http.ParticipantAnswers.text;
import static co.edu.corhuila.marketagri.workflow.adapter.out.http.ParticipantAnswers.uuid;

import co.edu.corhuila.marketagri.workflow.application.port.out.ConversationReader;
import co.edu.corhuila.marketagri.workflow.application.port.out.StepCall;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PurchaseMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;

/** Step 1 over HTTP: chat's I-05 {@code GET /internal/v1/chat/conversations/{id}}. */
public final class HttpConversationReader implements ConversationReader {

    private final ServiceCaller caller;
    private final URI chatApiUrl;
    private final ObjectMapper json;

    public HttpConversationReader(ServiceCaller caller, URI chatApiUrl, ObjectMapper json) {
        this.caller = caller;
        this.chatApiUrl = chatApiUrl;
        this.json = json;
    }

    @Override
    public StepCall<Conversation> read(UUID conversationId) {
        Answer answer = caller.call("GET", chatApiUrl.resolve("/internal/v1/chat/conversations/" + conversationId),
                null, null);
        return ParticipantAnswers.toStepCall(answer, json, view -> new Conversation(uuid(view, "id"),
                uuid(view, "productId"), text(view, "productName"), uuid(view, "buyerId"), text(view, "buyerName"),
                uuid(view, "producerId"), text(view, "producerName"), purchaseMethod(view)));
    }

    private static PurchaseMethod purchaseMethod(JsonNode view) {
        JsonNode method = view.get("agreedPurchaseMethod");
        return method == null || method.isNull() ? null : PurchaseMethod.valueOf(method.asText());
    }
}
