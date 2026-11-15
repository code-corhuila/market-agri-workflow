-- One checkout saga (ADR-015): who started it with which key, the transaction id fixed at its
-- creation, where it is and, if it ended in an error, what the buyer was answered. The internal
-- detail of a failure stays here and in the log, never in a response (norm 5.8.3).
CREATE TABLE workflow.saga_instance (
    id              uuid          NOT NULL DEFAULT gen_random_uuid(),
    saga_type       text          NOT NULL DEFAULT 'checkout',
    status          text          NOT NULL DEFAULT 'RUNNING',
    buyer_id        uuid          NOT NULL,   -- sub of the token: owner of the key and reader of E-46
    idempotency_key text          NOT NULL,   -- the Idempotency-Key of E-40 (D-C35)
    conversation_id uuid          NOT NULL,
    quantity        numeric(12,2) NOT NULL,
    transaction_id  uuid          NOT NULL,   -- sent to I-03 and I-06; the events carry it
    current_step    text          NOT NULL DEFAULT 'read-conversation',
    failed_step     text,                     -- the step that was refused (E-46)
    error_code      text,                     -- the contract error answered, repeated on a replay
    error_message   text,
    error_detail    text,                     -- participant, status and message: internal only
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_saga_instance PRIMARY KEY (id),
    CONSTRAINT chk_saga_instance_saga_type CHECK (saga_type = 'checkout'),
    CONSTRAINT chk_saga_instance_status CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    CONSTRAINT chk_saga_instance_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT chk_saga_instance_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_saga_instance_current_step
        CHECK (current_step IN ('read-conversation', 'reserve-stock', 'create-payment', 'await-payment')),
    CONSTRAINT chk_saga_instance_failed_step_name
        CHECK (failed_step IN ('read-conversation', 'reserve-stock', 'create-payment', 'await-payment')),
    -- COMPLETED never failed; COMPENSATED and FAILED always say where; RUNNING has one while it compensates
    CONSTRAINT chk_saga_instance_failed_step_by_status CHECK (
        NOT (status = 'COMPLETED' AND failed_step IS NOT NULL)
        AND NOT (status IN ('COMPENSATED', 'FAILED') AND failed_step IS NULL)),
    CONSTRAINT chk_saga_instance_updated_after_creation CHECK (updated_at >= created_at)
);
