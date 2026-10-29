# Fix catalogue

Each row is a defect found in Shopizer 3.2.7 (commit 6a4a0a6, tagged `baseline-3.2.7` here), where it was, what it caused, and how it is fixed. Compare any fix with `git diff baseline-3.2.7 -- <file>`.

## Money

| # | Defect in 3.2.7 | Where | Effect | Fix | Test |
|---|---|---|---|---|---|
| 1 | Braintree sale sent without `submitForSettlement` | `BraintreePayment.java:336` | Default checkout only authorises; funds released after about a week, merchant never paid | Sale submits for settlement; real status stored in `TRNAPPROVED`; admin form values corrected | `BraintreePaymentTest` |
| 2 | Stripe amount built by deleting "." from a `Locale.US` formatted string | `Stripe3Payment.java:320`, `StripePayment.java` | Totals of 1,000 or more fail; zero-decimal currencies (JPY) charged 100x | `StripeAmounts.toMinorUnits(BigDecimal, currency)` from Stripe's currency table | `StripeAmountsTest` |
| 3 | Stripe secret key written to the process-wide `Stripe.apiKey` | `Stripe3Payment.java:77` | Concurrent checkouts in two stores can run under the other store's key | `RequestOptions` per call with the store's key | `StripeRequestOptionsTest` |
| 4 | Capture and refund endpoints return `null` with HTTP 200 | `OrderPaymentApi.java:342` | Admin shows "refunded successfully" while nothing happens | Real capture and refund: admin role, store-scoped lookup, amount in (0, remaining], 409 when exhausted, order row locked for the whole operation, REFUNDED on full refund | `OrderFacadePaymentTest`, `PaymentServiceRefundTest`, demo script |
| 5 | Payment charged before the order row exists | `OrderServiceImpl.java:147` | Any failure after the charge leaves the customer charged with no order | Order saved as `PAYMENT_PENDING` in its own transaction, then charged, then finalised with a compare-and-set or marked `PAYMENT_FAILED`. `processOrder` runs outside the XML transaction advisor so each step commits on its own | `OrderServiceImplCheckoutTest` |
| 6 | No idempotency on checkout | `OrderApi.java:343,402` | A retried request charges twice | Optional `Idempotency-Key` header, stored with a unique constraint; replay returns the original order, mismatch returns 409 | `OrderFacadeIdempotencyAndStatusTest` |
| 7 | Oversell check commented out; stock looked up by the order line id | `OrderServiceImpl.java:197-203` | Stock goes negative; wrong product decremented | Conditional `UPDATE ... WHERE quantity >= :q` before charging | `OrderServiceImplCheckoutTest` |
| 8 | Any order status change allowed | `OrderFacadeImpl.java:1632` | CANCELED can return to PROCESSED; nothing is reversed | `OrderStatusTransitions` table; illegal moves return 409 | `OrderStatusTransitionsTest` |
| 9 | Order columns narrower than their sources (64 vs 120, 50 vs 96) | `OrderProduct.java:37`, `Order.java` | Long product names or emails fail the insert after payment | Columns widened; Flyway migration `V2` | `OrderColumnLengthTest` |

## Tenant isolation and security

| # | Defect in 3.2.7 | Where | Effect | Fix | Test |
|---|---|---|---|---|---|
| 10 | SKU queries: `a OR b AND store` and an unbound store join | `ProductRepository.java:25` and price and availability repositories | One store resolves or deletes another store's products and prices | Parenthesised, filtered on the product's own store | `ProductSkuTenantIsolationTest` |
| 11 | Token validation checks only username and expiry; one secret for both audiences | `JWTTokenUtil.java:173` | A customer token with an admin's username passes the admin API | `token_type` claim; each chain rejects the other type and untyped tokens | `JWTTokenTypeTest` |
| 12 | Admin login provider compared the password with the username | `JWTAdminAuthenticationProvider.java:62` | Broken credential check | `passwordEncoder.matches` | `JWTAdminAuthenticationProviderTest` |
| 13 | No default security chain | `MultipleEntryPointsSecurityConfig.java:327` | Unauthenticated `DELETE /api/v1/products/group/{code}` returned 200 | Lowest-priority chain with an explicit public allow-list; everything else denied | Manual smoke test, see README |
| 14 | All actuator endpoints public | `application.properties` | Anyone could change log levels and clear caches | Only health and info public; metrics and prometheus need the admin role | Manual smoke test |
| 15 | CORS reflects any Origin with credentials, in three places | `AuthenticationTokenFilter.java:59`, `filter/CorsFilter.java`, `@CrossOrigin("*")` on `ContentAdministrationApi` | Any site can call the API as the logged-in user | Allow-list from `CORS_ALLOWED_ORIGINS` | `AuthenticationTokenFilterCorsTest` |
| 16 | Signing key `aSecret`, 7-day tokens | `authentication.properties` | Forgeable tokens that live a week | `JWT_SECRET` required at 64+ bytes; 1-hour lifetime | `JWTTokenTypeTest` |
| 17 | Reviews trust `customerId` from the request body | `ProductReviewApi.java` | Any customer posts or edits reviews as any other | Reviewer taken from the authenticated principal; others' reviews return 404 | `ProductReviewApiTest` |

## API correctness

| # | Defect in 3.2.7 | Where | Effect | Fix | Test |
|---|---|---|---|---|---|
| 18 | `/category/{id}` and `/category/{friendlyUrl}` share one pattern | `CategoryApi.java:65,79` | `GET /api/v1/category/tables` returned 500 | `{id:\d+}` constraint (same for products) | `CategoryApiRoutingTest` |
| 19 | `sendError(400)` without `return` | `ProductApi.java:131` | Product saved after replying 400 | Returns immediately | Review |
| 20 | Same PATCH mapped twice | `ProductApi.java:483` | Sort-order handler unreachable | `params = "order"` | Review |
| 21 | Deleting an option value filters on the option id | `ProductAttributeRepository.java:18` | Deletes unrelated products' attributes | Filters on `pov.id` | `ProductOptionValueDeleteTest` |

## Resilience and operations

| # | Defect in 3.2.7 | Where | Effect | Fix | Test |
|---|---|---|---|---|---|
| 22 | Event multicaster starts a new thread per event | `AsynchronousEventsConfiguration.java:22` | Event bursts exhaust native threads | Bounded `ThreadPoolTaskExecutor`, caller-runs back pressure | `AsynchronousEventsConfigurationTest` |
| 23 | `@Async` on a raw fixed pool never shut down | `AsyncConfig.java` | Lost work on shutdown | Managed pool with graceful shutdown | `AsyncConfigTest` |
| 24 | No timeouts on UPS, USPS, Google, reCaptcha, SMTP | several | One stalled vendor pins request threads | 3 s connect, 10 s read, overridable | `ShippingQuoteTimeoutsTest`, `CaptchaRequestUtilsTimeoutTest` |
| 25 | Shared mail sender mutated per send | `DefaultEmailSenderImpl.java:55` | Mail sent with another store's SMTP credentials | Per-send sender; SMTP timeouts | `DefaultEmailSenderImplTest` |
| 26 | `hbm2ddl.auto=update`, no migrations | profiles | Schema drifts silently; columns never altered | Flyway on mysql, gcp, cloud with `validate` | Migration files |
| 27 | Docker image runs as root on a deprecated base with the H2 file baked in | `sm-shop/Dockerfile` | Mutable state in the image | Non-root, temurin JRE, health check | Docker build |
| 28 | CI pushes images from every branch through a private script | `.circleci/config.yml` | Unreviewed releases | GitHub Actions `mvn verify` on every push and PR | CI badge |
| 29 | Search mapping read with `ClassPathResource.getFile()` | `SearchServiceImpl.java:513` | Search cannot start from the packaged jar | Read as a stream | MySQL profile boot |
| 30 | Default profile needs a `database.properties` that is git-ignored | `.gitignore`, `shopizer-core-config.xml:85` | A fresh clone cannot start | Committed H2 file-database default | Fresh-clone boot |

## Gaps closed in the second pass

| # | Defect | Where | Effect | Fix | Test |
|---|---|---|---|---|---|
| 31 | Stripe refunds recorded as CAPTURE for the full order total with a null gateway id | `Stripe3Payment.java:423`, `StripePayment.java` | Ledger says money was taken when it was returned | REFUND entry with the actual amount and the Stripe refund id | `Stripe3RefundTest` |
| 32 | Braintree refund called on unsettled transactions | `BraintreePayment.refund` | Gateway rejects the refund | Void when authorised or submitted, refund when settled | `BraintreeRefundTest` |
| 33 | Ledger "last transaction" chosen alphabetically by type | `TransactionServiceImpl.java:104` | Already-captured authorisations offered for capture again | Ordered by creation time, then id | `TransactionServiceOrderingTest` |
| 34 | Orders stuck in `PAYMENT_PENDING` after a crash | checkout | Stock held, client cannot retry | Scheduled reconciler with compare-and-set claims; old idempotency keys purged | `PendingOrderReconcilerTest` |
| 35 | `ServiceException(String)` dropped its message | `ServiceException.java:31` | Errors read "ServiceException: null" | Text kept as the exception message | Whole suite |
| 36 | Reset link host taken from the request; two-day, reusable, date-only tokens | `UserFacadeImpl.java:822` | Reset tokens can be sent to an attacker's host | Allow-listed origins, one-hour single-use tokens | `UserFacadeResetOriginTest` |

Admin defects, fixed in [shopizer-admin-hardened](https://github.com/pavansky/shopizer-admin-hardened): Braintree form values, retry of POST requests, 1-based pager on a 0-based API, password reset hard-coded to DEFAULT, malformed `env.js` and `/api/api/v1` default, and an unreproducible build with no lockfile.

## Found while verifying

These came up when running the fixed build end to end, and are fixed in the same commits:

- **Self-deadlock in the first version of persist-then-charge.** The payment record was written in the outer transaction and updated from an inner one, so the finalise step waited on its own lock. Fixed by keeping `processOrder` out of the long transaction.
- **Lock outside a transaction.** The payment service is not covered by the XML transaction advisor, so the first refund lock ran with no transaction. Capture and refund are now explicitly transactional.
- **Ambiguous routes.** `{id:\d+}` and `{friendlyUrl}` both matched numeric paths. The slug route now requires a non-digit.
- **Health component hidden.** Hiding components made `/actuator/health/ping` return 404, and the storefront uses it at startup. Components stay visible and details stay hidden.

## How it was verified

- `mvn verify`: 167 tests, 0 failures (21 skipped are upstream `@Ignore`).
- [scripts/demo-checkout.sh](../scripts/demo-checkout.sh) against a fresh instance. Output: [demo-output.txt](demo-output.txt).
- `mysql` profile on an empty MySQL 8 database: Flyway applies V1 to V3, Hibernate validates the mapping, and the application starts.

