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
| Stuck orders | Left in limbo | Scheduled reconciler finalises or fails them and returns stock |
| Store isolation | SKU queries drop the store filter | Every SKU query scoped to the caller's store |
| Tokens | One secret `aSecret`, 7 days, admin and customer interchangeable | `JWT_SECRET` 64+ bytes, 1 hour, typed tokens |
| Default access | Unlisted paths unauthenticated. All actuator endpoints public | Deny by default. Only health and info public |
| Outbound calls | No timeouts. Thread per event | 3 s / 10 s timeouts. Bounded pools, graceful shutdown |
| Schema | `hbm2ddl update` | Flyway migrations, `validate` in production profiles |
| Delivery | Root Docker image. CI pushes from every branch | Non-root image with health check. CI runs `mvn verify` |

Every row links to a defect with file and line in [docs/FIXES.md](docs/FIXES.md).

## Read in this order

1. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): before and after diagrams for security, checkout, payments and runtime.
2. [docs/FIXES.md](docs/FIXES.md): the 36 defects, where they were, the effect, the fix and the test.
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

## Check the fixes yourself

Start the app, then run the end-to-end demo. It sets up a product, checks the security defaults, and checks out twice with the same `Idempotency-Key`. Recorded output is in [docs/demo-output.txt](docs/demo-output.txt).

```bash
scripts/demo-checkout.sh http://localhost:8080
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

## Companion repository

The Angular admin fixes live in [pavansky/shopizer-admin-hardened](https://github.com/pavansky/shopizer-admin-hardened): valid Braintree transaction types, safe retries, the 0-based orders pager, store-aware password reset, a working runtime config and a reproducible build.

## Design limits, on purpose

- **Payment record lost in a crash.** If the process dies after the gateway charges but before the payment record commits, the reconciler cannot see the payment and marks the order failed. Closing that needs a gateway lookup per module. The reconciler logs every decision and counts it at `/actuator/prometheus`, so these cases show up.
- **Spring Boot 3.** The upgrade is deliberately deferred. See [ADR 0008](docs/adr/0008-defer-the-spring-boot-upgrade.md).

## License and credit

Shopizer is by the Shopizer team and is licensed under the Apache License 2.0; see [LICENSE.md](LICENSE.md). The original README is kept at [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md). Changes in this fork are by Pavan Kumar Guthikonda under the same license.
