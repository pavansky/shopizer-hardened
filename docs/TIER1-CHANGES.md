# Tier 1 changes: detail by area
Each area was built on its own branch, reviewed by a second engineer who tried to break it, fixed, and then merged. This file keeps the full notes: what was wrong, what changed, the tests, and what is still open. The summary is in [FIXES.md](FIXES.md).

## Tenant isolation for customers, reviews and identity
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test):
1. Cross-store customer BOLA | CustomerFacadeImpl getCustomerById/update/deleteById/updateAddress, CustomerPopulator:104 | An admin of store A could read, overwrite, move or delete store B's customers by id | CustomerService.getById(id, store) for every lookup, 404 on mismatch, store kept on update | CustomerFacadeStoreScopeTest, CustomerStoreIsolationIntegrationTest
2. Account takeover through a body id | AuthenticateCustomerApi.register, CustomerFacadeImpl.registerCustomer/create (populator copies id, saveOrUpdate updates) | Unauthenticated register, or admin create, with another store's customer id overwrote that account and moved it; register returned a token for it | id cleared before populate/save on register and create | registerWithAnotherCustomersIdCreatesANewAccount, createIgnoresAnIdInTheBody, registrationIgnoresAnIdInTheBody
3. Customer login not store-bound | AbstractCustomerServices:59, AuthenticateCustomerApi 'TODO SET STORE', JWTCustomerAuthenticationManager | The same email in two stores broke login; a customer token worked against any store | Lookup in the request store; 'store' claim in the customer JWT (compared without case); token refused on another store; ambiguous unscoped getByNick returns null | CustomerTokenStoreBindingTest, CustomerServiceGetByNickTest, integration test
4. Registration answer for existing emails | AuthenticateCustomerApi register (409 message) | The 409 'already registered' message named the account | 200 with the {id, token} shape in both cases, no duplicate created, no message. PARTIAL: a token comes back only for a new email or a matching password, so existence can still be inferred | registeringAnExistingEmailCreatesNoDuplicateAndNamesNoAccount
5. Password change for another customer | POST /auth/customer/password | The username in the body was trusted | Must equal the signed-in principal, otherwise 404 | customerCanOnlyChangeTheirOwnPassword
6. Customer review writes across stores | CustomerFacadeImpl.createCustomerReview, CustomerReviewApi PUT route | A store A admin could post as a store B reviewer, or update any review by putting its id in a create; PUT never bound reviewId | Review id cleared, reviewer must be in the store, route fixed | CustomerFacadeStoreScopeTest, customerReviewsOfStoreBCannotBeWrittenThroughStoreA
7. Product reviews and related products across stores | ProductReviewApi create/getAll/update/delete, ProductRelationshipApi, ProductRelationshipRepositoryImpl.getByType | Store A listed store B's reviews and related items; a create could update any review by id | productService.findOne(id, store), reviewer checked against the store, body id cleared on create, store filter in the query, 404 for foreign ids | ProductReviewApiTest, productReviewsOfStoreBCannotBeWrittenThroughStoreA, reviewAndRelatedReadsOfStoreADoNotListStoreBData

Open item to record: account enumeration through register remains until email verification exists, or until register stops returning a token and the storefront logs in separately. Login and register have no throttling.

ADR or doc corrections: customer tokens now carry a 'store' claim next to token_type, and it is compared without case. Tokens issued before this change are refused, so shoppers log in again once. The storefront sends no store parameter and uses DEFAULT.

Demo talking points: 'One admin cannot see or overwrite another tenant's customers: you get a 404, not a 403, so the id is not confirmed.' 'An id in a create or register body is ignored, so register cannot take over an account.' 'A customer token is pinned to its store; replaying it at another store gives 401.' 'One integration test calls every customer endpoint and the review and related endpoints with another store's ids, so a new endpoint that forgets the scope fails CI.' Do NOT say 'register no longer reveals the account'; say instead that 'register no longer names the account, and full non-enumeration needs email verification'.

### Still open

- Full non-enumeration on register. A new email gets a token; an existing email with a wrong password gets {id:null, token:null}. Closing this means register must stop returning a token and the storefront must call /customer/login after a 200, or email verification must come first. The storefront is outside this workspace.
- Throttling on login and register. The codebase has no throttling to reuse, and it belongs to the abuse package. Register with a matching password acts as a login, with no lockout, the same as /customer/login today.
- Group (RBAC) checks on GET, PUT, PATCH and POST /private/customer and on the list. These belong to the RBAC package and adding them here could conflict with it.
- Product review status codes for a duplicate (500) and a rating above 5 (503), review moderation and pagination. These are outside this package; only the cross-store paths now return 404.
- The database.properties packaging gap in sm-shop/.gitignore. It predates this branch, and .gitignore is not a file I own.

### Risks

- Until the six order-API getByNick edits are merged, a shopper whose email is registered in two stores gets a not-authorized answer (no longer a 500) on authenticated order calls. This cannot happen in the single-store demo.
- Existing customer sessions are invalidated once after deploy, because tokens without the store claim are refused.
- Registration still lets a caller infer whether an account exists, from whether a token comes back. Register with a matching password works as a login with no lockout, the same as /customer/login.
- Product review create on /private routes now returns 404, where it used to return 503, for a reviewer or product of another store. An admin client that relied on 503 would see a different code. No known client does.
- AbstractCustomerServices.loadUserByUsername reads the store from the current request through RequestContextHolder. Outside a request it falls back to DEFAULT.
- The commits contain no Co-Authored-By trailer. The owner's instructions forbid AI or tool mentions in commits, and those instructions take precedence over the default attribution reminder.

## Tax calculation
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test):
- Tax basis ignored | TaxServiceImpl.calculateTax (old :119,126,133) | name() was compared with an enum constant and never matched, so every order was taxed on the billing address | switch on the enum; SHIPPINGADDRESS falls back to billing | TaxServiceImplGoldenTest basis tests, TaxCalculationGoldenTest.taxBasisSelectsTheAddress
- Tax class ignored | TaxRateRepository zone and province queries, TaxRateServiceImpl:51-58 | every rate of the zone applied to every class | tax class id predicate in both queries | TaxCalculationGoldenTest.twoTaxClassesInOneZoneAreTaxedSeparately, provinceRatesOfACountryWithoutZonesAreScopedByTaxClass (plus rateQueriesAreScopedByTaxClassAndIgnoreTheDescriptionLanguage once committed)
- Rates dropped by language | TaxRateRepository (td.language.id filter) | a rate with no description in the shopper's language was not applied | no language filter; label chosen in Java | TaxCalculationGoldenTest.rateWithoutADescriptionInTheShopperLanguageStillApplies
- Duplicate rate rows | rate queries with joined descriptions | one rate came back once per description | duplicates removed in memory (653565a) | TaxCalculationGoldenTest province test
- Consolidation dropped amounts | TaxServiceImpl old :271-283 | the result of amount.add was discarded | amounts summed into the TaxItem | golden tests
- Tax lines in rate-code order | TaxServiceImpl (old TreeMap by code) | a compound tax could be listed before the tax it compounds | lines ordered by tax priority (e20b3e5) | TaxServiceImplGoldenTest.taxLinesAreListedByPriorityNotByCode
- Unscoped DEFAULT lookup | TaxServiceImpl old :183,204; TaxClassRepository.findByCode | NonUniqueResultException once two stores have a DEFAULT class | getByCode(DEFAULT, store); unscoped lookup deprecated | TaxCalculationGoldenTest.eachStoreUsesItsOwnDefaultTaxClass; @AfterEach checks the unscoped lookup is never called
- Store without a DEFAULT class | TaxServiceImpl.defaultTaxClass | decided: its unclassified lines and shipping are not taxed, with a WARN. The single-class fallback added in 761b894 was removed in e8ef747 because it could charge a REDUCED or EXEMPT rate nobody configured | store creation should create DEFAULT (integrator) | TaxServiceImplGoldenTest.storeWithoutADefaultClass* (2 tests)
- Discount not taken off the tax base | OrderServiceImpl.caculateOrder (~350/411) | coupon orders were over-taxed | reductions passed as the order discount and allocated pro rata before tax; each discounted line stays between 0 and its amount (75d23fc) | OrderTotalTaxGoldenTest, TaxServiceImplGoldenTest, TaxCalculatorTest
- Shipping always taxed | TaxServiceImpl old :200-217 | ignored taxOnShipping and taxed free shipping | taxed only if ShippingConfiguration.taxOnShipping; free shipping never; handling only when charged | TaxServiceImplGoldenTest shipping, free-shipping and handling tests
- Double arithmetic | TaxServiceImpl old :254 | 9.975% of 100.00 came out as 9.97 | BigDecimal, HALF_UP to 2 dp per tax line | TaxCalculatorTest
- Piggyback base leaked | TaxServiceImpl old :248-257 | later plain rates were applied to the compounded base | compound = base + earlier taxes of the class | TaxServiceImplGoldenTest piggyback tests, TaxCalculatorTest
- Unrounded coupon | OrderServiceImpl.caculateOrder | subtotal showed as 143.999999999999999111... | reductions rounded HALF_UP to 2 dp | OrderTotalTaxGoldenTest

Known gaps (not fixed): (1) Coupon reductions are spread pro rata over all lines, not tied to their own line. This is the same with the current promo module, which applies one percentage to every line; a per-product coupon would move tax base between classes. (2) ONE_TIME additional prices are added to the subtotal but not to the tax base, so they are not taxed. This was already the case before this branch.

Rounding policy: amounts stay exact BigDecimal until a tax line is produced. Each tax line (one rate applied to one class base) is rounded HALF_UP to 2 dp. Lines with the same rate code are summed without rounding again. Discount allocations are rounded the same way, with the leftover cent put on the largest line. Coupon reductions are rounded HALF_UP to 2 dp before they reach the subtotal.

Before/after (from OrderTotalTaxGoldenTest). Cart: 1 x 100.00 standard 10%, 2 x 30.00 reduced 5%, 10% coupon (16.00), shipping 15.00, store taxes shipping.
- Before: tax 14.50, total 173.50.
- After: subtotal 144.00, tax 13.20 (STD 10.50 on 105.00, which is 90.00 + 15.00 shipping; RED 2.70 on 54.00), total 172.20.
- Same cart with free shipping: after, tax 11.70 and total 155.70.
- Quebec QST 9.975% on 100.00: before 9.97, after 9.98.

Extension point: TaxService.calculateTax(summary, customer, store, language, orderDiscount) is where an external engine (Avalara, Stripe Tax) plugs in. TaxCalculator keeps the built-in arithmetic separate and testable on its own.

### Still open

- The full backend suite was not re-run after e8ef747. It has to be run before merging: docker run --rm -v "/Users/pavansky/IdeaProjects/shz-work/tax/backend":/src -v shz-m2-tax:/root/.m2 -w /src maven:3.9-eclipse-temurin-11 mvn -B -o -fae install. Docker stopped responding during this round.
- The new H2 scenario in TaxCalculationGoldenTest.java is uncommitted and has not run. Before committing it, check that it passes on work/tax and fails on main (a copy of main with this test is ready in my scratchpad folder taxmain). Then commit it as 'Test the rate queries on their own against H2'.
- Nit 3 (tie each coupon reduction to its own line) is not done. OrderTotal has no link to the cart item, so this needs the ordertotal owner. It gives the same result with the current promo module.
- Nit 5 (ONE_TIME additional prices are not in the tax base) is not done. It was already the case before this branch and is out of scope; it is listed as a known gap.
- Creating a DEFAULT tax class when a store is created is still not done (file not owned; see needs_from_integrator).

### Risks

- The build is not verified after e8ef747, and the H2 test is uncommitted (see not_done). Do not merge until the full suite has been re-run.
- Behaviour change: a store with no DEFAULT tax class (any store created through the store API) charges no tax on products without a tax class, or on shipping, until store creation also creates a DEFAULT class.
- Behaviour change: with taxOnShipping=false (the default), shipping is no longer taxed. The configured tax basis now takes effect (default SHIPPINGADDRESS).
- Coupon orders now total less, because tax is computed after the discount. Other orders change only by 1-cent HALF_UP corrections.
- TaxClassService.getByCode(String) is kept but deprecated. Any new caller would hit NonUniqueResultException once two stores have the same code.
- Running the tests changes sm-shop/files/store/DownlaodRepository.dat. Restore it before committing.
- Docker on this machine stops responding under parallel builds. Background docker commands from this session (the main-code test run and a docker ps) may still be running.

## Payment gateway credentials
### What changed and how it is tested

FIXES.md rows:
| Payment gateway secrets returned in plain text | sm-shop api/v1/payment/PaymentApi.paymentModule | Any back-office user could read a store's live Stripe/Braintree/PayPal secrets | Every integration key is masked as ****+last4 unless it is on a known non-secret allowlist; a masked value on save keeps the stored secret (PaymentConfigurationSecrets, PaymentModuleConfigurationFacade) | PaymentConfigurationSecretsTest, PaymentModuleConfigurationFacadeTest, PaymentConfigurationApiIntegrationTest |
| Payment configuration endpoints had no role or store check | PaymentApi (0 checks across 3 /private mappings) | Any staff user, or an admin of another store, could swap gateway keys to divert payments | ADMIN or SUPERADMIN on the requested store required; 403 for role/store denial or a deleted user, 404 for an unknown store, 5xx (not 403) when the check itself fails | PaymentApiTest, PaymentConfigurationApiIntegrationTest |
| Static-IV AES-128-CBC with a committed key | sm-core modules/utils/EncryptionImpl; secretKey=7070200000000007 in all profiles | One DB dump plus the public repo decrypts every merchant's payment and shipping credentials; no integrity check | AES-256-GCM, random 12-byte IV, v2: prefix; key from SHOPIZER_ENCRYPTION_KEY; production profiles fail fast without it; legacy CBC read with SHOPIZER_LEGACY_SECRET_KEY and rewritten on the next save | EncryptionImplTest (including a fixed vector from the previous release) |
| Credential changes left no trace | payment configuration save path | No answer to who changed the gateway keys and when | CONFIGURATION_AUDIT (Flyway V5), append-only, key names only; email to the store address on every change | PaymentConfigurationApiIntegrationTest, PaymentConfigurationChangeNotifierTest |

Runbook line: Generate SHOPIZER_ENCRYPTION_KEY with openssl rand -base64 32 and keep it safe. Losing it makes the stored payment and shipping gateway credentials unreadable. On a database that holds configuration from before v2, also set SHOPIZER_LEGACY_SECRET_KEY=7070200000000007 until every payment and shipping configuration has been saved once.

Demo talking points: (1) GET /api/v1/private/modules/payment/stripe shows ****1234. Send it back unchanged and the stored secret stays the same. (2) A catalogue user, or the admin of another store, gets 403. (3) Change a key, then show the CONFIGURATION_AUDIT row (actor, module, 'publishableKey', no values) and the email in Mailpit at :8025. (4) The stored value starts with v2: and differs on every save because of the fresh IV. Without SHOPIZER_ENCRYPTION_KEY the mysql profile refuses to start. (5) A value written by the previous release still decrypts (pinned by a fixed test vector) and is upgraded on the next save.

### Still open

- Blocking item 2 (adding SHOPIZER_ENCRYPTION_KEY and SHOPIZER_LEGACY_SECRET_KEY to compose and .env.example): those files are not mine to edit. The exact lines are under needs_from_integrator and must land in the same merge.
- Admin UI password inputs (shopizer-admin-hardened src/app/pages/payment/services/*.json): out of scope for this package, see needs_from_integrator.
- Masking in ShippingConfigurationApi (carrier passwords and API keys are still returned in plain text): outside my files. Follow-up: reuse PaymentConfigurationSecrets.maskKeys/mergeKeys in the shipping read and write paths.
- Leftover container shz-secrets-review-db (mysql:8.4): an earlier review attempt created it, not this run, so I did not remove it. It can be removed with docker rm -f shz-secrets-review-db.
- Still open from before: key rotation and envelope encryption with a KMS (one v2 key, no key id yet; the prefix leaves room for v3:<keyId>:), a bulk re-encrypt migration (values are re-encrypted lazily on the next save), step-up authentication before credential changes, and an API or admin view of the audit table.
- Subject of commit c64ba73: its subject says 'Return 403 when payment configuration authorization cannot complete', and 772b164 narrows that to only the missing-user case. I left the earlier commit as it is rather than squash it, so that each step stays reviewable. 772b164's body explains the change.

### Risks

- Deployment break: once merged, the mysql, gcp, cloud and aws profiles will not start without SHOPIZER_ENCRYPTION_KEY. The compose and .env changes must land in the same merge.
- Existing MySQL demo volumes hold payment and shipping configuration encrypted with CBC. Without SHOPIZER_LEGACY_SECRET_KEY=7070200000000007, reading payment modules (checkout) and shipping configuration (shipping quotes) fails until each one is saved again. An older build cannot read v2 values after a rollback.
- History rewrite: the hashes of the last five commits changed (b1b5eb7 is now b52ea3a, 3e5add6 is now c64ba73, plus the three new commits). Anyone holding the old hashes of work/secrets needs to reset to the new branch tip. The branch was never pushed.
- A failure while checking the user (for example the database is down) now returns 5xx instead of 403. This is intended, but the error is visible to the caller as a server error.
- Users in ADMIN_RETAIL, ADMIN_ORDER and ADMIN_CATALOGUE lose access to the payment configuration screens. admin@shopizer.com (SUPERADMIN) is unaffected.
- Any real secret that starts with **** would be treated as unchanged (very unlikely). Publishable keys are masked too.
- The email is sent asynchronously after commit, and a send failure is only logged. The default H2 profile has no SMTP, so the email always fails there (expected).
- The integration test creates store PAYCFGB, users pay-catalog@test.com and pay-admin-b@test.com, and an inactive stripe configuration on DEFAULT in the shared test context. The full suite is green.
- The leftover container shz-secrets-review-db is still running on the host (not created by this run).

## Order lifecycle
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test):
- Status change to CANCELED/REFUNDED moved no money or stock | OrderFacadeImpl.updateOrderStatus | labels contradicted the ledger; stock leaked on admin cancel | PUT /status accepts only ORDERED/PROCESSED/DELIVERED, and only from a settled order; new POST /orders/{id}/cancel refunds, voids or returns the charge, restocks and sets CANCELED | OrderCommandServiceCancelTest, OrderCheckoutStepsLifecycleTest, OrderLifecycleIntegrationTest
- Status update could overwrite a concurrent cancel | OrderFacadeImpl.updateOrderStatus (unconditional merge, no @Version) | order DELIVERED with money refunded and stock back | compare-and-set from the read status in OrderCheckoutSteps.markStatus; 409 on a miss | OrderCheckoutStepsLifecycleTest, OrderFacadeLifecycleTest
- Cancel of an unconfirmed payment promised an automatic refund it could not do | OrderCommandServiceImpl.cancelUnsettled | customer misinformed, charge could stay with the merchant | automatic wording only when the module can look up and compensate; otherwise "refunded by hand", ERROR log, compensation_needed | OrderCommandServiceCancelTest, OrderLifecycleIntegrationTest
- Second concurrent cancel surfaced as a payment error | OrderCommandServiceImpl.cancelSettled | 502-style error instead of 409 | processRefund validation failure maps to 409 | OrderCommandServiceCancelTest
- Full refund did not return stock | OrderFacadeImpl.refundOrder | inventory lower than reality | restock only when that refund set REFUNDED | OrderFacadeLifecycleTest, OrderLifecycleIntegrationTest
- PAYMENT_REVIEW could never be settled | OrderCheckoutStepsImpl claims from PAYMENT_PENDING only; modules without lookup | orders frozen in review | POST /payment/recheck may claim REVIEW; POST /payment/confirm records an audited manual settlement | PendingOrderReconcilerRecheckTest, OrderCommandServiceCancelTest, OrderLifecycleIntegrationTest
- History POST recorded any status | OrderFacadeImpl.createOrderStatus | history contradicted Order.status | note only for the current status (409 otherwise) | OrderFacadeLifecycleTest
- No lifecycle emails; pending-page promise broken; status token showed a date | facade, reconciler, EmailTemplatesUtils | customer never told | status email on status change, cancel and refund; PendingOrderPaidEvent sends the confirmation (reconciler and manual confirm); token carries the status label | OrderFacadeLifecycleTest, PendingOrderConfirmationTest, EmailTemplatesUtilsStatusEmailTest
- Order list name/phone filter crossed stores | OrderRepositoryImpl | other stores' orders visible | parenthesised OR | OrderLifecycleIntegrationTest.orderOfAnotherStoreIsNotFound
- Customer-order endpoints unscoped | OrderApi /private/orders/customers/{id}, /auth/orders | cross-store customer data | group and store check; store-scoped getByNick | OrderLifecycleIntegrationTest
- Admin showed US$ on CAD orders | order-details.component.html, order-invoice.html | wrong currency | currency pipe with order.currency | admin build

ADR 0011 correction (replace "PAYMENT_REVIEW needs a person"): A PAYMENT_REVIEW order is settled by a person in one of three ways:
- Re-check (POST /payment/recheck). It asks the gateway again and may claim REVIEW orders.
- Confirm by hand (POST /payment/confirm). It needs a reason, records the order total as a captured payment with the reference, writes a history row naming the admin, closes the attempt as SUCCEEDED and sends the confirmation. Use it for money orders or for charges seen in the gateway dashboard that the module cannot look up.
- Cancel. A charge the gateway reports is returned. When the outcome cannot be checked, the history says to refund it by hand, and the case is counted as compensation_needed.
PUT /status never moves PAYMENT_PENDING or PAYMENT_REVIEW orders.

Runbook: demo-checkout.sh now ends with oak-bench quantity 5, not 4, because the full refund returns the unit. Update any text that says 4.

API change notes for clients:
- PUT /status returns 409 on CANCELED, REFUNDED, PAYMENT_*, on unpaid source orders, and on a concurrent change.
- POST /history returns 409 for a status other than the current one.
- /auth/orders returns 401 for a customer of another store.

Demo talking points:
1. GET /transitions on an order.
2. PUT CANCELED returns 409 pointing to /cancel.
3. Cancel a money-order order: it is refunded, stock goes back to 5, status is CANCELED, and the status email arrives.
4. Run simulate-stuck-order.sh. Re-check payment leaves it in PAYMENT_REVIEW with 'not confirmed yet'. Then either use "Confirm payment received" with a reason (order goes to ORDERED, confirmation email, history shows who and why), or Cancel (history says to refund by hand, because money order has no lookup).

### Still open

- Smoke run of scripts/demo-checkout.sh and simulate-stuck-order.sh on port 8104: not run. sm-shop/src/main/resources/database.properties is git-ignored and missing from this worktree, so the jar cannot start on the default profile. The HTTP integration tests cover the same money-order checkout, cancel, refund, re-check and confirm flows on H2. On a quiet host with that file in place, expect oak-bench to end at quantity 5.
- Store-scoped admin of another store (nit 2): not added. The cross-store HTTP test still uses the superadmin with ?store=DEFAULT against an order moved to another store. A store-admin user fixture needs the user-creation API and group setup outside the order package.
- i18n labels and Nebular dialogs (nit): not done. The labels ('Cancel order', 'Re-check payment', 'Confirm payment received') are English, and prompts use window.prompt/confirm, because src/assets/i18n is outside src/app/pages/orders.
- Not done by design: no financial_status column, no Flyway V6, no customer self-cancel. Manual confirmation is limited to PAYMENT_REVIEW; PAYMENT_PENDING orders still go through re-check only.

### Risks

- The commit history was rewritten in both repos to drop the trailer. Anyone who based work on the old SHAs (37861b3 onward in the backend, 3bd2f1c in the admin) must rebase. The old heads are saved in the scratchpad (backend-head-before-rewrite.txt, admin-head-before-rewrite.txt), and nothing was pushed.
- The manual payment confirmation trusts the admin's statement; the history row (user, reference, reason) is the only audit. It is limited to PAYMENT_REVIEW and to the refund groups (SUPERADMIN, ADMIN, ADMIN_ORDER).
- Cancel is still not atomic end to end. The refund or gateway call commits before the CANCELED step. A lost race after a gateway return is recorded with recordCompensation and answers 409. With the status update now compare-and-set, the known way for markCanceled(from=REFUNDED) to miss is gone, but a reconciler or second refund racing it could still leave the order REFUNDED and give 409.
- markStatus uses the request's EntityManager under open-in-view, like markCanceled. The facade no longer dirties the Order before the call, so no unconditional write is flushed alongside the conditional one.
- A full refund restocks even for DELIVERED orders whose goods were not returned. This follows the spec, but can overstate stock.
- The demo-checkout.sh smoke run is still unconfirmed end to end; see not_done.

## Catalog read path and connections
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test). These replace or extend the previous round's rows:
- Listing paged in memory over ~20 collection fetches (HHH000104) | ProductRepositoryImpl.listByStore | every product page read the whole store: 96.6 MB from MySQL per 15-product page on 1,001 products, 5.8 s p50 | two-phase: page ids in SQL with a shared FROM/WHERE for the count, then ProductGraph per-collection loads + default_batch_fetch_size=50 | ProductListingQueryTest; fail_on_pagination_over_collection_fetch=true in sm-core tests
- Products without inventory in the listing (regression in the two-phase listing, fixed before merge) | listingFromWhere | one product whose inventory was deleted made every listing page it was on fail (v1 503, v2 400: 'No applicable inventory') | exists(ProductAvailability) in the shared WHERE, as the old inner join did | ProductListingQueryTest.productWithoutAvailabilityIsNeitherCountedNorListed
- Count OR escaped the store filter (available=false) | ProductRepositoryImpl count query | other stores' future-dated products were counted (latent: the API only sends available=true) | parenthesised | ProductListingQueryTest.unavailableFilterStaysInsideTheStore (gives 2 on the old query, 1 on the new)
- listByStore set the page size twice | ProductServiceImpl:349 | page argument ignored | setStartPage(page) | ProductServiceListingPageTest
- Slug lookup unscoped; 500 on unknown slug | ProductRepositoryImpl.getByFriendlyUrl | store B's product could render on store A; an unknown slug gave 500 'Index 0 out of bounds' | scoped by store, request language first and then the store's default language; he/id/yi codes normalised; null -> 404 | ProductSlugLookupTest, ProductSlugLanguageCodeTest, CatalogReadHttpTest
- Cartesian product detail loader | getById / slug loader | bytes grew with the product of collection sizes (165.7 KB for a product with 10 images and 5 variants) | product row + descriptions, then one statement per collection; relationships no longer loaded | PARTLY MET: 62.3 KB for that product, but 59.7 KB against 54.9 KB before for a small one (1 description, 3 images, 4 attributes, 3 variants); per-statement metadata (about 4 KB) dominates small products | ProductSlugLookupTest
- show_sql in production profiles | profiles/*/database.properties | ~86% of log bytes | ${DB_SHOW_SQL:false} | DatabaseProfileSettingsTest
- 30 s pool wait, 500 on pool timeout | DataConfiguration, RestErrorHandler | requests queued 30 s, then 500 | connectionTimeout 2000 ms; 503 + Retry-After: 2 for exceptions that reach RestErrorHandler. ProductApi's list endpoint (and other catch-all controllers) still send 503 without Retry-After | RestErrorHandlerPoolTimeoutTest
- Connection pinned for the whole request under open-in-view | Spring default DELAYED_ACQUISITION_AND_HOLD | stalled gateway calls drain the pool | DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION; OSIV stays on | ConnectionReleaseTest
- Public catalog never cacheable | Spring Security no-store default | CDN cannot absorb catalog reads | CatalogCacheControlFilter with an explicit route allow-list (anonymous GET/HEAD only; errors, Authorization, signup token and store-unique checks keep no-store) | CatalogCacheControlFilterTest, CatalogReadHttpTest

Demo behaviour change: a product URL in a language with no translation now falls back to the store's default-language slug (200). Only a slug that exists in neither language, or belongs to another store, returns 404.

MEASUREMENT, this round (requirement 3, product page). MySQL 8.4 on a private docker network. The same database was used for both jars. Seeded with scripts/demo-checkout.sh, then 'PRODUCTS=20 scripts/seed-catalog.sh http://localhost:8105' (normal products: 1 description, 3 images, 4 attributes, 3 variants), then 'START=2001 PRODUCTS=1 IMAGES=10 VARIANTS=5 scripts/seed-catalog.sh http://localhost:8105' (rich product seed-02001). Before jar: git archive f940406 (merge base), built with mvn -B -o -DskipTests package. After jar: this branch. Setup: docker network create shz-perf-net; docker run -d --name shz-perf-db --network shz-perf-net -e MYSQL_DATABASE=SALESMANAGER -e MYSQL_USER=shopizer -e MYSQL_PASSWORD=$PW -e MYSQL_ROOT_PASSWORD=$PW mysql:8.4 --lower_case_table_names=0 --character-set-server=utf8mb4. App: docker run -d --name shz-catalog-perf --network shz-perf-net -p 8105:8080 -v <jar>:/app.jar -w /tmp -e SPRING_PROFILES_ACTIVE=mysql eclipse-temurin:11-jdk java -Ddb.jdbcUrl='jdbc:mysql://shz-perf-db:3306/SALESMANAGER?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true' -Ddb.user=shopizer -Ddb.password=$PW -jar /app.jar. Method: 3 warm-up calls, then 20 sequential curl calls. MySQL bytes and selects per request are the deltas of SHOW GLOBAL STATUS Bytes_sent and Com_select, divided by 20.
Results (response bytes | MySQL bytes per request | selects | p50/p95 ms), before -> after:
- v1 /api/v1/product/seed-00010 (normal): 4,709 | 54,946 -> 59,690 | 4.0 -> 14.3 | 26.6/38.4 -> 32.1/91.6
- v1 /api/v1/product/seed-02001 (rich): 6,548 | 165,691 -> 62,263 | 4.0 -> 14.2 | 25.1/29.1 -> 27.8/68.1
- v2 /api/v2/product/name/seed-00010: 7,038 | 96,742 -> 79,574 | 15.1 -> 15.2 | 34.7/47.6 -> 29.6/100.4
- v2 /api/v2/product/name/seed-02001: 10,766 | 225,271 -> 83,691 | 23.1 -> 15.1 | 32.1/41.5 -> 30.5/54.5
- v1 slug in fr, falling back to the en slug: 200 both | 54,946 -> 59,678
- unknown slug: before HTTP 500, 31,872 B, 2 selects; after HTTP 404, 8,001 B, 2 selects
Latencies at this size are within noise (shared Docker VM). The byte counts are the meaningful figures.

Listing and k6 numbers from the previous round still hold (1,001 products): list page 96.6 MB -> 126 KB per request, p50 5.8 s -> 26 ms; under k6 the before jar was OOM-killed and the after jar served 749 req/s (products p95 62 ms, product p95 35 ms, category p95 24 ms).

Demo talking points: one product without inventory can no longer take the storefront listing down. Product URLs keep working when the shopper switches language. Only listed catalog routes are cached at a CDN; signup tokens and uniqueness checks never are. On a moderately rich product, the product page now reads 2.7x fewer bytes from MySQL than before.

### Still open

- Requirement 3 is only partly met. On a product with 1 description, 3 images, 4 attributes and 3 variants, the product page reads 59.7 KB from MySQL for a 4.7 KB response, against 54.9 KB before. The fixed cost is about 8 KB per request (the store lookup); the rest is about 4 KB of result-set metadata per statement, because each per-collection statement selects the product row again. The loader is better from moderately rich products upward (2.7x fewer bytes with 10 images and 5 variants). It is not 'close to the response size' for small products. Going further means loading the collections from the child side with batch initialisation. The previous attempt measured that at 60 KB with 22 statements, so it is no clear win.
- spring.jpa.open-in-view=false: unchanged from the previous round (kept on, with release after each transaction). The reason is in the earlier report.
- The leftover containers from the first attempt, shz-catperf-db (running) and shz-catalog-perf-db (exited), and the network shz-catperf-net were not removed: the session's permission policy blocked the removal again. Command for the integrator: docker rm -f -v shz-catperf-db shz-catalog-perf-db && docker network rm shz-catperf-net
- I did not re-run k6 in this round. These changes add one exists subquery to the listing and remove statements from the detail page; the k6 numbers from the previous round are in doc_notes. The MySQL dataset this round had 22 products, so a list-page latency figure from it would not be meaningful.

### Risks

- Slug fallback: a request in language X with the default-language slug now returns the product (the old pre-scoping behaviour for that case). If two products in the store share a slug, the one in the request language wins over the default-language one, and the oldest id wins within a language.
- The listing hides products with no inventory row for admin listings too, as the old page query did. Admins have to open such a product by id or SKU to add inventory.
- The cache allow-list must be kept up to date: a new public catalog route is no-store until someone adds it. A route listed by pattern, for example /api/v2/product/{sku}, is cacheable for any value in that segment.
- The detail loader no longer initialises Product.relationships. Nothing reads them today, but code that reads them outside a session would hit a LazyInitializationException.
- Small products still cost slightly more MySQL bytes per product page than before (+9% on the measured product), because of per-statement metadata.
- seed-catalog.sh re-runs now make three reads per existing product (attributes, variants and images counts), so a re-run over 1,000 seeded products takes longer than before.

## 3-D Secure, gateway timeouts, bulkhead and webhooks
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test), in addition to last round's five:
- Guest payment init ignored the shopper's address for tax | OrderPaymentApi init | with address-based tax every card checkout got 409 at init, or an intent below the order total that failed at capture | init accepts the guest's billing/delivery (checkout body shape), converted with the checkout's CustomerPopulator into a transient customer; total computed with it | OrderPaymentApiInitTest guest* tests, stripePayment.test.js
- Unknown or foreign ?quote= on init | OrderPaymentApi init | 500 for an unknown id; another cart's quote accepted | quote must exist and belong to the cart, else 400 | OrderPaymentApiInitTest unknown/foreign quote
- 3DS resume did not check ownership | OrderServiceImpl process() | another cart sending a waiting pi_ could capture that customer's order, or fail it and release its stock | resume or supersede only from the same CART_CODE, else 409 payment_intent_in_use with the waiting order untouched; Stripe3Payment declines an intent whose cart_code metadata names another cart, before any client secret is returned | OrderServiceImplCheckoutTest, Stripe3ActionRequiredTest, PaymentErrorResolverTest
- Resume used the current cart lines | OrderServiceImpl process() | a decline after a same-total resume released stock for unreserved lines | resume only when skus and quantities match, else fail the waiting order with its own lines and place a new one | OrderServiceImplCheckoutTest changedLines...
- 409 retry ignored the client secret; guest account retry failed | storefront Checkout.js, stripePayment.js | the challenge could not be resumed reliably; 'customer already exist' | confirmCardPayment(clientSecret) from the 409, retry with the same key without password fields, 409 action branch first | stripePayment.test.js
- Non-instant refunds never reached the ledger | StripeWebhookApi | refunds pending at charge.refunded were skipped for good | charge.refund.updated handled by the same idempotent path | StripeWebhookApiTest
- 409/503 checkout wiring untested over HTTP | sm-shop ITs | demo talking points unproven | PaymentCheckoutAnswersIntegrationTest on the guest and signed-in endpoints | itself
Runbook: Stripe webhook endpoint https://<host>/api/v1/webhooks/stripe/<storeCode>; events payment_intent.succeeded, payment_intent.payment_failed, payment_intent.canceled, charge.refunded, charge.refund.updated, charge.dispute.created, charge.dispute.closed; signing secret in the stripe3 module key webhookSecret. Error codes: 409 payment_action_required (clientSecret, paymentIntentId, paymentIntentStatus, orderId), 409 payment_intent_in_use, 503 payment_gateway_busy with Retry-After.
ADR note: why confirmCardPayment and not handleCardAction. Payment Element intents use automatic confirmation; handleCardAction only works for manual confirmation.
Demo talking points: (1) a tampered init amount gives 409, and an unknown quote gives 400; (2) 3DS: only after one browser run on test keys, pm_card_threeDSecure2Required gives the challenge, and the same Idempotency-Key resumes the same order with no second stock reservation; another cart cannot reuse that PaymentIntent (409 payment_intent_in_use); (3) gateway slow gives 202 unknown outcome and the reconciler settles it, while gateway saturation gives 503 with Retry-After and nothing charged (proven over HTTP by the IT); (4) a Dashboard refund, including a delayed one, reaches the shop ledger. A dispute on a paid order adds history only; PAYMENT_REVIEW applies only to orders still PAYMENT_PENDING.

### Still open

- The 3-D Secure challenge after a 409 has still not been run in a browser with Stripe test keys. It is covered by jest, the Java sandbox IT (skipped without keys) and the HTTP integration test with a stubbed payment service. Run it once with pm_card_threeDSecure2Required before using demo talking point 2.
- Dispute evidence is still not collected in the shop. A paid order gets a history entry and no status or flag change (no status allowed from ORDERED/DELIVERED for this).
- scripts/simulate-stuck-order.sh was not re-run (it needs deploy/.env and the compose MySQL stack). Its code path is unchanged.
- sm-shop/files/store/DownlaodRepository.dat is modified in the working tree by the test and smoke runs. It is not committed. My attempt to restore it with git checkout was blocked by the sandbox, so please discard it before merging.

### Risks

- The storefront 3DS completion after a 409 (confirmCardPayment with the returned secret) is unit-tested only. It needs one browser run with Stripe test keys. Money order, the demo default, does not touch it.
- Resume now requires identical skus and quantities. Cart items whose SKU column is null (old carts) would never match, so they are superseded instead of resumed: the waiting order is failed with its own stock returned and a new order is placed. That is safe, but the customer gets a new order id.
- The Stripe cart_code check only applies when both the intent metadata and the checkout carry a cart code. Intents created outside the shop's init are not checked.
- The guest init address must convert like checkout's. An unknown country or zone code returns 400 at init, which is the same conversion failure checkout would have. The card form shows the message.
- PaymentCheckoutAnswersIntegrationTest swaps a spy into OrderServiceImpl.paymentService in the shared context and restores it in @After. If a future test runs in parallel in the same JVM, it could see the spy during that window. Surefire runs these classes sequentially today.
- Unchanged from last round: bulkhead permits are per JVM and per module; a requires_payment_method intent can be failed by the reconciler after its grace period while the customer is still choosing another card (safe, new order id); charge.refunded and charge.refund.updated each call Stripe to list refunds.

## Rate limits, staff permissions and security headers
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test), added to the previous set:
- Lockout keyed only on account+address | LoginAttemptTracker / LockoutAuthenticationProvider | an attacker with many addresses had no cap on guesses per account | second counter per account alone (20 per 15 min, 5 min lock), same 401 body | LockoutAuthenticationProviderTest.failuresSpreadOverManyAddressesLockTheAccountEverywhere
- A full lockout tracker stopped recording failures | LoginAttemptTracker | 100k junk usernames switched the lockout off for everyone | bounded LRU, oldest key dropped | LockoutAuthenticationProviderTest.aFullTrackerDropsTheOldestKey...
- Disabled accounts answered faster than wrong passwords on customer and /services logins | DaoAuthenticationProvider pre-checks | timing showed the account exists and is disabled | PasswordFirstDaoAuthenticationProvider hashes first | PasswordFirstDaoAuthenticationProviderTest
- Two beans named authenticationProvider | MultipleEntryPointsSecurityConfig | bean overriding picked which provider admin login used | adminAuthenticationProvider; unused customer bean removed | full suite, AdminLoginAndSessionIntegrationTest
- Customer password change unthrottled; Basic + login charged twice | AbuseProtectionConfig / RateLimitFilter | current-password oracle; double counting | password-change budget; charge once per request | RateLimitFilterTest, RateLimitWiringIntegrationTest
- CSP fixed to localhost:8080 / 9090 | admin docker/nginx.conf, storefront conf.d/default.conf | any other API port or host blocked all API calls | same-host any-port source added; other hosts by start-up substitution | nginx -t + header check

ADR note (lockout design): the account+address lock keeps an attacker on one address from locking the owner out. The account-wide lock caps guessing spread over many addresses. Someone who knows a username can still keep that account locked by sending 20 failures every 5 minutes. That trade-off is accepted because the lock is short and both thresholds are properties.

Test totals for release notes: 276 run, 0 failures, 21 skipped (sm-core 134/13, sm-shop 140/8 plus the 2-test rate-limit-wiring execution).

Demo talking points: (1) a burst of logins gets 429 with Retry-After; (2) unknown and real reset emails get identical answers; (3) a catalog-only user gets 403 on refunds, shown with curl, not the admin UI, until the RestErrorHandler change lands; (4) deactivate an admin and the next request gets 401; (5) a product saved with <img onerror> has it stripped; (6) SELECT * FROM ADMIN_AUDIT after demo-checkout. Keep the user-create screen out of the demo until the sec/private filter change lands.

### Still open

- /api/v1/sec/private returns 401 to admin tokens (should-fix 2). This is not fixable in my files: AuthenticationTokenFilter decides which paths get admin-token resolution, and it is owned by another package. The exact change is under needs_from_integrator. The 'superadmin gets 200 there' assertion can only pass after that change, so I did not add a test that fails now. Until it lands, keep the user-create and customer-add screens out of the demo (both call getListOfGroups).
- Store endpoints answer 401 to authenticated non-admin staff (should-fix 3). The 401 comes from MerchantStoreApi via userFacade.authorizedGroup throwing UnauthorizedException, and RestErrorHandler maps that to 401. Neither file is mine, and the catalog-perf package is also editing RestErrorHandler. The exact change is under needs_from_integrator. Demo: show the catalog-only 403s with curl or PrivateApiAuthorizationCoverageTest, not by signing in to the admin UI as that user.
- Re-sanitising HTML already stored (nit) is not done. It needs either a one-off rewrite of the product, content, category and manufacturer description tables, or sanitising in the readable populators. Both touch files or data outside this package, and a bulk rewrite of merchant content the day before a demo carries more risk than it removes. New and edited content is sanitised on save.
- TOTP MFA for admins: not started (secret column, enrolment flow, second login step and admin UI screens). Too large to do well alongside the rest.
- scripts/simulate-stuck-order.sh was not run (it needs the compose MySQL stack). Nothing in this package touches the reconciler.
- Rate limits and lockout are per instance and in memory (no shared store).

### Risks

- Account-wide lockout: someone who knows a staff username can keep that account locked by sending 20 wrong passwords every 5 minutes from two or more addresses. During the demo, avoid scripted bad-password loops against admin@shopizer.com, or restart the app to clear the in-memory state.
- Until the RestErrorHandler change lands, non-admin staff who sign in to the admin UI get 401 from the store endpoints and are signed out. Superadmin is unaffected.
- Until the AuthenticationTokenFilter change lands, /api/v1/sec/private/groups and /permissions return 401, and the admin UI signs out on the user form and the customer add page. This was already broken on main.
- The same-host CSP source allows connections to any port on the UI's own host name. script-src is unchanged (nonce plus allow-list).
- The build starts one more Spring context in a separate surefire JVM (about 15 s). If someone rewrites the surefire section and drops the exclude, the wiring test runs with limits off in the shared JVM and fails, so the mistake shows up rather than passing silently.
- HTML stored before this release is not re-sanitised until it is next saved.
- Behind a proxy without shopizer.security.trusted-proxies, all clients share one rate-limit bucket and lockout address.
- Removing the unused customer authenticationProvider bean leaves the JWTCustomerAuthenticationProvider class unused in the codebase (it was already unused in practice).

## Backups, alerting, request ids and end-to-end CI
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test), new this round:
- Two stacks shared one dump directory | deploy/docker-compose.yml, restore-drill.sh | With a second stack from the same checkout, each stack's retention deleted the other's dumps, and the drill could compare stack B's dump with stack A's database | BACKUP_DUMPS_DIR (default ./backup/dumps), read by both compose and the drill | Live: ops stack dumps only in backup/dumps/shz-ops, drill --fresh PASSED from there; empty dir -> FAILED, exit 1
- BackupStale paged during a fresh start | deploy/prometheus/alerts.yml | The first backup waits for the schema (about 16 min on a slow host), longer than for: 10m | for: 30m | promtool: no page at 20m, page at 45m
- Remove-from-cart spec could not fail | storefront tests/e2e/specs/04-remove-from-cart.spec.js | test.fail covered the whole test | Server-side test passes for real; expected failure set only after the DELETE check | Local run 6/6, one of them an expected failure
- Error page logs had no request id | RequestIdFilter | The ERROR dispatch was skipped after the MDC was cleared | Filter runs on ERROR dispatch, id kept in a request attribute | RequestIdFilterTest.errorDispatchKeepsTheIdOfTheFailedRequest

deploy/README.md addition (replaces last round's second-stack note): "Second stack on one machine: copy .env.example and change the ports, BACKEND_SUBNET, IMAGE_TAG and BACKUP_DUMPS_DIR (e.g. ./backup/dumps/<name>). Run with -p <name> or COMPOSE_PROJECT_NAME, and pass ENV_FILE=<file> to simulate-stuck-order.sh and restore-drill.sh."

Grafana note: the backup panel shows 'never' until the first good backup.

The other doc notes and demo talking points from the earlier report still apply.

### Still open

- Nit scheduler: not changed in code. Spring 5.3 @Scheduled cannot pick a scheduler per method, and the only global fixes are a property in application.properties, which I do not own, or a SchedulingConfigurer bean, which would change the reconciler's scheduling too. The property is listed under needs_from_integrator.
- demo-checkout.sh is not my file, so it still prints no ORDER_ID= line and its refund section still targets order 1 by number. e2e.yml now reads the id from the output, but the job still needs a new database, which a new runner and down -v provide.
- I did not rebuild the api image or test the error-dispatch request id live. The stack ran on the existing ops image; the change is covered by the unit test.
- The GitHub Actions workflows were not run on GitHub, as instructed.

### Risks

- BackupStale now reports a real backup outage 20 minutes later than before (30m 'for' instead of 10m).
- An existing deployment keeps using ./backup/dumps, because that is the default. A second stack is isolated only if its env file sets BACKUP_DUMPS_DIR.
- Remove-from-cart test 2 turns red once Cart.js is fixed and stays red until its test.fail line is removed (intended).
- e2e.yml has still never run on GitHub; its timings are estimates. demo-checkout.sh's hard-coded order 1 means the job needs a new database.
- The scheduler sharing described above stays until the pool-size property lands; under load the review gauge can lag by one reconciler pass.
- Risks from the earlier report still apply: store-tag cardinality (switch shopizer.metrics.store-tag.enabled to false for many stores) and the retry every 5 minutes while backups keep failing.

## Storefront currency, accessibility and SEO
### What changed and how it is tested

FIXES.md rows (Defect | Where | Effect | Fix | Test):
- Order history shows US$ for a CAD store | storefront RecentOrder.js, OrderDetails.js | Wrong currency on customer receipts | Use ReadableOrderTotal.total, else format the value with ReadableOrder.currency (util/money.js) | src/util/money.test.js
- Focus outline removed globally | _common.scss '*:focus{outline:none !important}' | Keyboard users cannot see focus (WCAG 2.4.7) | :focus-visible ring plus a skip-to-content link | npm run a11y
- Icon-only controls have no name; dropdowns not operable by keyboard | IconGroup, MobileSearch, HeaderSocial, ShopTopAction, Cart, LanguageCurrencyChanger | Screen readers announce only 'button' (4.1.2); the language list opens only on focus and has no state | aria-labels; aria-expanded, aria-controls and Escape on the account, cart and language dropdowns | npm run a11y, LanguageCurrencyChanger.test.js
- Form labels not linked to fields | checkout and account forms | Fields announced with no name (1.3.1, 3.3.2) | util/a11y.js links each label to its field at runtime | src/util/a11y.test.js, npm run a11y
- Checkout button contrast 1.53:1 | theme colour #D1D1D1 behind white text | Fails 1.4.3 | Theme colour darkened to the nearest shade reaching 4.5:1 (util/contrast.js); grey text uses $muted-text | src/util/contrast.test.js
- No canonical, structured data or 404 | ProductDetail.js, Category.js, App.js | Weak SEO; unknown URLs render an empty shell | Canonical from APP_PUBLIC_URL or the page origin, JSON-LD Product/Offer with an image fallback, NotFound view | src/util/seo.test.js, seo.public-url.test.js
- No sitemap | backend | Crawlers find products only through client-rendered links | GET /api/v1/sitemap.xml?store=CODE: store-scoped, visible categories without hidden ancestors, available products, row-limited queries, 5-minute per-store cache | SitemapApiTest, SitemapIntegrationTest (cross-store, hidden, not yet available; HTTP test enabled with the permit rule)
- No automated accessibility check | storefront CI | Regressions ship unnoticed | 'accessibility' CI job running axe-core with timeouts; fails on serious/critical violations, on pages without catalogue content and on API calls with no fixture | CI job

Demo talking points: tab from the address bar to show the skip link and focus ring, then press Enter on the language toggle and Escape to close it. Open a product and show the JSON-LD in view-source or the Rich Results test. Visit /nonexistent to show the 404 page. Run curl -i localhost:8080/api/v1/sitemap.xml?store=DEFAULT, once the permit rule is in, to show XML and Cache-Control max-age=3600. npm run a11y prints 0 violations on 5 pages and fails if a page renders empty.

Note on diffs: git diff main..work/cx in backend may show a one-line docs/SYSTEM-DESIGN.md change. That comes from main moving ahead (84569f4), not from a work/cx commit.

### Still open

- Enabling the HTTP-level sitemap test: it needs the permit rule in MultipleEntryPointsSecurityConfig, which I do not own. The test is written and disabled, and the steps are under needs_from_integrator.
- Adding APP_PUBLIC_URL to the root .env: that file is outside my ownership. Without the key there, env.sh will not write a runtime APP_PUBLIC_URL. The build-time REACT_APP_PUBLIC_URL and a hand-edited public/env-config.js both still work.
- Backend smoke run on this round: not repeated. The reviewer's smoke run with demo-checkout.sh passed on this branch, and since then only the read-only sitemap controller and query have changed. The full suite is green.
- Carried over: form labels are still linked at runtime by src/util/a11y.js rather than by explicit htmlFor/id in Checkout.js. The axe check still covers only the 5 pages the brief named.

### Risks

- Sitemap cache: a catalogue change can take up to shopizer.sitemap.cache-seconds (default 300) to appear in the sitemap. The cache is in memory and per instance, holding one entry per existing store.
- The row limit counts rows per language. With more than 10 store languages, a very large catalogue could list slightly fewer than 50,000 items. That cannot happen with the demo data.
- Until the permit rule is added, the sitemap answers 401 over HTTP and the skipped count is 22 instead of 21.
- The axe content expectations are tied to current markup: .product-wrap, .product-details-content h2, .cart-table-content rows, .billing-info-wrap inputs and the category menu link. A redesign of these pages must update EXPECT in a11y/axe-check.js. A new API call on these pages must be recorded into fixtures.json, or the check fails, which is intended.
- The language list no longer opens on keyboard focus alone. Keyboard users press Enter or Space on the toggle, which is the standard disclosure pattern. Hover behaviour is unchanged.
- Carried over: the runtime label helper and the automatic darkening of the theme colour, as described in the earlier report.
