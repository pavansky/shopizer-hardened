#!/usr/bin/env bash
# Simulates a crash after the gateway charged: the order is left PAYMENT_PENDING,
# its payment record is missing and its payment attempt has no outcome, two days ago.
# The reconciler must NOT fail it (money may have been taken). With no gateway answer
# it moves the order to PAYMENT_REVIEW for a person. Uses the compose stack's database.
# Usage: scripts/simulate-stuck-order.sh <orderId>
set -euo pipefail
cd "$(dirname "$0")/.."
ORDER="${1:?order id}"
set -a; . deploy/.env; set +a
sql() { docker compose -f deploy/docker-compose.yml exec -T db mysql -uroot -p"$DB_ROOT_PASSWORD" -N SALESMANAGER -e "$1" 2>/dev/null; }
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
docker compose -f deploy/docker-compose.yml logs api 2>/dev/null | grep -E "PendingOrderReconciler" | tail -2
