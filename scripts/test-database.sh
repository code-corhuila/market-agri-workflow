#!/usr/bin/env bash
# Throwaway PostgreSQL with the workflow schema of db/, for the integration tests
# (Anexo C: "PostgreSQL con el esquema del -db, por TEST_DATABASE_URL"). Needs Docker.
#
#   export "$(bash scripts/test-database.sh)"   # start, migrate, print TEST_DATABASE_URL=…
#   ./mvnw -B verify -Pintegration
#   bash scripts/test-database.sh down          # remove the container
#
# TEST_DB_PORT: host port (default: 55435). Progress goes to stderr; stdout is only the variable.
set -euo pipefail

NAME=workflow-test-db
PORT=${TEST_DB_PORT:-55435}
DB_DIR=$(cd "$(dirname "$0")/../db" && pwd)
PASSWORD=test-only          # a throwaway container on 127.0.0.1: not a secret

if [ "${1:-}" = down ]; then
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    exit 0
fi

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:5432" \
    -e POSTGRES_DB=market_agri -e POSTGRES_USER=market_admin -e POSTGRES_PASSWORD="$PASSWORD" \
    postgres:16-alpine >/dev/null

# Over TCP: the image's first, temporary server only listens on the socket.
echo "waiting for PostgreSQL…" >&2
until docker exec "$NAME" pg_isready -h 127.0.0.1 -U market_admin -d market_agri >/dev/null 2>&1; do
    sleep 1
done

# What market-agri-infra's 01-instance.sh does: extension, workflow_app and its search_path.
docker exec -i "$NAME" psql -q -v ON_ERROR_STOP=1 -U market_admin -d market_agri >&2 <<SQL
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE ROLE workflow_app LOGIN PASSWORD '$PASSWORD';
ALTER ROLE workflow_app SET search_path = workflow;
SQL

# The schema comes from db/, migrated by the administrator (ADR-013), with the changelog tables
# of workflow-db-migrate. --contexts=integration: no seeds, so the tests start empty.
# pwd -W gives D:/… in Git Bash; MSYS_NO_PATHCONV keeps /workspace as it is.
HOST_DIR=$(cd "$DB_DIR" && { pwd -W 2>/dev/null || pwd; })
MSYS_NO_PATHCONV=1 docker run --rm --network "container:$NAME" -v "$HOST_DIR:/workspace:ro" \
    liquibase/liquibase:4.29 --search-path=/workspace \
    --changelog-file=changelog/changelog-master.yaml \
    --url=jdbc:postgresql://localhost:5432/market_agri \
    --username=market_admin --password="$PASSWORD" \
    --database-changelog-table-name=databasechangelog_workflow \
    --database-changelog-lock-table-name=databasechangeloglock_workflow \
    --contexts=integration update >&2

echo "TEST_DATABASE_URL=jdbc:postgresql://localhost:$PORT/market_agri?user=workflow_app&password=$PASSWORD"
