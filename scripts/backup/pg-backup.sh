#!/usr/bin/env bash
#
# Stage 10 (docs/DECISIONS.md ADR-017): nightly PostgreSQL backup for the docker-compose.prod.yml
# deployment. Dumps the database with pg_dump (custom format, -Fc) so it can be restored with
# pg_restore (parallelism, selective table restore, etc - plain SQL dumps can't do either), then
# applies a simple day-count retention policy so the backup directory doesn't grow unbounded.
#
# Usage:
#   BACKUP_DIR=/var/backups/loyalty-bot ./scripts/backup/pg-backup.sh
#
# Intended to run from cron on the host, e.g.:
#   0 3 * * * BACKUP_DIR=/var/backups/loyalty-bot /opt/loyalty-bot/scripts/backup/pg-backup.sh >> /var/log/loyalty-bot-backup.log 2>&1
#
# Does NOT back up the import_files volume (supplier price-file blobs) - that is either:
#   - already durable because you switched to S3 storage (supplier-import.storage.provider=s3,
#     see docs/DECISIONS.md ADR-014), in which case your S3 bucket's own versioning/replication
#     is the backup story, or
#   - backed up separately with `docker run --rm -v import_files:/data -v $BACKUP_DIR:/backup
#     alpine tar czf /backup/import-files-$(date +%F).tar.gz -C /data .` if still on local storage.
# It is deliberately NOT re-implemented here: the DB dump (this script) is the one backup that is
# never optional (it holds every shop's catalog/orders/customers), while the file blobs are
# re-derivable from the original supplier emails if truly lost.
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-loyalty-bot-db}"
POSTGRES_DB="${POSTGRES_DB:-loyalty_db}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_file="$BACKUP_DIR/${POSTGRES_DB}-${timestamp}.dump"

echo "[pg-backup] Dumping ${POSTGRES_DB} from container ${CONTAINER_NAME} -> ${backup_file}"
docker exec "$CONTAINER_NAME" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$backup_file"

size_bytes=$(wc -c < "$backup_file")
if [ "$size_bytes" -lt 1024 ]; then
  echo "[pg-backup] ERROR: backup file is suspiciously small (${size_bytes} bytes) - not trusting it, deleting" >&2
  rm -f "$backup_file"
  exit 1
fi
echo "[pg-backup] OK: ${backup_file} (${size_bytes} bytes)"

echo "[pg-backup] Applying retention: deleting dumps older than ${RETENTION_DAYS} days in ${BACKUP_DIR}"
find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.dump" -type f -mtime "+${RETENTION_DAYS}" -print -delete

echo "[pg-backup] Done"
