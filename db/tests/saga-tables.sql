-- HU-TXN-001 · E2a: the table rules of the workflow schema (ADR-013, ADR-015; api-contract E-40,
-- E-46, D-C35). Run by db-ci after the first update; everything is rolled back at the end.
\set ON_ERROR_STOP on
BEGIN;

-- Fails the run unless the statement is refused by exactly the expected constraint.
CREATE FUNCTION pg_temp.expect_refused(stmt text, expected text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    got text;
BEGIN
    EXECUTE stmt;
    RAISE EXCEPTION 'FAIL: accepted, % should refuse it: %', expected, stmt;
EXCEPTION WHEN check_violation OR unique_violation OR foreign_key_violation THEN
    GET STACKED DIAGNOSTICS got = CONSTRAINT_NAME;
    IF got IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'FAIL: refused by %, expected %: %', got, expected, stmt;
    END IF;
    RAISE NOTICE 'ok: % refuses it', expected;
END $$;

CREATE FUNCTION pg_temp.expect(ok boolean, what text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL: %', what;
    END IF;
    RAISE NOTICE 'ok: %', what;
END $$;

-- A valid saga insert; any column can be overridden by replacing its value.
CREATE FUNCTION pg_temp.saga_insert(idempotency_key text,
        buyer_id uuid DEFAULT 'b0000000-0000-4000-8000-000000000001',
        transaction_id uuid DEFAULT gen_random_uuid(), quantity numeric DEFAULT 12.5,
        status text DEFAULT 'RUNNING', current_step text DEFAULT 'read-conversation',
        failed_step text DEFAULT NULL)
RETURNS text LANGUAGE sql AS $$
    SELECT format('INSERT INTO workflow.saga_instance (buyer_id, idempotency_key, conversation_id, quantity,
                       transaction_id, status, current_step, failed_step)
                   VALUES (%L, %L, gen_random_uuid(), %L, %L, %L, %L, %L)',
                  buyer_id, idempotency_key, quantity, transaction_id, status, current_step, failed_step);
$$;

-- 1. A new checkout saga starts RUNNING in its first step, with its own id (ADR-015 points 1–3).
DO $$ BEGIN EXECUTE pg_temp.saga_insert('compra-0001',
        transaction_id => 'a0000000-0000-4000-8000-000000000001'); END $$;
SELECT pg_temp.expect(
    (SELECT id IS NOT NULL AND saga_type = 'checkout' AND status = 'RUNNING'
            AND current_step = 'read-conversation' AND failed_step IS NULL
            AND created_at IS NOT NULL AND updated_at IS NOT NULL
     FROM workflow.saga_instance WHERE idempotency_key = 'compra-0001'),
    'a new saga is a RUNNING checkout in read-conversation');

-- 2. Idempotent start (D-C35, norm 5.8): one saga per buyer and key; another buyer may reuse it.
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0001'), 'uq_saga_instance_buyer_key');
DO $$ BEGIN EXECUTE pg_temp.saga_insert('compra-0001', buyer_id => 'b0000000-0000-4000-8000-000000000002'); END $$;
SELECT pg_temp.expect_refused(pg_temp.saga_insert('corta'), 'chk_saga_instance_idempotency_key_length');
SELECT pg_temp.expect_refused(pg_temp.saga_insert(repeat('k', 129)), 'chk_saga_instance_idempotency_key_length');

-- 3. One saga per transaction id: the events find their saga by it (ADR-015 points 2 and 7).
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0002',
       transaction_id => 'a0000000-0000-4000-8000-000000000001'), 'uq_saga_instance_transaction_id');

-- 4. Closed vocabularies: the four statuses and the four steps of ADR-015; a positive quantity.
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0003', status => 'PENDING'), 'chk_saga_instance_status');
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0003', current_step => 'charge-payment'),
       'chk_saga_instance_current_step');
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0003', status => 'COMPENSATED',
       failed_step => 'pay'), 'chk_saga_instance_failed_step_name');
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0003', quantity => 0), 'chk_saga_instance_quantity_positive');

-- 5. failedStep (E-46): a completed saga has none; a compensated or failed one always has one;
--    a running one may have it while it compensates.
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0004', status => 'COMPLETED', failed_step => 'await-payment'),
       'chk_saga_instance_failed_step_by_status');
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0004', status => 'COMPENSATED'),
       'chk_saga_instance_failed_step_by_status');
SELECT pg_temp.expect_refused(pg_temp.saga_insert('compra-0004', status => 'FAILED'),
       'chk_saga_instance_failed_step_by_status');
DO $$ BEGIN EXECUTE pg_temp.saga_insert('compra-0004', status => 'RUNNING', current_step => 'reserve-stock',
        failed_step => 'create-payment'); END $$;
DO $$ BEGIN EXECUTE pg_temp.saga_insert('compra-0005', status => 'COMPENSATED', current_step => 'await-payment',
        failed_step => 'await-payment'); END $$;

-- 6. Steps: one row per step of a saga, stored before the next one starts (norm 5.8).
INSERT INTO workflow.saga_step (saga_instance_id, step, status, attempts, result)
SELECT id, 'reserve-stock', 'DONE', 1, '{"unitPriceCents": 680000}'::jsonb
FROM workflow.saga_instance WHERE idempotency_key = 'compra-0001' AND transaction_id = 'a0000000-0000-4000-8000-000000000001';
SELECT pg_temp.expect(
    (SELECT compensation_attempts = 0 AND started_at IS NOT NULL AND updated_at IS NOT NULL FROM workflow.saga_step
     WHERE step = 'reserve-stock'),
    'a step keeps its attempts, its result and when it changed');
CREATE FUNCTION pg_temp.step_insert(step text, status text DEFAULT 'STARTED', attempts int DEFAULT 0,
        result text DEFAULT NULL, saga uuid DEFAULT NULL)
RETURNS text LANGUAGE sql AS $$
    SELECT format('INSERT INTO workflow.saga_step (saga_instance_id, step, status, attempts, result)
                   VALUES (%L, %L, %L, %L, %L::jsonb)',
                  coalesce(saga, (SELECT id FROM workflow.saga_instance
                                  WHERE transaction_id = 'a0000000-0000-4000-8000-000000000001')),
                  step, status, attempts, result);
$$;
SELECT pg_temp.expect_refused(pg_temp.step_insert('reserve-stock'), 'pk_saga_step');
SELECT pg_temp.expect_refused(pg_temp.step_insert('release-stock'), 'chk_saga_step_step');
SELECT pg_temp.expect_refused(pg_temp.step_insert('create-payment', status => 'OK'), 'chk_saga_step_status');
SELECT pg_temp.expect_refused(pg_temp.step_insert('create-payment', attempts => -1), 'chk_saga_step_attempts');
SELECT pg_temp.expect_refused(pg_temp.step_insert('create-payment', result => '[1, 2]'), 'chk_saga_step_result_object');
SELECT pg_temp.expect_refused(pg_temp.step_insert('create-payment', saga => gen_random_uuid()),
       'fk_saga_step_saga_instance');

-- 7. Consumed events (ADR-013, ADR-014): each one applied once; an event for an unknown
--    transaction is recorded without a saga.
INSERT INTO workflow.processed_event (event_id, event_type, saga_instance_id)
SELECT 'e0000000-0000-4000-8000-000000000001', 'TransactionConfirmed', id
FROM workflow.saga_instance WHERE transaction_id = 'a0000000-0000-4000-8000-000000000001';
INSERT INTO workflow.processed_event (event_id, event_type)
VALUES ('e0000000-0000-4000-8000-000000000002', 'TransactionFailed');
SELECT pg_temp.expect_refused(
       'INSERT INTO workflow.processed_event (event_id, event_type) VALUES (''e0000000-0000-4000-8000-000000000001'', ''TransactionFailed'')',
       'pk_processed_event');
SELECT pg_temp.expect_refused(
       'INSERT INTO workflow.processed_event (event_id, event_type) VALUES (gen_random_uuid(), ''NewChatMessage'')',
       'chk_processed_event_event_type');
SELECT pg_temp.expect_refused(
       'INSERT INTO workflow.processed_event (event_id, event_type, saga_instance_id) VALUES (gen_random_uuid(), ''TransactionFailed'', gen_random_uuid())',
       'fk_processed_event_saga_instance');

-- 8. The recovery sweep reads the RUNNING sagas by their last change (ADR-015 point 6).
SELECT pg_temp.expect(
    (SELECT indexdef LIKE '%(updated_at)%WHERE (status = ''RUNNING''::text)%'
     FROM pg_indexes WHERE schemaname = 'workflow' AND indexname = 'idx_saga_instance_running'),
    'a partial index serves the recovery sweep');

-- 9. workflow_app may change rows but never delete them (deletes run as the migration user).
SELECT pg_temp.expect(
    has_table_privilege('workflow_app', 'workflow.saga_instance', 'SELECT, INSERT, UPDATE')
    AND NOT has_table_privilege('workflow_app', 'workflow.saga_instance', 'DELETE')
    AND NOT has_table_privilege('workflow_app', 'workflow.saga_step', 'DELETE')
    AND NOT has_table_privilege('workflow_app', 'workflow.processed_event', 'DELETE'),
    'workflow_app reads, inserts and updates, without DELETE');

ROLLBACK;
\echo 'saga-tables: all checks passed'
