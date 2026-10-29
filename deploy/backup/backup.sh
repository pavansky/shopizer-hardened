#!/bin/sh
# Logical backups of SALESMANAGER every BACKUP_INTERVAL_SECONDS, keeping BACKUP_KEEP dumps.
# --single-transaction gives a consistent InnoDB snapshot without locking writes;
# the binary log (7 days, enabled on the db service) allows point-in-time recovery
# between dumps. Production uses RDS automated backups instead; see docs/SYSTEM-DESIGN.md.
set -eu
mkdir -p /dumps
while true; do
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  tmp="/dumps/.SALESMANAGER-$ts.sql.gz"
  if mysqldump -h db -u root --single-transaction --routines --triggers --set-gtid-purged=OFF \
       --databases SALESMANAGER | gzip -9 > "$tmp"; then
    mv "$tmp" "/dumps/SALESMANAGER-$ts.sql.gz"
    sha256sum "/dumps/SALESMANAGER-$ts.sql.gz" > "/dumps/SALESMANAGER-$ts.sql.gz.sha256"
    echo "backup ok $ts $(du -h /dumps/SALESMANAGER-$ts.sql.gz | cut -f1)"
  else
    rm -f "$tmp"; echo "backup FAILED $ts" >&2
  fi
  ls -1t /dumps/SALESMANAGER-*.sql.gz 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) | while read -r old; do rm -f "$old" "$old.sha256"; done
  sleep "$BACKUP_INTERVAL_SECONDS"
done
