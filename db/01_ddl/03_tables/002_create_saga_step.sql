-- Each step of a saga, stored before the next one starts (norm 5.8; Annex E, rule 2): its status,
-- the attempts of its call and of its compensation, and the participant's answer, which feeds the
-- next step or the compensation. The saga never re-validates a domain rule from it.
CREATE TABLE workflow.saga_step (
    saga_instance_id      uuid        NOT NULL,
    step                  text        NOT NULL,
    status                text        NOT NULL DEFAULT 'STARTED',
    attempts              integer     NOT NULL DEFAULT 0,
    compensation_attempts integer     NOT NULL DEFAULT 0,
    result                jsonb,
    started_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_saga_step PRIMARY KEY (saga_instance_id, step),
    CONSTRAINT chk_saga_step_step
        CHECK (step IN ('read-conversation', 'reserve-stock', 'create-payment', 'await-payment')),
    -- STARTED: called, no answer yet (the recovery sweep resumes it); REFUSED: a 4xx, or no answer
    -- after the retries; COMPENSATED: its compensation was done
    CONSTRAINT chk_saga_step_status CHECK (status IN ('STARTED', 'DONE', 'REFUSED', 'COMPENSATED')),
    CONSTRAINT chk_saga_step_attempts CHECK (attempts >= 0 AND compensation_attempts >= 0),
    CONSTRAINT chk_saga_step_result_object CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);
