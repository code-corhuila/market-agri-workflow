# market-agri-workflow

Transversal **checkout saga** orchestrator (ADR-015) of the **Marketplace Agrícola Huila**
distributed system: Java 21, Spring Boot 3.5, hexagonal architecture in three Maven modules
(Anexo C). It will run the purchase across chat, catalog and transactions (E-40) and expose the
state of each saga (E-46). Contract: `market-agri-docs/07-api/api-contract.md` §4.6.

## Modules

```
workflow-core/      domain/model, application/port/{in,out}, application/usecase — plain Java, no framework
workflow-adapters/  adapter/in/http, adapter/out/persistence
workflow-app/       composition root: entry point, wiring and every limit (application.yml)
db/                 the workflow schema with Liquibase, as a -db repository (ADR-013): see db/README.md
deploy/             Dockerfile and compose.yml (service and workflow-db-migrate), included by market-agri-infra
```

`workflow-core` declares no framework: a Spring or JDBC type there does not compile. The service
connects as `workflow_app` and never migrates: `workflow-db-migrate` does it, from infra.

## What the scaffold already does

| Rule | Where |
|---|---|
| `GET /health` → `200 {"status":"UP"}` without token (liveness, E-60) | `adapter/in/http/HealthController` |
| `GET /health/ready` → `200`/`503` with `checks.database` (E-61; `broker` joins with the event consumer) | `adapter/in/http/HealthController`, `JdbcDatabaseCheck` |
| One error body `{error, message, details, traceId}` from **one** handler (D-C21) | `adapter/in/http/ErrorHandler` |
| `X-Correlation-Id` reused or generated, returned, as `traceId` and in every JSON log line (D-C28) | `adapter/in/http/CorrelationFilter` |
| Token of auth validated locally with `JWT_PUBLIC_KEY` (RS256 only, `iss`, `exp` with 30 s of skew, role); `SERVICE` only on `/internal/**`, `@RequiredRole` per route (ADR-020, D-C27) | `adapter/in/http/AuthInterceptor`, `Rs256Verifier` |

## Run locally

```bash
./mvnw -B verify                      # build, unit and HTTP tests (Windows: mvnw.cmd)
cp .env.example .env                  # DATABASE_* of a database where db/ is migrated
set -a && . ./.env && set +a && java -jar workflow-app/target/workflow-app-0.0.1-SNAPSHOT.jar
curl -i http://localhost:8080/health/ready
```

## Integration tests

They run against a real PostgreSQL with the schema of `db/`, reached through `TEST_DATABASE_URL`
(Anexo C), and are skipped when it is not set. With Docker:

```bash
export "$(bash scripts/test-database.sh)"   # throwaway container on 127.0.0.1:55435, migrated
./mvnw -B verify -Pintegration              # unit, HTTP and *IT
bash scripts/test-database.sh down
```

In CI, the `integration` job does the same on every pull request to `qa`; `db-ci.yml` rebuilds
the schema on every pull request.

## Branching

`develop` ← `feat/…`, `fix/…`, `chore/…` (squash); `qa` ← `qa-<SUBTASK-ID>` (rebase, re-applied
with `git cherry-pick -x`); `main` ← `release.2.Y.Z` with the teacher's approval. Full policy:
`market-agri-docs/00-governance/git-conventions.md`.
