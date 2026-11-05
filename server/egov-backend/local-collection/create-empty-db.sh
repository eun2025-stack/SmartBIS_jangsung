#!/usr/bin/env bash
# WRITE-ONLY deliverable for this review: do not execute without separate approval.
# Source order: V001..V019 -> schema.sql -> run-schema.sql; never docs/ copies.
# No .env is loaded. PGHOST/PGPORT/PGUSER/PGPASSWORD and FLYWAY_URL/USER/PASSWORD
# must be supplied by the caller. Values must never be echoed.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$script_dir/../../.." && pwd)"
printf '%s\n' 'PLAN: create empty DB; Flyway V001..V019; local-collection/schema.sql; local-collection/run-schema.sql'
if [[ "${1:-}" != --execute ]]; then
  printf '%s\n' 'NOT EXECUTED: separate approval and --execute required'
  exit 0
fi
: "${POSTGRES_DB:?explicit target DB required}"
: "${FLYWAY_URL:?explicit Flyway JDBC URL required}"
[[ "$POSTGRES_DB" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || { echo 'INVALID_DATABASE_NAME'; exit 2; }
[[ "${FLYWAY_URL##*/}" == "$POSTGRES_DB" ]] || { echo 'DATABASE_TARGET_MISMATCH'; exit 2; }
# schema.sql permits only smartbis_db for a local rebuild. This script does
# not bypass it or grant permission to create/migrate another target.
[[ "$POSTGRES_DB" == smartbis_db ]] || { echo 'LOCAL_SCHEMA_TARGET_REQUIRES_SEPARATE_DECISION'; exit 2; }
for version in {1..19}; do
  printf -v prefix 'V%03d__' "$version"
  files=("$repo_dir/server/database/migrations/$prefix"*.sql)
  [[ ${#files[@]} == 1 && -f "${files[0]}" ]] || { echo 'MIGRATION_SET_INVALID'; exit 2; }
done
# createdb must fail if the target already exists. No existing DB is dropped.
timeout 30 createdb --no-password -- "$POSTGRES_DB"
timeout 600 flyway -q -target=19 -locations="filesystem:$repo_dir/server/database/migrations" migrate
timeout 60 psql --no-password --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --single-transaction --file="$script_dir/schema.sql"
timeout 60 psql --no-password --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --single-transaction --file="$script_dir/run-schema.sql"
