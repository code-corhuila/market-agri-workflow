-- Events already applied (ADR-013, ADR-014): written in the same transaction as the saga change, so
-- a redelivered event changes nothing. An event for an unknown transaction is recorded without saga.
CREATE TABLE workflow.processed_event (
    event_id         uuid        NOT NULL,   -- eventId of the envelope
    event_type       text        NOT NULL,
    saga_instance_id uuid,
    processed_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_event PRIMARY KEY (event_id),
    CONSTRAINT chk_processed_event_event_type CHECK (event_type IN ('TransactionConfirmed', 'TransactionFailed'))
);
