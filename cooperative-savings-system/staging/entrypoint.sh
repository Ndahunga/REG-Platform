#!/usr/bin/env bash
# Starts PostgreSQL inside the container unless DATABASE_URL points at a real database, then the backend, then seeds
# demo data on a fresh database.
set -euo pipefail
export SERVER_PORT="${PORT:-8080}"

if [ -n "${DATABASE_URL:-}" ]; then
  # postgres://user:pass@host:port/db
  proto_removed="${DATABASE_URL#*://}"
  creds="${proto_removed%%@*}"; hostpart="${proto_removed#*@}"
  export POSTGRES_USER="${creds%%:*}" POSTGRES_PASSWORD="${creds#*:}"
  hostport="${hostpart%%/*}"; export POSTGRES_DB="${hostpart#*/}"
  export POSTGRES_HOST="${hostport%%:*}"
  if [[ "$hostport" == *:* ]]; then export POSTGRES_PORT="${hostport##*:}"; else export POSTGRES_PORT=5432; fi
  echo "[staging] using external database at ${POSTGRES_HOST}"
else
  PGDATA=/tmp/pgdata
  PGBIN=$(ls -d /usr/lib/postgresql/*/bin | head -1)
  if [ ! -s "$PGDATA/PG_VERSION" ]; then
    mkdir -p "$PGDATA" && chown postgres:postgres "$PGDATA"
    su postgres -c "$PGBIN/initdb -D $PGDATA -A trust -U postgres" >/dev/null
    cat >> "$PGDATA/postgresql.conf" <<CONF
shared_buffers = 16MB
max_connections = 20
work_mem = 2MB
maintenance_work_mem = 16MB
fsync = off
synchronous_commit = off
listen_addresses = '127.0.0.1'
CONF
  fi
  su postgres -c "$PGBIN/pg_ctl -D $PGDATA -l /tmp/pg.log -w start" >/dev/null
  su postgres -c "psql -qtc \"select 1 from pg_database where datname='csams'\" | grep -q 1 || psql -qc \"create user csams password 'csams'\" -c \"create database csams owner csams\"" >/dev/null
  export POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5432 POSTGRES_DB=csams POSTGRES_USER=csams POSTGRES_PASSWORD=csams
  echo "[staging] embedded PostgreSQL started (data resets when the instance restarts)"
fi
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:-6}"
export SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1

java -jar /app/app.jar &
APP=$!
( python3 /app/seed.py || echo "[seed] failed (the app keeps running)" ) &
wait $APP
