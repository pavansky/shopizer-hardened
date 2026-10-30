#!/usr/bin/env bash
# Restore drill: prove the newest dump restores. A backup is only a backup once it has been
# restored.
#   1. checks the dump's sha256 and gzip integrity
#   2. restores it into a throwaway MySQL 8.4 container
#   3. compares the restored table count with the dump and with the live schema, and the
#      row counts of the money tables with the live database
# Prints RESTORE DRILL PASSED and exits 0, or RESTORE DRILL FAILED and exits 1 on any
# mismatch or error. Live row counts move with new orders, so use --fresh (take a backup
# first) when the stack is in use.
#
# Usage (from the repository root):
#   deploy/backup/restore-drill.sh [--fresh]
# ENV_FILE (default deploy/.env) and COMPOSE_PROJECT_NAME select another stack.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
set -a; . "$ENV_FILE"; set +a
compose() { docker compose --env-file "$ENV_FILE" -f docker-compose.yml "$@"; }
scratch="${COMPOSE_PROJECT_NAME:-shopizer}-restore-drill"
result=FAILED

finish() {
  docker rm -f "$scratch" >/dev/null 2>&1 || true
  if [ "$result" = PASSED ]; then
    echo "RESTORE DRILL PASSED"
  else
    echo "RESTORE DRILL FAILED" >&2
    exit 1
  fi
}
trap finish EXIT
die() { echo "FAIL: $*" >&2; exit 1; }

if [ "${1:-}" = "--fresh" ]; then
  echo "taking a backup now"
  compose exec -T backup bash /backup.sh once || die "the backup job refused the new dump"
fi

dump=$(ls -1t backup/dumps/SALESMANAGER-*.sql.gz 2>/dev/null | head -1 || true)
[ -n "$dump" ] || die "no dump in deploy/backup/dumps"
echo "newest dump: $dump ($(wc -c < "$dump" | tr -d ' ') bytes)"

[ -f "$dump.sha256" ] || die "no checksum file for $dump"
if command -v sha256sum >/dev/null; then sum=(sha256sum -c); else sum=(shasum -a 256 -c); fi
(cd backup/dumps && "${sum[@]}" "$(basename "$dump").sha256") || die "checksum mismatch"
gzip -t "$dump" || die "not a valid gzip file"
in_dump=$(gunzip -c "$dump" | grep -c '^CREATE TABLE ' || true)
[ "$in_dump" -gt 0 ] || die "the dump contains no tables"

docker rm -f "$scratch" >/dev/null 2>&1 || true
docker run -d --name "$scratch" -e MYSQL_ROOT_PASSWORD=drill mysql:8.4 >/dev/null
# a TCP query only succeeds once initialisation is over (the init server has no networking)
for _ in $(seq 1 90); do
  docker exec "$scratch" mysql -h127.0.0.1 -uroot -pdrill -e "select 1" >/dev/null 2>&1 && break
  sleep 2
done
docker exec "$scratch" mysql -h127.0.0.1 -uroot -pdrill -e "select 1" >/dev/null 2>&1 || die "scratch MySQL did not start"

start=$(date +%s)
gunzip -c "$dump" | docker exec -i "$scratch" mysql -h127.0.0.1 -uroot -pdrill 2>/dev/null || die "restore failed"
echo "restored in $(( $(date +%s) - start ))s"

restored_q() { docker exec "$scratch" mysql -h127.0.0.1 -uroot -pdrill -N -B -e "$1" 2>/dev/null; }
live_q() { compose exec -T db mysql -uroot -p"$DB_ROOT_PASSWORD" -N -B -e "$1" 2>/dev/null; }

fails=0
tables_q="select count(*) from information_schema.tables where table_schema='SALESMANAGER' and table_type='BASE TABLE'"
restored_tables=$(restored_q "$tables_q")
live_tables=$(live_q "$tables_q") || die "cannot query the live database"
status=OK; { [ "$restored_tables" = "$in_dump" ] && [ "$restored_tables" = "$live_tables" ]; } || { status=MISMATCH; fails=$((fails+1)); }
printf '%-16s dump=%-6s restored=%-6s live=%-6s %s\n' "tables" "$in_dump" "$restored_tables" "$live_tables" "$status"

for t in ORDERS ORDER_PRODUCT SM_TRANSACTION PAYMENT_ATTEMPT PRODUCT CUSTOMER; do
  live=$(live_q "select count(*) from SALESMANAGER.$t") || live="error"
  restored=$(restored_q "select count(*) from SALESMANAGER.$t") || restored="error"
  status=OK; [ "$live" = "$restored" ] && [ "$live" != error ] || { status="MISMATCH (writes since the dump? rerun with --fresh)"; fails=$((fails+1)); }
  printf '%-16s live=%-6s restored=%-6s %s\n' "$t" "$live" "$restored" "$status"
done

[ "$fails" -eq 0 ] || die "$fails check(s) did not match"
result=PASSED
