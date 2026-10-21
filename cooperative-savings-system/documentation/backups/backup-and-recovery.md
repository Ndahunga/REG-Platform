# Database & uploads backup

Operational backups for TERIMBERE CSAMS. There is **no** public HTTP endpoint that downloads full database dumps.

## Prerequisites

- `pg_dump` / `pg_restore` on the PATH (PostgreSQL client tools)
- Environment variables only (never commit passwords):

```bash
export PGHOST=...
export PGPORT=5432
export PGUSER=...
export PGDATABASE=cooperative_savings_db
export PGPASSWORD=...   # prefer .pgpass or a secrets manager
```

## Backup

Linux/macOS:

```bash
chmod +x infrastructure/scripts/backup-db.sh
./infrastructure/scripts/backup-db.sh
```

Windows (PowerShell) equivalent:

```powershell
$env:BACKUP_DIR = ".\backups\db"
New-Item -ItemType Directory -Force -Path $env:BACKUP_DIR | Out-Null
$stamp = Get-Date -Format "yyyyMMddTHHmmssZ"
$out = Join-Path $env:BACKUP_DIR "csams_$env:PGDATABASE`_$stamp.dump"
pg_dump --format=custom --no-owner --no-acl --file=$out
```

## Restore

```bash
chmod +x infrastructure/scripts/restore-db.sh
./infrastructure/scripts/restore-db.sh ./backups/db/csams_cooperative_savings_db_....dump
```

## Uploads directory

Also back up `FILE_STORAGE_BASE_PATH` (default `uploads/`) with filesystem snapshots or `rsync`/`robocopy`. Database dumps do **not** include uploaded files.

## Scheduling

Use host cron, systemd timers, or your cloud scheduler to run `backup-db.sh` daily. Example cron:

```
15 2 * * * cd /opt/csams && . /etc/csams/backup.env && ./infrastructure/scripts/backup-db.sh >> /var/log/csams-backup.log 2>&1
```

## Verification procedure

1. Run a backup and confirm the `.dump` file size is non-zero.
2. On a **non-production** restore target, restore the dump.
3. Start the app against the restored DB and hit `/actuator/health` and `/api/v1/public/health`.
4. Spot-check one cooperative’s ledger and membership counts.
5. Confirm uploads restore separately if testing file downloads.

Retention guidance: keep at least 14 daily dumps + weekly copies off-box.
