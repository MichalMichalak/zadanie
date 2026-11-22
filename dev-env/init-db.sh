#!/usr/bin/env bash
#
# Creates the database and tables and restricts the application credentials to DML only.
#
# The application itself never issues DDL: schema changes are a separate, privileged
# operation (in production: a migration run by DBA tooling, not by the deployed service).
#
# Usage:
#   ./init-db.sh            # create database, tables and grants (idempotent)
#   ./init-db.sh --reset    # drop all tables first, then create everything again
#
# Requires the containers from docker-compose.yml to be running (docker compose up -d).

set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
    echo "error: .env not found; copy .env.example to .env first" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD must be set in .env}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE must be set in .env}"
: "${MYSQL_USER:?MYSQL_USER must be set in .env}"

reset=false
if [[ "${1:-}" == "--reset" ]]; then
    reset=true
elif [[ $# -gt 0 ]]; then
    echo "error: unknown argument '$1'" >&2
    exit 1
fi

# The mysql client password comes from the container environment, never from the host shell.
as_root() {
    docker compose exec -T mysql \
        sh -c 'exec mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --batch --skip-column-names'
}

as_root_in_database() {
    docker compose exec -T mysql \
        sh -c 'exec mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names'
}

echo "waiting for mysql to accept connections..."
for attempt in $(seq 1 60); do
    if as_root <<< 'SELECT 1' >/dev/null 2>&1; then
        break
    fi
    if [[ "$attempt" -eq 60 ]]; then
        echo "error: mysql did not become ready in time" >&2
        exit 1
    fi
    sleep 2
done

if [[ "$reset" == true ]]; then
    echo "dropping existing tables in ${MYSQL_DATABASE}..."
    as_root <<SQL
DROP DATABASE IF EXISTS \`${MYSQL_DATABASE}\`;
SQL
fi

# schema.sql holds table DDL only, so the database is created here, with the collation the tables
# inherit from.
echo "creating database ${MYSQL_DATABASE} if needed..."
as_root <<SQL
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
SQL

echo "applying schema to ${MYSQL_DATABASE}..."
as_root_in_database < schema.sql

echo "restricting '${MYSQL_USER}' to DML on ${MYSQL_DATABASE}..."
as_root <<SQL || echo "note: no previous grants to revoke"
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${MYSQL_USER}'@'%';
SQL
as_root <<SQL
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
SQL

echo
echo "done. '${MYSQL_USER}'@'%' can only SELECT/INSERT/UPDATE/DELETE in ${MYSQL_DATABASE}:"
as_root <<SQL
SHOW GRANTS FOR '${MYSQL_USER}'@'%';
SQL
