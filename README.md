# market-agri-workflow

Transversal **checkout saga** orchestrator (ADR-015) of the **Marketplace Agrícola Huila**
distributed system: Java 21, Spring Boot 3.5, hexagonal architecture in three Maven modules
(Anexo C). It will run the purchase across chat, catalog and transactions (E-40) and expose the
state of each saga (E-46). Contract: `market-agri-docs/07-api/api-contract.md` §4.6.

## Modules

```
workflow-core/      domain/model, application/port/{in,out}, application/usecase — plain Java, no framework
workflow-adapters/  adapter/in/http (and the outbound adapters, with their use cases)
workflow-app/       composition root: entry point, wiring and every limit (application.yml)
db/                 the workflow schema with Liquibase, as a -db repository (ADR-013): see db/README.md
deploy/             Dockerfile and compose.yml (service and workflow-db-migrate), included by market-agri-infra
```

`workflow-core` declares no framework: a Spring or JDBC type there does not compile. The service
connects as `workflow_app` and never migrates: `workflow-db-migrate` does it, from infra.

## What the scaffold already does

| Rule | Where |
|---|---|
| `GET /health` → `200 {"status":"UP"}` (liveness, E-60) | `adapter/in/http/HealthController` |

## Run locally

```bash
./mvnw -B verify                      # build, unit and HTTP tests (Windows: mvnw.cmd)
java -jar workflow-app/target/workflow-app-0.0.1-SNAPSHOT.jar
curl -i http://localhost:8080/health
```

## Branching

`develop` ← `feat/…`, `fix/…`, `chore/…` (squash); `qa` ← `qa-<SUBTASK-ID>` (rebase, re-applied
with `git cherry-pick -x`); `main` ← `release.2.Y.Z` with the teacher's approval. Full policy:
`market-agri-docs/00-governance/git-conventions.md`.
