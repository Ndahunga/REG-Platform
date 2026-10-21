#!/usr/bin/env bash
# PostgreSQL logical backup for TERIMBERE CSAMS.
# Credentials MUST come from the environment — never hardcode passwords here.
#
# Required env:
#   PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
# Optional:
#   BACKUP_DIR (default: ./backups/db)
#   RETENTION_DAYS (default: 14) — informational; prune is optional below
#
# Example:
#   export PGHOST=localhost PGPORT=5432 PGUSER=csams_user PGDATABASE=cooperative_savings_db
#   read -s PGPASSWORD; export PGPASSWORD
#   ./infrastructure/scripts/backup-db.sh

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups/db}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${BACKUP_DIR}/csams_${PGDATABASE:-db}_${STAMP}.dump"

mkdir -p "${BACKUP_DIR}"

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"

echo "Backing up ${PGDATABASE} @ ${PGHOST} → ${OUT}"
pg_dump --format=custom --no-owner --no-acl --file="${OUT}"

# Optional prune of dumps older than retention
find "${BACKUP_DIR}" -type f -name 'csams_*.dump' -mtime "+${RETENTION_DAYS}" -print -delete || true

echo "Backup complete: ${OUT}"
echo "Also back up the uploads directory separately (FILE_STORAGE_BASE_PATH)."
