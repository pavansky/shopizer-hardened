# Persist the order before charging

**Status:** accepted

## Context
In 3.2.7 the payment call runs before the order insert. A failure after the charge leaves money taken with no record.

## Decision
Insert the order as PAYMENT_PENDING and reserve stock in a committed transaction, then charge, then finalise. Each step is a REQUIRES_NEW method on a separate bean so the XML transaction advisor and self-invocation cannot merge them.

## Consequences
Every charge has an order to reconcile. A checkout uses two short transactions instead of one long one. Orders stuck in PAYMENT_PENDING need a reconciliation job, which is the next step.
