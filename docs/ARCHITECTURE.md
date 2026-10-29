# Architecture: before and after

Shopizer keeps its shape: one Spring Boot application built from five Maven modules, with plug-in payment and shipping modules. What changes is how a request is secured, how money moves, and how the system behaves when a dependency is slow.

## 1. Request security

### Before (3.2.7)

```mermaid
flowchart LR
  R[Request] --> M{URL prefix}
  M -->|/api/v*/private/**| A[Admin JWT chain]
  M -->|/api/v*/auth/**| C[Customer JWT chain]
  M -->|anything else| O[No security chain<br/>runs unauthenticated]
  A --> V[validateToken:<br/>username + expiry only]
  C --> V
  O --> X[DELETE /products/group/x -> 200<br/>actuator loggers, caches writable]
```

- Admin and customer tokens were signed with the same secret (`aSecret`) and checked only for username and expiry, so a customer token with an admin's username passed the admin check.
- Any path outside the two prefixes ran with no security at all.
- Every actuator endpoint was exposed without authentication.
- CORS echoed back any Origin with credentials allowed.

### After

```mermaid
flowchart LR
  R[Request] --> M{URL prefix}
  M -->|/api/v*/private/**| A[Admin chain<br/>token_type = admin]
  M -->|/api/v*/auth/**| C[Customer chain<br/>token_type = customer]
  M -->|everything else| D[Default chain<br/>explicit public allow-list<br/>deny all others]
  D --> H[/actuator/health, /info public<br/>other actuator: admin role/]
```

- Tokens carry a `token_type` claim and each chain rejects the other type.
- The signing key comes from `JWT_SECRET`; startup fails if it is shorter than 64 bytes. Tokens last 1 hour, not 7 days.
- A last, lowest-priority chain permits only listed storefront endpoints and denies the rest.
- CORS uses an allow-list (`CORS_ALLOWED_ORIGINS`).

## 2. Checkout and money

### Before

```mermaid
sequenceDiagram
  participant Client
  participant API as OrderApi
  participant Svc as OrderServiceImpl
  participant PSP as Payment gateway
  participant DB
  Client->>API: POST /cart/{code}/checkout
  API->>Svc: process(order)
  Svc->>PSP: charge
  PSP-->>Svc: approved
  Svc->>DB: insert order
  Note over Svc,DB: insert fails (e.g. name longer than 64 chars)<br/>-> customer charged, no order
  Svc->>DB: decrement stock (oversell check commented out)
  Client->>API: retry after timeout
  API->>Svc: process again -> second charge
```

### After

```mermaid
sequenceDiagram
  participant Client
  participant API as OrderApi
  participant F as OrderFacade
  participant Steps as OrderCheckoutSteps
  participant PSP as Payment gateway
  participant DB
  Client->>API: POST checkout + Idempotency-Key
  API->>F: processOrder
  F->>DB: claim key (unique merchant+key)
  alt key already completed
    F-->>Client: 200, original order, no charge
  end
  F->>Steps: reserve (own transaction)
  Steps->>DB: insert order PAYMENT_PENDING<br/>conditional stock decrement
  Steps-->>F: committed
  F->>PSP: charge (per-store key, timeouts)
  alt payment fails
    F->>Steps: markFailed -> PAYMENT_FAILED, stock returned
  else payment ok
    F->>Steps: finalise -> ORDERED, transaction stored
  end
  F->>DB: bind key to order id
```

- The order exists before any money moves, so every charge has an order to reconcile against.
- Stock is reserved with `UPDATE ... WHERE quantity >= :q`, so two checkouts cannot both take the last unit.
- Status changes follow an explicit transition table (`OrderStatusTransitions`); illegal moves return 409.

### Payment attempts: the gateway decides

A payment attempt is committed before every gateway call. Its reference goes to the gateway, so a charge the shop never recorded can still be found. See [ADR 0011](adr/0011-gateway-is-the-source-of-truth.md).

```mermaid
sequenceDiagram
  participant Svc as OrderServiceImpl
  participant Steps as OrderCheckoutSteps
  participant PSP as Payment gateway
  participant DB
  Svc->>Steps: createPendingOrder
  Steps->>DB: order PAYMENT_PENDING, stock reserved
  Svc->>Steps: beginPaymentAttempt (own transaction)
  Steps->>DB: attempt INITIATED, attempt_ref, Stripe intent id
  Svc->>PSP: charge (Braintree orderId / Stripe idempotency key = attempt_ref)
  alt approved
    Svc->>Steps: markPaymentSucceeded
    Steps->>DB: order ORDERED, Transaction, attempt SUCCEEDED (one transaction)
  else declined
    Svc->>Steps: markPaymentFailed
    Steps->>DB: order PAYMENT_FAILED, stock returned, attempt FAILED
  else timeout / IO / unexpected error
    Svc->>Steps: markPaymentOutcomeUnknown
    Steps->>DB: attempt UNKNOWN, order stays PAYMENT_PENDING, stock held
    Svc-->>Svc: 202 "payment_pending" with the order id
  end
```

### Recovering stuck orders and late charges

```mermaid
flowchart TB
  J[Reconciler every 5 min] --> Q[PAYMENT_PENDING older than 15 min, batches of 100]
  Q --> P{Transaction stored or<br/>attempt SUCCEEDED?}
  P -->|yes| F[CAS to ORDERED or PROCESSED]
  P -->|no| A{Latest attempt}
  A -->|none or FAILED| X[CAS to PAYMENT_FAILED<br/>return stock<br/>release idempotency key]
  A -->|INITIATED or UNKNOWN| L[Ask the gateway]
  L -->|SUCCEEDED| C[Record the missing Transaction<br/>CAS to ORDERED or PROCESSED<br/>'reconciled: payment confirmed by gateway']
  L -->|FAILED| X
  L -->|NOT_FOUND, older than 30 min| X
  L -->|NOT_FOUND young, PENDING,<br/>or lookup error| W[Leave it]
  W -->|older than 24 h| R[CAS to PAYMENT_REVIEW<br/>ERROR log, stock held]
  J --> O[Open attempts on PAYMENT_FAILED<br/>or CANCELED orders]
  O --> G[Ask the gateway]
  G -->|SUCCEEDED| V[Void or refund, key comp-attempt_ref<br/>REFUND Transaction<br/>'compensated: late charge returned']
  G -->|cannot compensate| N[Change nothing<br/>ERROR log, compensation_needed]
  G -->|FAILED or NOT_FOUND| Z[Close attempt FAILED]
```

- Each order is claimed with a compare-and-set, so two instances never settle the same order.
- Each order runs in its own transaction, so one bad order does not stop the batch.
- An order is never failed while its payment outcome is unknown and the gateway has not answered.
- Counters at `/actuator/prometheus`: `shopizer_reconciliation_orders_total{outcome=...}` with finalised, failed, skipped, error, confirmed_by_gateway, review, lookup_error, compensated, compensation_needed.

| Module | Status lookup | Compensation |
|---|---|---|
| Braintree | Search by order id = attempt_ref | Void if unsettled, refund if settled |
| Stripe (PaymentIntents) | Retrieve the intent stored before the call | Cancel if only authorised, refund if captured |
| Stripe (legacy Charges) | None: always PENDING, goes to review | None: compensation_needed |
| Money order | No gateway | None |

## 3. Payment modules

| Module | Before | After |
|---|---|---|
| Braintree | Sale without `submitForSettlement`: funds held, never settled | Sale settles; real gateway status recorded |
| Stripe | Amount built by deleting "." from "1,234.56"; key in a global static | Minor units per currency from BigDecimal; key passed per request |
| Capture / refund API | Returned 200 and did nothing | Store-scoped, amount-checked, row-locked operations recorded in the ledger |
| Braintree refund | Refund on unsettled sales failed | Void when unsettled, refund when settled |

## 4. Runtime behaviour

```mermaid
flowchart TB
  subgraph Before
    E1[Every event: new thread<br/>SimpleAsyncTaskExecutor] 
    H1[UPS, USPS, Google, reCaptcha, SMTP:<br/>no timeouts]
    S1[Schema: hbm2ddl update]
    D1[Docker: root, H2 file baked in]
  end
  subgraph After
    E2[Bounded pools<br/>CallerRuns back pressure<br/>graceful shutdown]
    H2[3s connect / 10s read<br/>on every outbound call]
    S2[Flyway migrations<br/>hbm2ddl validate on mysql, gcp, cloud]
    D2[Docker: non-root, temurin JRE,<br/>health check, no baked data]
  end
  E1 --> E2
  H1 --> H2
  S1 --> S2
  D1 --> D2
```

## What stays the same, on purpose

- **One deployable.** Five Maven modules in one jar fit a small team. Splitting it adds network failures, not features.
- **Payment and shipping module interfaces.** Per-store configuration behind a plug-in interface is the best seam in the product.
- **Per-language description tables and populators.** The gap is a missing default-language fallback, not the schema.
- **SM_SEQUENCER ids and Ehcache.** Change them when lock-wait metrics or a second instance require it.
