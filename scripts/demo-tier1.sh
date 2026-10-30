#!/usr/bin/env bash
# Second demo: the Tier 1 fixes, live against a running stack.
# Run after scripts/demo-checkout.sh (it needs the oak-bench product).
# Usage: scripts/demo-tier1.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
J='Content-Type: application/json'
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

TOKEN=$(curl -s -X POST "$API/private/login" -H "$J" -d '{"username":"admin@shopizer.com","password":"password"}' | field "d['token']")
AUTH="Authorization: Bearer $TOKEN"
BODY='{"customer":{"emailAddress":"tier1.buyer@example.com","firstName":"Tier","lastName":"One","billing":{"firstName":"Tier","lastName":"One","address":"1 Main St","city":"Toronto","postalCode":"M5V2T6","country":"CA","zone":"ON","phone":"5555555555"}},"payment":{"paymentType":"MONEYORDER","transactionType":"CAPTURE","paymentModule":"moneyorder","amount":"249.00"},"currency":"CAD","customerAgreement":true}'
stock() { curl -s "$API/product/oak-bench?store=DEFAULT" | field "d.get('quantity')"; }

echo "== Order lifecycle: cancel moves money and stock"
CART=$(curl -s -X POST "$API/cart" -H "$J" -d '{"product":"oak-bench","quantity":1}' | field "d['code']")
ORDER=$(curl -s -X POST "$API/cart/$CART/checkout" -H "$J" -H "Idempotency-Key: tier1-$RANDOM$RANDOM" -d "$BODY" | field "d['id']")
echo "order $ORDER placed, stock now $(stock)"
echo "legal next steps : $(curl -s "$API/private/orders/$ORDER/transitions" -H "$AUTH" | field "d['next']")"
echo "PUT status CANCELED (bypass attempt) -> $(code -X PUT "$API/private/orders/$ORDER/status" -H "$AUTH" -H "$J" -d '"CANCELED"')   (must use the cancel command)"
echo "POST cancel      : $(curl -s -X POST "$API/private/orders/$ORDER/cancel" -H "$AUTH" | field "d['status']+' | '+(d.get('message') or '')")"
echo "POST cancel again -> $(code -X POST "$API/private/orders/$ORDER/cancel" -H "$AUTH")   (409, nothing refunded twice)"
echo "stock after cancel: $(stock)"

echo "== Gateway secrets are write-only, encrypted and audited"
curl -s -o /dev/null -X POST "$API/private/modules/payment" -H "$AUTH" -H "$J" \
  -d '{"code":"stripe3","active":false,"defaultSelected":false,"integrationKeys":{"publishableKey":"pk_test_demo","secretKey":"sk_test_DEMOONLY000000000wxyz","webhookSecret":"whsec_DEMO4321"},"integrationOptions":{}}'
echo "read back        : $(curl -s "$API/private/modules/payment/stripe3" -H "$AUTH" | field "d['integrationKeys']")"
echo "(stored as AES-256-GCM 'v2:' ciphertext; the change is in CONFIGURATION_AUDIT and emailed to the store)"

echo "== Tenant isolation"
echo "customer of another store by id -> $(code "$API/private/customer/999999?store=DEFAULT" -H "$AUTH")   (404, never another store's data)"
echo "unknown product slug           -> $(code "$API/product/no-such-product?store=DEFAULT")   (3.2.7: 500)"

echo "== Abuse defences"
printf "10 bad admin logins from one address: "
for _ in $(seq 1 10); do printf "%s " "$(code -X POST "$API/private/login" -H "$J" -d '{"username":"nobody@example.com","password":"wrong"}')"; done; echo "  (401 then 429)"

echo "== Catalog is CDN-cacheable, private data is not"
echo "product GET : $(curl -s -D - -o /dev/null "$API/product/oak-bench?store=DEFAULT" | grep -i '^cache-control' | tr -d '\r')"
echo "orders GET  : $(curl -s -D - -o /dev/null "$API/private/orders?store=DEFAULT" -H "$AUTH" | grep -i '^cache-control' | tr -d '\r')"
echo "sitemap     : $(code "$API/sitemap.xml?store=DEFAULT")"
echo "request id  : $(curl -s -D - -o /dev/null "$API/store/DEFAULT" | grep -i '^x-request-id' | tr -d '\r')"
