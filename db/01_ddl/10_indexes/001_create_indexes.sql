-- Each index names the query or rule it serves (Anexo A, rule 3). The tables are new and empty,
-- so CONCURRENTLY is not needed.

-- E-40 idempotent start: one saga per buyer and key (D-C35); a replay finds the original
CREATE UNIQUE INDEX uq_saga_instance_buyer_key
    ON workflow.saga_instance (buyer_id, idempotency_key);

-- TransactionConfirmed / TransactionFailed find their saga by the transaction id (ADR-015 point 7)
CREATE UNIQUE INDEX uq_saga_instance_transaction_id
    ON workflow.saga_instance (transaction_id);

-- Recovery sweep: RUNNING sagas by their last change (ADR-015 point 6)
CREATE INDEX idx_saga_instance_running
    ON workflow.saga_instance (updated_at) WHERE status = 'RUNNING';

-- Foreign key column (rule 3); saga_step is covered by its primary key
CREATE INDEX idx_processed_event_saga_instance_id
    ON workflow.processed_event (saga_instance_id);
