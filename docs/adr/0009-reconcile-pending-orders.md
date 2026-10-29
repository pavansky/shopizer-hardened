# Reconcile pending orders instead of trusting the happy path

**Status:** accepted

## Context
Persist-then-charge means a crash or failed finalise leaves an order in PAYMENT_PENDING with stock reserved.

## Decision
A scheduled job settles pending orders older than a grace period. A recorded payment finalises the order; no payment fails it, returns the stock and releases its idempotency key. Each order is claimed with a database compare-and-set in its own transaction. Outcomes are Micrometer counters.

## Consequences
Instances can run the job concurrently. A charge that the gateway took but the process never recorded is still marked failed; closing that needs a per-gateway lookup and is visible through the counters and logs.
