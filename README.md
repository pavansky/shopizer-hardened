# Shopizer, hardened

A fork of [Shopizer 3.2.7](https://github.com/shopizer-ecommerce/shopizer) (Java 11, Spring Boot 2.5) that fixes the defects a multi-tenant merchant would hit first: money that is never collected, data that leaks between stores, endpoints that are open by default, and calls that hang forever.

The architecture is kept on purpose. This is the same modular monolith, with correct money handling, safe defaults and fast failure.

[![ci](https://github.com/pavansky/shopizer-hardened/actions/workflows/ci.yml/badge.svg)](https://github.com/pavansky/shopizer-hardened/actions/workflows/ci.yml)

## What changed, in one table

| Area | 3.2.7 | This fork |
|---|---|---|
| Braintree | Default sale only authorises. Merchant is never paid | Sale settles. Real gateway status recorded |
| Stripe | Totals of 1,000 or more fail. Yen charged 100x. Key in a global static | Minor units per currency. Key per request |
| Checkout | Charge first, insert order after. Retries charge twice | Order saved as `PAYMENT_PENDING`, then charged. `Idempotency-Key` supported |
| Stock | Oversell check commented out | Conditional decrement before charging |
| Order status | Any change allowed | Explicit transition table, 409 on illegal moves |
| Capture and refund | Returned 200 and did nothing | Real operations: store-scoped, amount-checked, row-locked, recorded as REFUND |
| Stuck orders | Left in limbo, or failed while the customer was charged | Write-ahead payment attempts, gateway lookups, signed Stripe webhooks, review queue, automatic refund of late charges |
| Customer data | Readable and editable across stores by id | Store-scoped everywhere, store-bound customer tokens, two-store integration test |
| Gateway keys | Plain text to any staff user; static-IV AES with a committed key | Masked, admin-only, AES-256-GCM with an environment key, audited |
| Tax | Basis and tax class ignored, `double` arithmetic | Golden-tested, per line, discounts before tax |
| Order status | Cancel moved no money or stock | Cancel and refund commands that refund and restock; admin offers legal steps only |
| Catalog at scale | 96.6 MB read per listing page on 1,001 products | 126 KB; 749 rps where the old build ran out of memory |
| Gateway outages | SDK default timeouts, up to about 4 minutes | 3 s / 15 s timeouts and a bulkhead; 503 before any charge when saturated |
| Abuse | No rate limits; staff were all-powerful | 429 and lockout; per-group permissions with a build-time coverage check; CSP; sanitised HTML; audit trail |
| Operations | Backups could lie; alerts reached no one | Validated backups with a freshness alert, Alertmanager, request ids, JSON logs, per-store metrics, end-to-end CI |
| Store isolation | SKU queries drop the store filter | Every SKU query scoped to the caller's store |
| Tokens | One secret `aSecret`, 7 days, admin and customer interchangeable | `JWT_SECRET` 64+ bytes, 1 hour, typed tokens |
| Default access | Unlisted paths unauthenticated. All actuator endpoints public | Deny by default. Only health and info public |
| Outbound calls | No timeouts. Thread per event | 3 s / 10 s timeouts. Bounded pools, graceful shutdown |
| Schema | `hbm2ddl update` | Flyway migrations, `validate` in production profiles |
| Delivery | Root Docker image. CI pushes from every branch | Non-root image with health check. CI runs `mvn verify` |

Every row links to a defect with file and line in [docs/FIXES.md](docs/FIXES.md).

## Demo day

The whole platform runs locally with one command: API, MySQL, admin, storefront, backups with a restore drill, Prometheus alerts, Grafana and a mail catcher. See [deploy/README.md](deploy/README.md). A full clean-stack rehearsal is recorded in [docs/demo-rehearsal.txt](docs/demo-rehearsal.txt).

- [docs/SYSTEM-DESIGN.md](docs/SYSTEM-DESIGN.md): how the same containers run in production, backups and recovery targets, scaling, costs, and the Postgres decision.
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md): measured latency, where the time goes, and where Rust would and would not help.

## Read in this order

1. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): before and after diagrams for security, checkout, payments and runtime.
2. [docs/FIXES.md](docs/FIXES.md): the 79 defects, where they were, the effect, the fix and the test.
3. [docs/adr](docs/adr): one page per design decision, including what was kept and why.
4. The commit history: one commit per fix, on top of the tag `baseline-3.2.7`.

```bash
git log --oneline baseline-3.2.7..main
git diff baseline-3.2.7 -- sm-core/src/main/java/com/salesmanager/core/business/modules/integration/payment/impl/BraintreePayment.java
```

## Build and run

Requires JDK 11 and Maven 3.8+, or Docker.

```bash
mvn -B verify
```

```bash
cd sm-shop && mvn spring-boot:run
```

The default profile uses an embedded H2 database and needs no setup. The API listens on port 8080. Swagger UI is at `/swagger-ui.html`.

For MySQL, run with the `mysql` profile. Flyway creates or upgrades the schema and Hibernate only validates it.

| Variable | Purpose | Default |
|---|---|---|
| `JWT_SECRET` | HS512 signing key, 64+ bytes | Development value, logs a warning |
| `JWT_EXPIRATION_SECONDS` | Access token lifetime | 3600 |
| `CORS_ALLOWED_ORIGINS` | Storefront and admin origins | `http://localhost:3000,http://localhost:4200` |

For Stripe webhooks, point an endpoint at `/api/v1/webhooks/stripe/<storeCode>` for the `payment_intent.*` events, and save its signing secret in the store's Stripe settings as the webhook secret.

## Check the fixes yourself

Start the app, then run the end-to-end demo. It sets up a product, checks the security defaults, and checks out twice with the same `Idempotency-Key`. Recorded output is in [docs/demo-output.txt](docs/demo-output.txt).

```bash
scripts/demo-checkout.sh http://localhost:8080
```

Then the third-pass fixes: cancel that refunds and restocks, masked gateway keys, tenant isolation, rate limits and caching headers.

```bash
scripts/demo-tier1.sh http://localhost:8080
```

Or check single defects by hand:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:8080/api/v1/products/group/x
```

That returned 200 on 3.2.7 and now returns 401.

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/category/tables
```

That returned 500 on 3.2.7 and now resolves the slug.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/actuator/loggers/ROOT -H 'Content-Type: application/json' -d '{"configuredLevel":"OFF"}'
```

That returned 204 on 3.2.7 and now returns 401.

## Proving payments against real sandboxes

The normal build never touches the network. A separate profile runs the real module classes and the reconciler-facing lookup and compensation calls against the Stripe test mode and the Braintree sandbox: Stripe PaymentIntents (automatic and manual capture, partial and full refund, compensation, decline, unknown reference), legacy Stripe charges (search lookup, compensation) and Braintree (sale, lookup by order id, void, refund after a forced settle, decline). Each check prints one `[sandbox-proof]` line with ids, statuses and amounts, never keys.

```bash
export STRIPE_TEST_SECRET_KEY=sk_test_...          # test-mode key only; anything else is refused
export BRAINTREE_SANDBOX_MERCHANT_ID=...
export BRAINTREE_SANDBOX_PUBLIC_KEY=...
export BRAINTREE_SANDBOX_PRIVATE_KEY=...
mvn -B -Psandbox verify | tee sandbox.log
grep '\[sandbox-proof\]' sandbox.log
```

A gateway whose variables are missing is skipped, not failed. In GitHub the [sandbox workflow](.github/workflows/sandbox.yml) runs weekly and on demand with repository secrets of the same four names: `STRIPE_TEST_SECRET_KEY`, `BRAINTREE_SANDBOX_MERCHANT_ID`, `BRAINTREE_SANDBOX_PUBLIC_KEY`, `BRAINTREE_SANDBOX_PRIVATE_KEY`.

## Companion repository

The Angular admin fixes live in [pavansky/shopizer-admin-hardened](https://github.com/pavansky/shopizer-admin-hardened): valid Braintree transaction types, safe retries, the 0-based orders pager, store-aware password reset, a working runtime config and a reproducible build.

## How a lost payment record is handled

This used to be a known limit. It is now closed.

- **Payment outcomes come from the gateway.** Every gateway call is written ahead as a payment attempt whose reference goes to the gateway. A timeout keeps the order pending (the API answers 202) instead of failing it. A signed Stripe webhook settles the order within seconds, and the reconciler asks Braintree or Stripe what happened: a confirmed charge is recorded and the order finalised, a late charge on a failed order is voided or refunded, and an order the gateway cannot answer for moves to `PAYMENT_REVIEW` after a day. The legacy Stripe Charge module is looked up through the Stripe search API on the `attempt_ref` metadata; because that index can lag by about a minute, an empty answer only fails an order once the attempt is older than `notFoundSafeMinutes`. See [ADR 0011](docs/adr/0011-gateway-is-the-source-of-truth.md).

## Deferred on purpose

- **Spring Boot 3.** The upgrade is deliberately deferred. See [ADR 0008](docs/adr/0008-defer-the-spring-boot-upgrade.md).

## License and credit

Shopizer is by the Shopizer team and is licensed under the Apache License 2.0; see [LICENSE.md](LICENSE.md). The original README is kept at [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md). Changes in this fork are by Pavan Kumar Guthikonda under the same license.
