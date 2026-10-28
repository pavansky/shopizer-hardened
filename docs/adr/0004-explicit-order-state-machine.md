# Explicit order state machine

**Status:** accepted

## Context
Any status could be set from any status, so refunds and cancellations had no defined meaning.

## Decision
A transition table in OrderStatusTransitions, checked before every status change. Illegal moves return 409.

## Consequences
Admin screens that relied on free moves now get an error. Adding a status means adding its transitions.
