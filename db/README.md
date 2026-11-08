# workflow — database (`db/`)

The state of the checkout saga lives in the schema `workflow` of the single PostgreSQL instance
(ADR-013): the transversal services have no `-db` repository (norm 4.5.1, 5.8.4), so the
migrations live in this folder, with the layout and the rules of a `-db` (Annex A, ADR-011).
The instance and the `workflow_app` login belong to `market-agri-infra`.

```
changelog/changelog-master.yaml   Liquibase entry point
01_ddl/ … 04_tcl/                 schema, tables, foreign keys, indexes, roles and grants
05_rollbacks/                     rollback SQL mirroring every forward change
tests/                            SQL checks of the table rules, run by db-ci (step 3b)
../deploy/compose.yml             workflow-db-migrate, the runner infra includes
../.github/workflows/db-ci.yml    rebuild, isolation, table rules, full rollback, rebuild
```

| Table | Purpose | Main rules |
|---|---|---|
| `saga_instance` | One checkout saga (ADR-015) | one per `(buyer_id, idempotency_key)` (D-C35) and per `transaction_id`, fixed at creation; `status` `RUNNING`/`COMPLETED`/`COMPENSATED`/`FAILED`; `current_step` and `failed_step` among the four steps; `failed_step` never on `COMPLETED`, always on `COMPENSATED` and `FAILED`; `error_code` and `error_message` are what the buyer was answered, `error_detail` is internal only (norm 5.8.3) |
| `saga_step` | Each step, stored before the next one | primary key `(saga_instance_id, step)`; `status` `STARTED`/`DONE`/`REFUSED`/`COMPENSATED`; `attempts` and `compensation_attempts` ≥ 0; `result` is the participant's answer (a JSON object) |
| `processed_event` | Events applied once (ADR-013, ADR-014) | primary key `event_id`; `TransactionConfirmed` or `TransactionFailed`; the saga is empty for an unknown transaction |

`workflow_writer` (granted to `workflow_app`) reads, inserts and updates: no `DELETE`. The partial
index `idx_saga_instance_running` serves the recovery sweep. Migrate from `market-agri-infra`
(`run --rm workflow-db-migrate`) before starting the service; the service never migrates.
