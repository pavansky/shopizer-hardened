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

## 3. Payment modules

| Module | Before | After |
|---|---|---|
| Braintree | Sale without `submitForSettlement`: funds held, never settled | Sale settles; real gateway status recorded |
| Stripe | Amount built by deleting "." from "1,234.56"; key in a global static | Minor units per currency from BigDecimal; key passed per request |
| Capture / refund API | Returned 200 and did nothing | Returns 501 until implemented |

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
