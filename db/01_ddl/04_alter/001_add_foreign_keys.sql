-- Foreign keys inside the workflow schema only (ids of other domains are bare uuids, ADR-010).
-- workflow_app has no DELETE, so RESTRICT makes an accidental physical delete of a saga fail.
ALTER TABLE workflow.saga_step
    ADD CONSTRAINT fk_saga_step_saga_instance
    FOREIGN KEY (saga_instance_id) REFERENCES workflow.saga_instance (id) ON DELETE RESTRICT;

ALTER TABLE workflow.processed_event
    ADD CONSTRAINT fk_processed_event_saga_instance
    FOREIGN KEY (saga_instance_id) REFERENCES workflow.saga_instance (id) ON DELETE RESTRICT;
