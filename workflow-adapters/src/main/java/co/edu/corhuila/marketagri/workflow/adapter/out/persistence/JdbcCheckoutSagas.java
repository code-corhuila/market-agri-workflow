package co.edu.corhuila.marketagri.workflow.adapter.out.persistence;

import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutError;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutFailure;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutRequest;
import co.edu.corhuila.marketagri.workflow.domain.model.CheckoutSaga;
import co.edu.corhuila.marketagri.workflow.domain.model.Conversation;
import co.edu.corhuila.marketagri.workflow.domain.model.PaymentCheckout;
import co.edu.corhuila.marketagri.workflow.domain.model.Quantity;
import co.edu.corhuila.marketagri.workflow.domain.model.Reservation;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStatus;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStep;
import co.edu.corhuila.marketagri.workflow.domain.model.StepRecord;
import co.edu.corhuila.marketagri.workflow.domain.model.StepStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code saga_instance} and {@code saga_step} (ADR-013, ADR-015 §3). A save writes the saga and
 * every step it has in one transaction, so a crash never leaves a step without its saga's state.
 * Each step's answer is its {@code result}: the conversation, the reservation, or the opened
 * checkout with the {@code TransactionView} as transactions wrote it.
 */
public class JdbcCheckoutSagas implements CheckoutSagas {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcCheckoutSagas.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final TransactionTemplate snapshot;

    public JdbcCheckoutSagas(JdbcClient jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<CheckoutSaga> findByKey(UUID buyerId, String idempotencyKey) {
        return snapshot.execute(tx -> jdbc.sql("""
                        SELECT id FROM saga_instance WHERE buyer_id = :buyer AND idempotency_key = :key""")
                .param("buyer", buyerId).param("key", idempotencyKey).query(UUID.class).optional().flatMap(this::load));
    }

    @Override
    public Optional<CheckoutSaga> findById(UUID id) {
        return snapshot.execute(tx -> load(id));
    }

    @Override
    public CheckoutSaga runLocked(UUID id, Consumer<CheckoutSaga> action) {
        return transaction.execute(tx -> {
            jdbc.sql("SELECT id FROM saga_instance WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).single();
            CheckoutSaga saga = load(id).orElseThrow();
            action.accept(saga);
            return saga;
        });
    }

    public List<UUID> recoverable(Instant cutoff) {
        return jdbc.sql("""
                        SELECT id FROM saga_instance WHERE status = 'RUNNING' AND updated_at < :cutoff
                          AND (current_step <> 'await-payment' OR failed_step IS NOT NULL)
                        ORDER BY updated_at, id""")
                .param("cutoff", Timestamp.from(cutoff)).query(UUID.class).list();
    }

    public void applyPaymentEvent(UUID event, String type, UUID transactionId, Consumer<CheckoutSaga> action) {
        transaction.executeWithoutResult(tx -> {
            Optional<UUID> id = jdbc.sql("SELECT id FROM saga_instance WHERE transaction_id = :id FOR UPDATE")
                    .param("id", transactionId).query(UUID.class).optional();
            int inserted = jdbc.sql("""
                            INSERT INTO processed_event (event_id, event_type, saga_instance_id)
                            VALUES (:event, :type, :saga) ON CONFLICT (event_id) DO NOTHING""")
                    .param("event", event).param("type", type).param("saga", id.orElse(null)).update();
            if (inserted == 1) {
                id.flatMap(this::load).ifPresentOrElse(action,
                        () -> LOG.warn("Payment event {} has no saga for transaction {}", event, transactionId));
            }
        });
    }

    private Optional<CheckoutSaga> load(UUID id) {
        Map<SagaStep, StepRecord> steps = new EnumMap<>(SagaStep.class);
        Map<SagaStep, String> results = new EnumMap<>(SagaStep.class);
        jdbc.sql("SELECT * FROM saga_step WHERE saga_instance_id = :id").param("id", id).query((row, n) -> {
            SagaStep name = step(row.getString("step"));
            steps.put(name, new StepRecord(StepStatus.valueOf(row.getString("status")), row.getInt("attempts"),
                    row.getInt("compensation_attempts"), instant(row, "started_at"), instant(row, "updated_at")));
            results.put(name, row.getString("result"));
            return name;
        }).list();
        return jdbc.sql("SELECT * FROM saga_instance WHERE id = :id").param("id", id)
                .query((row, n) -> restore(row, steps, results)).optional();
    }

    /** The unique index on (buyer_id, idempotency_key) decides a race between two first requests. */
    @Override
    public boolean create(CheckoutSaga saga) {
        CheckoutRequest request = saga.request();
        int inserted = jdbc.sql("""
                        INSERT INTO saga_instance (id, status, buyer_id, idempotency_key, conversation_id, quantity,
                                                   transaction_id, current_step, created_at, updated_at)
                        VALUES (:id, :status, :buyer, :key, :conversation, :quantity, :transaction, :step,
                                :created, :updated)
                        ON CONFLICT (buyer_id, idempotency_key) DO NOTHING""")
                .param("id", saga.id()).param("status", saga.status().name())
                .param("buyer", request.buyerId()).param("key", request.idempotencyKey())
                .param("conversation", request.conversationId()).param("quantity", request.quantity().value())
                .param("transaction", saga.transactionId()).param("step", saga.currentStep().wireName())
                .param("created", Timestamp.from(saga.createdAt())).param("updated", Timestamp.from(saga.updatedAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public void save(CheckoutSaga saga) {
        transaction.executeWithoutResult(status -> {
            Optional<CheckoutFailure> failure = saga.failure();
            jdbc.sql("""
                            UPDATE saga_instance
                               SET status = :status, current_step = :step, failed_step = :failed,
                                   error_code = :code, error_message = :message, error_detail = :detail,
                                   updated_at = :updated
                             WHERE id = :id""")
                    .param("status", saga.status().name()).param("step", saga.currentStep().wireName())
                    .param("failed", saga.failedStep().map(SagaStep::wireName).orElse(null))
                    .param("code", failure.map(f -> f.error().name()).orElse(null))
                    .param("message", failure.map(CheckoutFailure::message).orElse(null))
                    .param("detail", failure.map(CheckoutFailure::detail).orElse(null))
                    .param("updated", Timestamp.from(saga.updatedAt())).param("id", saga.id())
                    .update();
            for (SagaStep step : SagaStep.values()) {
                saga.step(step).ifPresent(record -> saveStep(saga, step, record));
            }
        });
    }

    private void saveStep(CheckoutSaga saga, SagaStep step, StepRecord record) {
        jdbc.sql("""
                        INSERT INTO saga_step (saga_instance_id, step, status, attempts, compensation_attempts, result,
                                               started_at, updated_at)
                        VALUES (:saga, :step, :status, :attempts, :compensations, CAST(:result AS jsonb), :started,
                                :updated)
                        ON CONFLICT (saga_instance_id, step) DO UPDATE
                           SET status = EXCLUDED.status, attempts = EXCLUDED.attempts,
                               compensation_attempts = EXCLUDED.compensation_attempts,
                               result = EXCLUDED.result, updated_at = EXCLUDED.updated_at""")
                .param("saga", saga.id()).param("step", step.wireName()).param("status", record.status().name())
                .param("attempts", record.attempts()).param("compensations", record.compensationAttempts())
                .param("result", result(saga, step)).param("started", Timestamp.from(record.startedAt()))
                .param("updated", Timestamp.from(record.updatedAt()))
                .update();
    }

    private String result(CheckoutSaga saga, SagaStep step) {
        Object answer = switch (step) {
            case READ_CONVERSATION -> saga.conversation();
            case RESERVE_STOCK -> saga.reservation();
            case CREATE_PAYMENT -> saga.payment().map(this::checkout).orElse(null);
            case AWAIT_PAYMENT -> null;
        };
        try {
            return answer == null ? null : json.writeValueAsString(answer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot store the answer of " + step.wireName(), e);
        }
    }

    private JsonNode checkout(PaymentCheckout payment) {
        try {
            ObjectNode node = json.createObjectNode();
            node.set("transaction", json.readTree(payment.transactionView()));
            return node.put("checkoutUrl", payment.checkoutUrl().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the TransactionView of I-06 is not JSON", e);
        }
    }

    private CheckoutSaga restore(ResultSet row, Map<SagaStep, StepRecord> steps, Map<SagaStep, String> results)
            throws SQLException {
        String code = row.getString("error_code");
        String failed = row.getString("failed_step");
        return CheckoutSaga.restore(row.getObject("id", UUID.class), row.getObject("transaction_id", UUID.class),
                new CheckoutRequest(row.getObject("buyer_id", UUID.class), row.getString("idempotency_key"),
                        row.getObject("conversation_id", UUID.class), new Quantity(row.getBigDecimal("quantity"))),
                SagaStatus.valueOf(row.getString("status")), step(row.getString("current_step")),
                failed == null ? null : step(failed),
                code == null ? null : new CheckoutFailure(CheckoutError.valueOf(code), row.getString("error_message"),
                        row.getString("error_detail")),
                steps, read(results.get(SagaStep.READ_CONVERSATION), Conversation.class),
                read(results.get(SagaStep.RESERVE_STOCK), Reservation.class),
                payment(results.get(SagaStep.CREATE_PAYMENT)), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private <T> T read(String result, Class<T> type) {
        try {
            return result == null ? null : json.readValue(result, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored answer is not a " + type.getSimpleName(), e);
        }
    }

    private PaymentCheckout payment(String result) {
        JsonNode node = read(result, JsonNode.class);
        return node == null ? null
                : new PaymentCheckout(node.get("transaction").toString(), URI.create(node.get("checkoutUrl").asText()));
    }

    private static SagaStep step(String wireName) {
        return Arrays.stream(SagaStep.values()).filter(s -> s.wireName().equals(wireName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown step in the database: " + wireName));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }
}
