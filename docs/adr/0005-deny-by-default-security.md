# Deny by default, typed tokens

**Status:** accepted

## Context
Paths outside /private and /auth ran unauthenticated, and one token type passed both chains.

## Decision
A last security chain allows only listed public storefront endpoints and denies the rest. Tokens carry token_type and each chain checks it. The signing key comes from the environment and must be 64+ bytes.

## Consequences
A missed public endpoint returns 401 or 403 instead of silently being open, which is the safer failure. Every user logs in once after upgrade.
