#!/usr/bin/env bash
# Simulates a crash after the gateway charged: the order is left PAYMENT_PENDING,
# its payment record is missing and its payment attempt has no outcome, two days ago.
# The reconciler must NOT fail it (money may have been taken). With no gateway answer
# it moves the order to PAYMENT_REVIEW for a person. Uses the compose stack's database.
# Exits 1 if the order is still PAYMENT_PENDING after five minutes.
# Usage: scripts/simulate-stuck-order.sh <orderId>
# ENV_FILE (default deploy/.env) and COMPOSE_PROJECT_NAME select another stack.
set -euo pipefail
cd "$(dirname "$0")/.."
ORDER="${1:?order id}"
[[ "$ORDER" =~ ^[0-9]+$ ]] || { echo "order id must be a number" >&2; exit 2; }
ENV_FILE="${ENV_FILE:-deploy/.env}"
set -a; . "$ENV_FILE"; set +a
compose() { docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.yml "$@"; }
sql() { compose exec -T db mysql -uroot -p"$DB_ROOT_PASSWORD" -N SALESMANAGER -e "$1" 2>/dev/null; }
sql "update ORDERS set ORDER_STATUS='PAYMENT_PENDING', DATE_PURCHASED=DATE_SUB(NOW(), INTERVAL 2 DAY) where ORDER_ID=$ORDER;
     delete from SM_TRANSACTION where ORDER_ID=$ORDER;
     update PAYMENT_ATTEMPT set STATUS='UNKNOWN', DATE_CREATED=DATE_SUB(NOW(), INTERVAL 2 DAY) where ORDER_ID=$ORDER;"
echo "before: $(sql "select ORDER_STATUS from ORDERS where ORDER_ID=$ORDER") / attempt $(sql "select STATUS from PAYMENT_ATTEMPT where ORDER_ID=$ORDER order by ID desc limit 1")"
echo "waiting for the reconciler (RECONCILE_DELAY_MS=${RECONCILE_DELAY_MS:-300000})..."
for _ in $(seq 1 60); do
  s=$(sql "select ORDER_STATUS from ORDERS where ORDER_ID=$ORDER")
  [ "$s" != "PAYMENT_PENDING" ] && break
  sleep 5
done
echo "after:  $s"
# works with both log formats: JSON lines are reduced to their message
compose logs api 2>/dev/null | grep -E "PendingOrderReconciler" | tail -2 \
  | python3 -c 'import sys,json
for l in sys.stdin:
    j=l.split("|",1)[-1].strip()
    try: print(" ", json.loads(j).get("message",""))
    except ValueError: print(l.rstrip())' || true
[ "$s" != "PAYMENT_PENDING" ]
