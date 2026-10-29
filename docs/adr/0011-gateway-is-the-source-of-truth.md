# The payment gateway is the source of truth

**Status:** accepted

## Context
The checkout persists the order, charges, then records the payment. If the process dies after the charge but before the record commits, the shop has no trace of the money. The reconciler then saw no payment and failed the order. A timeout was also handled as a decline, so a charge that went through could leave a failed order and released stock.

## Decision
Before calling the gateway, the checkout commits a payment attempt with a unique reference. The reference goes to the gateway: Braintree order id, Stripe idempotency key and metadata. A Stripe PaymentIntent id is stored before the call.

Outcomes are classified. A decline fails the order. A timeout, IO error or unexpected error marks the attempt UNKNOWN, keeps the order PAYMENT_PENDING with its stock, and the API answers 202 with the order id.

The reconciler never fails an order whose attempt has no clear outcome without asking the gateway. Confirmed: record the payment and finalise. Failed: fail. Not found: fail only after 30 minutes. No answer: wait, and after a day move the order to PAYMENT_REVIEW.

Stripe webhooks are the fast path. `POST /api/v1/webhooks/stripe/{store}` verifies the signature with the store's webhook secret, finds the attempt by PaymentIntent id and runs the same reconciliation for that one order. It asks the gateway again rather than trusting the event body, so a replayed or duplicated event changes nothing. The scheduled reconciler stays the safety net for missed events.

A second pass looks at open attempts on orders already failed or cancelled. If the gateway took the money, the module voids or refunds it and the refund is recorded.

## Consequences
A lost record no longer means a failed order with a paid customer. Some orders now wait longer before they settle. PAYMENT_REVIEW needs a person. The legacy Stripe Charge module now has a lookup through the Charge search API on the `attempt_ref` metadata, and compensates by refunding the charge with the `comp-` idempotency key. Stripe's search index can lag new charges by up to about a minute, so NOT_FOUND is only acted on after `notFoundSafeMinutes`; a charge created without an attempt reference still answers PENDING and goes to review. If an automatic refund fails, nothing changes and the case is logged and counted as compensation_needed.
