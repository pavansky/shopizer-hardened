# Idempotency-Key on checkout

**Status:** accepted

## Context
Browsers and mobile clients retry on timeouts. Without a key, each retry is a new charge.

## Decision
Accept an optional Idempotency-Key header. Store merchant, key, cart, request hash and order id under a unique constraint. Replays return the original order; a different cart or body returns 409.

## Consequences
Clients that send the header get exactly-once checkout. Clients that do not keep today's behaviour. Old keys need periodic cleanup.
