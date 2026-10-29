# Refunds are ledger operations with a row lock

**Status:** accepted

## Context
Capture and refund were stubs. Refunds rewrote the order total, and Stripe recorded them as captures.

## Decision
Refunds never change the order total. They add a REFUND transaction with the gateway id. The remaining amount is captured minus refunded. Capture and refund run in one transaction that locks the order row first, so concurrent requests are serialised. Braintree voids unsettled sales and refunds settled ones.

## Consequences
The ledger is the source of truth for what was taken and returned. Holding the row lock during the gateway call serialises operations per order only, which is acceptable for admin-driven refunds.
