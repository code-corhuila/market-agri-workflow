package co.edu.corhuila.marketagri.workflow.application.port.out;

import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import java.util.UUID;

/** Step 1: chat's I-05. */
public interface ConversationReader {

    StepCall<Conversation> read(UUID conversationId);
}
