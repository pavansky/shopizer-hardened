#!/usr/bin/env bash
# End-to-end demo against a fresh instance (default H2 profile).
# Shows: typed admin token, deny-by-default, persist-then-charge, Idempotency-Key replay and conflict.
# Usage: scripts/demo-checkout.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
BASE="${1:-http://localhost:8080}"
API="$BASE/api"
J='Content-Type: application/json'
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== Default access"
echo "anonymous DELETE /api/v1/products/group/x  -> $(code -X DELETE "$API/v1/products/group/x")   (3.2.7: 200)"
echo "anonymous POST  /actuator/loggers/ROOT     -> $(code -X POST "$BASE/actuator/loggers/ROOT" -H "$J" -d '{"configuredLevel":"OFF"}')   (3.2.7: 204)"

echo "== Admin login (token carries token_type=admin)"
TOKEN=$(curl -s -X POST "$API/v1/private/login" -H "$J" -d '{"username":"admin@shopizer.com","password":"password"}' | json "['token']")
AUTH="Authorization: Bearer $TOKEN"

echo "== Catalog setup"
curl -s -o /dev/null -X POST "$API/v1/private/modules/payment" -H "$AUTH" -H "$J" \
  -d '{"code":"moneyorder","active":true,"defaultSelected":true,"integrationKeys":{"address":"1 Main St"},"integrationOptions":{}}'
CAT=$(curl -s -X POST "$API/v1/private/category" -H "$AUTH" -H "$J" \
  -d '{"code":"furniture","visible":true,"descriptions":[{"language":"en","name":"Furniture","friendlyUrl":"furniture"}]}' | json "['id']")
curl -s -o /dev/null -X POST "$API/v2/private/product" -H "$AUTH" -H "$J" -d "{\"identifier\":\"oak-bench\",\"sku\":\"oak-bench\",\"visible\":true,\"shipeable\":false,\"canBePurchased\":true,\"price\":249.00,\"quantity\":5,\"dateAvailable\":\"2026-10-01\",\"categories\":[{\"id\":$CAT}],\"type\":\"GENERAL\",\"descriptions\":[{\"language\":\"en\",\"name\":\"Oak Bench\",\"description\":\"Solid oak bench\",\"friendlyUrl\":\"oak-bench\",\"title\":\"Oak Bench\"}]}"
echo "category by slug /api/v1/category/furniture -> $(code "$API/v1/category/furniture")   (3.2.7: 500)"

newcart() { curl -s -X POST "$API/v1/cart" -H "$J" -d '{"product":"oak-bench","quantity":1}' | json "['code']"; }
BODY='{"customer":{"emailAddress":"test.buyer@example.com","firstName":"Test","lastName":"Buyer","billing":{"firstName":"Test","lastName":"Buyer","address":"1 Main St","city":"Toronto","postalCode":"M5V2T6","country":"CA","zone":"ON","phone":"5555555555"}},"payment":{"paymentType":"MONEYORDER","transactionType":"CAPTURE","paymentModule":"moneyorder","amount":"249.00"},"currency":"CAD","customerAgreement":true}'
checkout() {
  curl -s -w '\n%{http_code}' -X POST "$API/v1/cart/$1/checkout" -H "$J" -H "Idempotency-Key: $2" -d "$BODY" \
    | python3 -c "import sys,json;b,c=sys.stdin.read().rsplit('\n',1);d=json.loads(b);print('HTTP',c,'order id',d['id'] if 'id' in d else '-',d.get('message',''))"
}

echo "== Checkout with Idempotency-Key"
CART=$(newcart)
echo "first attempt : $(checkout "$CART" demo-key-1)"
echo "retry, same key: $(checkout "$CART" demo-key-1)"
echo "same key, other cart: $(checkout "$(newcart)" demo-key-1)"

echo "== Refunds (order 1, total 249.00)"
refund() { curl -s -w '\n%{http_code}' -X POST "$API/v1/private/orders/1/refund" -H "$AUTH" -H "$J" -d "$1" \
  | python3 -c "import sys,json;b,c=sys.stdin.read().rsplit('\n',1);d=json.loads(b) if b.strip() else {};print('HTTP',c,d.get('transactionType',''),d.get('amount',''),d.get('message',''))"; }
echo "partial 100.00     : $(refund '{"amount":100.00}')"
echo "over-refund 200.00 : $(refund '{"amount":200.00}')"
echo "remaining (no body): $(refund '{}')"
echo "nothing left       : $(refund '{}')"

echo "== Orders recorded"
curl -s "$API/v1/private/orders?count=20" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin);[print(' order',o['id'],o.get('orderStatus')) for o in d.get('orders',[])]"
echo "stock left for oak-bench (started at 5):"
curl -s "$API/v1/product/oak-bench?store=DEFAULT" | python3 -c "import sys,json;d=json.load(sys.stdin);print(' quantity', d.get('quantity'))" || true
