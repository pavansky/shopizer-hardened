#!/usr/bin/env bash
# Restore drill: restore the newest dump into a throwaway MySQL and compare row counts
# with the live database. A backup is only proven once it has been restored.
# Usage: deploy/backup/restore-drill.sh            (run from the repository root)
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
dump=$(ls -1t backup/dumps/SALESMANAGER-*.sql.gz | head -1)
echo "newest dump: $dump"
(cd backup/dumps && sha256sum -c "$(basename "$dump").sha256" 2>/dev/null || shasum -a 256 -c "$(basename "$dump").sha256")
docker rm -f shopizer-restore-drill >/dev/null 2>&1 || true
docker run -d --name shopizer-restore-drill -e MYSQL_ROOT_PASSWORD=drill mysql:8.4 >/dev/null
until docker exec shopizer-restore-drill mysqladmin ping -uroot -pdrill --silent 2>/dev/null; do sleep 2; done
sleep 5
start=$(date +%s)
gunzip -c "$dump" | docker exec -i shopizer-restore-drill mysql -uroot -pdrill 2>/dev/null
echo "restored in $(( $(date +%s) - start ))s"
q="select table_name from information_schema.tables where table_schema='SALESMANAGER' and table_type='BASE TABLE' order by 1"
fails=0
for t in ORDERS ORDER_PRODUCT SM_TRANSACTION PAYMENT_ATTEMPT PRODUCT CUSTOMER; do
  live=$(docker compose exec -T db mysql -uroot -p"$DB_ROOT_PASSWORD" -N -e "select count(*) from SALESMANAGER.$t" 2>/dev/null)
  restored=$(docker exec shopizer-restore-drill mysql -uroot -pdrill -N -e "select count(*) from SALESMANAGER.$t" 2>/dev/null)
  status=OK; [ "$live" = "$restored" ] || { status="DIFFERS (writes since the dump?)"; fails=$((fails+1)); }
  printf '%-16s live=%-6s restored=%-6s %s\n' "$t" "$live" "$restored" "$status"
done
tables=$(docker exec shopizer-restore-drill mysql -uroot -pdrill -N -e "$q" 2>/dev/null | wc -l | tr -d ' ')
echo "tables restored: $tables"
docker rm -f shopizer-restore-drill >/dev/null
[ "$fails" -eq 0 ] && echo "RESTORE DRILL PASSED" || echo "RESTORE DRILL: $fails tables differ"
