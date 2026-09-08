#!/usr/bin/env bash
#
# Stage 10 (docs/DECISIONS.md ADR-017): restores a pg_dump custom-format backup produced by
# pg-backup.sh into a running postgres container. DESTRUCTIVE: drops and recreates every object in
# the target database first (--clean --if-exists), so this must only ever be pointed at a database
# you intend to fully overwrite (a fresh disaster-recovery instance, or a deliberate rollback).
#
# Usage:
#   ./scripts/backup/pg-restore.sh /var/backups/loyalty-bot/loyalty_db-20261201-030000.dump
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <path-to-backup.dump>" >&2
  exit 1
fi

BACKUP_FILE="$1"
CONTAINER_NAME="${CONTAINER_NAME:-loyalty-bot-db}"
POSTGRES_DB="${POSTGRES_DB:-loyalty_db}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "ERROR: backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

echo "About to restore '$BACKUP_FILE' into database '$POSTGRES_DB' on container '$CONTAINER_NAME'."
echo "This DROPS every existing object in that database first. Type 'yes' to continue:"
read -r confirmation
if [ "$confirmation" != "yes" ]; then
  echo "Aborted."
  exit 1
fi

echo "[pg-restore] Restoring..."
cat "$BACKUP_FILE" | docker exec -i "$CONTAINER_NAME" pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists

echo "[pg-restore] Done. Verify the app (Flyway runs 'validate' on startup in prod - a schema"
echo "mismatch between this dump's era and the current migrations will fail app startup loudly"
echo "rather than corrupt data; see docs/DECISIONS.md ADR-013 if that happens)."
