# Keep the modular monolith

**Status:** accepted

## Context
Shopizer ships as one Spring Boot jar from five Maven modules. Splitting it would mean separate deploys, network calls and distributed transactions for a team of four to six engineers.

## Decision
Keep one deployable. Fix behaviour inside it: security chains, checkout ordering, timeouts.

## Consequences
Changes stay reviewable as ordinary diffs. Module boundaries are enforced by Maven today; an ArchUnit rule is the next step if boundaries erode.
