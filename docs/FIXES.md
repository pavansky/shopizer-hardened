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
| 37 | No record of a gateway call before it was made | `OrderServiceImpl.process` | A crash after the charge left no trace; the reconciler failed a paid order | Payment attempt committed before the call; reference sent to the gateway; reconciler asks the gateway | `OrderServiceImplCheckoutTest`, `PendingOrderReconcilerTest`, `BraintreeStatusLookupTest`, `Stripe3StatusLookupTest` |
| 38 | Timeout treated as a decline | `OrderServiceImpl.process` | Order failed and stock released while the customer may have been charged | Unknown outcomes keep the order `PAYMENT_PENDING`; API answers 202; `PAYMENT_REVIEW` after a day | `OrderServiceImplCheckoutTest`, `PendingOrderReconcilerTest` |
| 39 | Orphan charges on failed or cancelled orders | reconciler | Money taken for an order the shop gave up on | Second pass voids or refunds through the module with a `comp-` idempotency key; otherwise logged and counted | `PendingOrderReconcilerTest`, `Stripe3StatusLookupTest` |
| 40 | No gateway push path: outcomes only learned by polling | none | Orders wait for the next reconciler run | Signed Stripe webhook that re-verifies with the gateway and settles one order | `StripeWebhookApiTest` |
| 41 | Legacy Stripe Charge module had no gateway lookup | `StripePayment.lookup` | Every unclear Charge-API order waited a day and went to manual review; late charges were never returned | Lookup through the Charge search API on `metadata['attempt_ref']` (captured, held, pending, failed, refunded, not found); NOT_FOUND only counts after `notFoundSafeMinutes` because the search index lags; compensation refunds with a `comp-` idempotency key | `StripeChargeLookupTest`, `StripeChargeSandboxIT` |
| 42 | Gateway SDKs years out of date (stripe-java 19.5.0, braintree-java 2.73.0) | `pom.xml` | Old pinned Stripe API version; no Charge search; missing fixes | stripe-java 34.0.0 (API version 2026-09-30.endive), braintree-java 3.55.1, gson 2.10.1; capture sends `statement_descriptor_suffix` because card payments reject a full descriptor on current API versions | Whole suite, `*SandboxIT` with `-Psandbox` |
| 43 | Async order email read the request thread's entities | `EmailTemplatesUtils.sendOrderEmail` | Under concurrent checkouts, placed orders reported errors ("ResultSet is from UPDATE") | Order and customer reloaded by id in the email thread's own read-only transaction | 8-way concurrent checkout, 3 rounds |
| 44 | Out-of-stock surfaced as 500 or 503 | `OrderApi` | Clients retry a sold-out checkout | 409 with the reason | Concurrent checkout |
| 45 | javax.mail 1.4.7 stalled mid-DATA | `pom.xml` | Order emails never sent | Jakarta Mail 1.6, managed by Spring Boot | Mailpit receives both confirmations |
| 46 | Firewall-rejected URLs returned 500 | security config | False server-error alerts | 400 via `HttpStatusRequestRejectedHandler` | Manual |
| 47 | `Idempotency-Key` blocked by CORS | `AuthenticationTokenFilter` | Browser checkout from the storefront origin failed | Header allowed | Preflight check |

Admin defects, fixed in [shopizer-admin-hardened](https://github.com/pavansky/shopizer-admin-hardened): Braintree form values, retry of POST requests, 1-based pager on a 0-based API, password reset hard-coded to DEFAULT, malformed `env.js` and `/api/api/v1` default, and an unreproducible build with no lockfile.

## Found while verifying

These came up when running the fixed build end to end, and are fixed in the same commits:

- **Self-deadlock in the first version of persist-then-charge.** The payment record was written in the outer transaction and updated from an inner one, so the finalise step waited on its own lock. Fixed by keeping `processOrder` out of the long transaction.
- **Lock outside a transaction.** The payment service is not covered by the XML transaction advisor, so the first refund lock ran with no transaction. Capture and refund are now explicitly transactional.
- **Ambiguous routes.** `{id:\d+}` and `{friendlyUrl}` both matched numeric paths. The slug route now requires a non-digit.
- **Health component hidden.** Hiding components made `/actuator/health/ping` return 404, and the storefront uses it at startup. Components stay visible and details stay hidden.

## How it was verified

- `mvn verify`: 589 tests, 0 failures (21 skipped are upstream `@Ignore`).
- [scripts/demo-checkout.sh](../scripts/demo-checkout.sh) against a fresh instance. Output: [demo-output.txt](demo-output.txt).
- `mysql` profile on an empty MySQL 8 database: Flyway applies V1 to V3, Hibernate validates the mapping, and the application starts.
- Real MySQL 8: Flyway applies V1 to V4, the demo passes, and a simulated crash (order `PAYMENT_PENDING`, attempt `UNKNOWN`, two days old) is moved to `PAYMENT_REVIEW` by the reconciler instead of being failed.

## Third pass: the Tier 1 gaps from the world-class review

A review against Amazon, Flipkart, Shopify and Zalando found 83 verified gaps (see the gap review in the interview pack). The ten Tier 1 gaps, plus the cheapest Tier 2 items, are closed here. Each area was built on its own branch, attacked by a second reviewer, fixed and merged. Full notes, tests and what is still open: [TIER1-CHANGES.md](TIER1-CHANGES.md).

| # | Defect | Effect | Fix | Proof |
|---|---|---|---|---|
| 48 | Customer endpoints loaded customers by id without the store | Staff of one store could read, edit, move or delete another store's customers | Store-scoped lookups everywhere, 404 for another store's id; PUT never moves a record | Two-store integration test over every customer endpoint; `demo-tier1.sh` |
| 49 | Customer login and tokens were not bound to a store | The same email in two stores broke login; a token worked on any store | Login in the request's store; `store` claim in the customer JWT | `CustomerTokenStoreBindingTest` |
| 50 | Registration with a body id overwrote that account | Account takeover through `register` | The id in the body is ignored on register and create | Integration tests |
| 51 | Reviews and related products were not store-scoped | Another store's data on product pages | Store-scoped reads | Integration test |
| 52 | Tax basis compared a String with an enum | Every order taxed on the billing address | Switch on the enum | Golden tests |
| 53 | Tax class ignored; amounts dropped; `double` arithmetic | Wrong totals with two tax classes | Class-scoped rate queries, summed amounts, `BigDecimal` per line, discounts before tax, store setting for shipping tax | `TaxCalculationGoldenTest`, `TaxServiceImplGoldenTest` |
| 54 | `DEFAULT` tax class looked up across stores | A second store's `DEFAULT` broke taxed checkout everywhere | Store-scoped lookup; each new store gets its own `DEFAULT` | Golden tests |
| 55 | Gateway secrets returned in plain text to any staff user | Live Stripe/Braintree keys readable or replaceable | Masked on read (`****1234`), a masked value keeps the stored secret, admin role and same store required | `PaymentConfigurationApiIntegrationTest`; `demo-tier1.sh` |
| 56 | AES-128-CBC, static IV, key committed in six profiles | One database dump plus the public repo decrypts every merchant's credentials | AES-256-GCM, random IV, `v2:` prefix, key from `SHOPIZER_ENCRYPTION_KEY`; production profiles refuse to start without it; legacy values read once and re-encrypted | `EncryptionImplTest` with a fixed legacy vector |
| 57 | Credential changes left no trace | No answer to "who changed the keys?" | Append-only `CONFIGURATION_AUDIT` (V5) with key names only, plus an email to the store | Integration test; Mailpit |
| 58 | Status changes to CANCELED or REFUNDED moved no money or stock | Labels contradicted the ledger; stock leaked | `POST /orders/{id}/cancel` refunds or voids, restocks and sets CANCELED; `PUT /status` only for fulfilment moves | `OrderLifecycleIntegrationTest`; `demo-tier1.sh` |
| 59 | Admin "Update status" only wrote a history row | The merchant's main control did nothing | Admin calls the state machine with legal next steps only; Cancel, Re-check and Confirm payment buttons | Admin build; lifecycle tests |
| 60 | Full refund did not restock; PAYMENT_REVIEW could never be settled | Inventory too low; orders stuck forever | Restock on full refund; recheck and confirm commands for orders in review | Lifecycle tests |
| 61 | Product listing paged in memory over ~20 joined collections | 96.6 MB read per 15-product page on 1,001 products, 5.8 s p50, out of memory under load | Page ids in SQL, then batch-load; build fails on in-memory pagination | `ProductListingQueryTest`; 126 KB and 26 ms after; 749 rps under k6 |
| 62 | Slug lookup ignored the store; unknown slug gave 500 | Another store's product on a PDP | Scoped by store and language, 404 for unknown | `ProductSlugLookupTest` |
| 63 | A request held its DB connection for its whole life | A slow gateway drains the pool | Connections released after each transaction; 2 s pool wait, then 503 with Retry-After | `ConnectionReleaseTest`, `RestErrorHandlerPoolTimeoutTest` |
| 64 | Stripe and Braintree ran on SDK default timeouts (up to about 4 minutes) | One slow gateway stalls checkout for every store | 3 s connect, 15 s read, bounded retries, and a per-gateway bulkhead that fails fast with 503 before any charge | Unit and HTTP integration tests |
| 65 | Payment init took the amount from the request body | A client could set its own price | Amount from the server-side cart total; mismatch is 409 | `OrderPaymentApiInitTest` |
| 66 | No 3-D Secure | EEA, UK and Indian cards fail SCA | Stripe Payment Element with `confirmPayment`; `requires_action` is "not paid yet" (409 with the client secret), resumed with the same Idempotency-Key | Unit tests, jest, sandbox IT (needs keys) |
| 67 | Dashboard refunds and disputes never reached the shop | Ledger out of date | `charge.refunded`, `charge.refund.updated`, `charge.dispute.*` webhooks, idempotent by Stripe id | `StripeWebhookApiTest` |
| 68 | No rate limits; login revealed whether accounts exist | Card testing and credential stuffing | Per-address and per-account limits with 429 and lockout; identical answers for unknown and wrong-password; reset requests answer the same way | `RateLimitFilterTest`, `LockoutAuthenticationProviderTest`; `demo-tier1.sh` |
| 69 | Every `/private` endpoint needed only "logged in" | Any staff member could change payment keys or users | URL rules per permission group; a test fails the build if any `/private` endpoint is only covered by the generic rule | RBAC coverage test |
| 70 | Disabled admins and changed passwords kept access | Departed staff kept working tokens | Active check and credential-change time checked on every request (V7) | `AdminLoginAndSessionIntegrationTest` |
| 71 | No CSP; product HTML stored unsanitised | A stored script could skim cards | CSP and related headers on API, storefront and admin; OWASP sanitiser on save | Header checks; sanitiser tests |
| 72 | Audit did not record who acted | No forensics | `ADMIN_AUDIT` row for every mutating admin request; `modifiedBy` from the signed-in user | Audit tests |
| 73 | Backup script logged "backup ok" for a failed, empty dump | Silent data loss | `pipefail`, dump validation (gzip, size, table count), status file, freshness metric and `BackupStale` alert; the drill exits non-zero on any mismatch | Live: dump validated with 94 tables; `RESTORE DRILL PASSED` |
| 74 | Alerts went nowhere; the review alert cleared while orders were still in review | Pages reach no one | Alertmanager to email (Mailpit in the demo), Watchdog alert, review alert driven by a live gauge of orders in review | Live: Watchdog email received in Mailpit; review alert firing |
| 75 | No request ids; logs were plain text | No way to follow one request | `X-Request-Id` in and out, carried into async work; JSON logs with `LOG_FORMAT=json` | `RequestIdFilterTest` |
| 76 | No per-store health | A revoked key fails one merchant silently | Store tag on request metrics and a payment outcome counter; Grafana panels | Live Prometheus |
| 77 | No end-to-end test of the whole stack | Integration breaks found late | `e2e.yml` builds the stack, runs both demo scripts, the stuck-order simulation, the restore drill and five Playwright specs; Dependabot in all three repos | Local Playwright run: 6 of 6 passed |
| 78 | US$ on order pages of a CAD store; no focus outline; unlabelled controls; no canonical, JSON-LD, 404 or sitemap | Wrong receipts; inaccessible; invisible to search | Order currency, WCAG basics with an axe check in CI, canonical and Product JSON-LD, 404 page, `GET /api/v1/sitemap.xml` | Unit tests, axe check, `SitemapIntegrationTest` |
| 79 | `database.properties` for the default profile was git-ignored | A fresh clone could not start | Tracked | Fresh build |

### Still on the roadmap, deliberately

- **Search, promotions, fulfilment and returns.** Each is a multi-week build. See the gap review for the first step of each.
- **TOTP MFA for admins.** Rate limits, lockout, deactivation and token revocation are in place; MFA is next.
- **Verified email on registration.** Needed to close the last bit of account enumeration.
- **Real-gateway proof.** The sandbox suite, including the 3-D Secure test, runs as soon as test keys are set.
