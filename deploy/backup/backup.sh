#!/usr/bin/env bash
# Logical backups of SALESMANAGER every BACKUP_INTERVAL_SECONDS, keeping BACKUP_KEEP dumps.
#
#   backup.sh          loop: one backup now, then one per interval (the compose service)
#   backup.sh once     one backup; exits 0 only if it passed every check (restore drill, CI)
#
# A dump is kept, and reported as good, only when mysqldump and gzip both succeeded and the
# file passes gzip -t, is at least BACKUP_MIN_BYTES, ends with mysqldump's "Dump completed"
# trailer and has one CREATE TABLE per table in the live schema. It is written under a
# hidden temporary name and renamed into place only after those checks, so an empty or
# half-written file never looks like a backup.
#
# After a good dump, $STATUS_DIR/backup.status records last_success_epoch, size_bytes and
# table_count. The API exports it as shopizer_backup_last_success_timestamp_seconds and
# Prometheus raises BackupStale when it gets old. A failed run prints BACKUP FAILED, writes
# $STATUS_DIR/backup.last-failure, leaves the status file and the existing dumps alone, and
# retries after BACKUP_RETRY_SECONDS.
#
# --single-transaction gives a consistent InnoDB snapshot without blocking writes, and
# --source-data=2 records that snapshot's binlog position as a comment, which is where a
# point-in-time binlog replay starts. Production uses RDS automated backups instead; see
# docs/SYSTEM-DESIGN.md.
set -euo pipefail

DB_HOST="${DB_HOST:-db}"
DB_NAME="${DB_NAME:-SALESMANAGER}"
DUMP_DIR="${DUMP_DIR:-/dumps}"
STATUS_DIR="${STATUS_DIR:-/status}"
BACKUP_INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-21600}"
BACKUP_RETRY_SECONDS="${BACKUP_RETRY_SECONDS:-300}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
BACKUP_MIN_BYTES="${BACKUP_MIN_BYTES:-8192}"
BACKUP_WAIT_SECONDS="${BACKUP_WAIT_SECONDS:-300}"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }
fail() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup check failed: $*" >&2; exit 1; }

# MYSQL_PWD comes from the environment, so the password is never on a command line.
mysql_q() { mysql -h "$DB_HOST" -u root -N -B -e "$1"; }

# After a host or daemon restart the backup container can start before MySQL accepts
# connections (depends_on is not re-checked then), and on a new stack before the API has
# created the schema. Wait for both instead of dumping nothing.
schema_tables() {
  mysql_q "select count(*) from information_schema.tables where table_schema='$DB_NAME' and table_type='BASE TABLE'"
}
wait_for_db() {
  local waited=0
  until [ "$(schema_tables 2>/dev/null || echo 0)" -gt 0 ]; do
    [ "$waited" -lt "$BACKUP_WAIT_SECONDS" ] || fail "no $DB_NAME schema on $DB_HOST after ${BACKUP_WAIT_SECONDS}s"
    sleep 5
    waited=$((waited + 5))
  done
}

# One backup, run in its own bash process (see the loop), so set -e applies to every line.
backup_once() {
  mkdir -p "$DUMP_DIR" "$STATUS_DIR"
  wait_for_db

  local ts name expected found size
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  name="$DB_NAME-$ts.sql.gz"
  # global, not local: the EXIT trap runs after this function has returned
  tmp="$DUMP_DIR/.$name.partial"
  trap 'rm -f "${tmp:-}" "${tmp:-}.sha256"' EXIT

  expected=$(schema_tables)

  mysqldump -h "$DB_HOST" -u root --single-transaction --source-data=2 --routines --triggers \
    --set-gtid-purged=OFF --databases "$DB_NAME" | gzip -9 > "$tmp"

  gzip -t "$tmp" || fail "$name is not a valid gzip file"
  size=$(stat -c %s "$tmp")
  [ "$size" -ge "$BACKUP_MIN_BYTES" ] || fail "$name is $size bytes, below the $BACKUP_MIN_BYTES byte minimum"
  gunzip -c "$tmp" | tail -n 1 | grep -q -- "-- Dump completed" || fail "$name has no 'Dump completed' trailer"
  found=$(gunzip -c "$tmp" | grep -c '^CREATE TABLE ' || true)
  [ "$found" -eq "$expected" ] || fail "$name has $found CREATE TABLE statements, the schema has $expected tables"

  (cd "$DUMP_DIR" && sha256sum "$(basename "$tmp")" | sed "s|\.$name\.partial\$|$name|" > "$tmp.sha256")
  mv "$tmp.sha256" "$DUMP_DIR/$name.sha256"
  mv "$tmp" "$DUMP_DIR/$name"

  printf 'last_success_epoch=%s\nsize_bytes=%s\ntable_count=%s\nfile=%s\n' \
    "$(date +%s)" "$size" "$found" "$name" > "$STATUS_DIR/.backup.status.tmp"
  mv "$STATUS_DIR/.backup.status.tmp" "$STATUS_DIR/backup.status"
  log "backup ok $name size=${size}B tables=$found"

  # retention runs only after a good dump, so failures never delete the last good ones
  ls -1t "$DUMP_DIR/$DB_NAME"-*.sql.gz | tail -n +$((BACKUP_KEEP + 1)) \
    | while read -r old; do rm -f "$old" "$old.sha256"; done
}

if [ "${1:-}" = "once" ]; then
  backup_once
  exit 0
fi

mkdir -p "$STATUS_DIR"
while true; do
  if bash "$0" once; then
    delay="$BACKUP_INTERVAL_SECONDS"
  else
    log "BACKUP FAILED: no new dump was kept, the last good backup is unchanged; retrying in ${BACKUP_RETRY_SECONDS}s" >&2
    printf 'last_failure_epoch=%s\n' "$(date +%s)" > "$STATUS_DIR/backup.last-failure"
    delay="$BACKUP_RETRY_SECONDS"
  fi
  sleep "$delay"
done
