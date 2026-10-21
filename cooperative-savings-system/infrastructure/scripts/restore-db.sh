#!/usr/bin/env bash
# Restore a TERIMBERE CSAMS PostgreSQL custom-format dump.
# WARNING: This can overwrite data. Use only with explicit intent.
#
# Required env: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
# Usage:
#   ./infrastructure/scripts/restore-db.sh /path/to/csams_db_YYYYMMDD.dump

set -euo pipefail

DUMP_FILE="${1:?Usage: restore-db.sh <dump-file>}"

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "Dump file not found: ${DUMP_FILE}" >&2
  exit 1
fi

echo "Restoring ${DUMP_FILE} into ${PGDATABASE} @ ${PGHOST}"
echo "This may drop/replace objects depending on dump contents."
pg_restore --clean --if-exists --no-owner --no-acl --dbname="${PGDATABASE}" "${DUMP_FILE}"

echo "Restore finished. Verify Flyway schema history and application health."
