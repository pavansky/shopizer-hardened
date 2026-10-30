# Gateway credentials are write-only, encrypted with an environment key, and audited

**Status:** accepted

## Context
Payment module keys were returned in plain text to any staff user, stored with AES-128-CBC and a static IV, and encrypted with a key committed to the repository.

## Decision
Secrets are masked on read (`****1234`); sending the mask back keeps the stored value. Only store admins can read or change them, and only for their own store. Values are encrypted with AES-256-GCM and a random IV per value, prefixed `v2:`, with the key from `SHOPIZER_ENCRYPTION_KEY`. Production profiles refuse to start without it. Values written by the old scheme are read once with `SHOPIZER_LEGACY_SECRET_KEY` and re-encrypted on the next save. Every change writes an append-only audit row (who, when, which key names, never values) and emails the store.

## Consequences
A database dump alone no longer exposes merchant credentials, and every change is attributable. Losing the key makes stored credentials unreadable, so it belongs in a secrets manager with backup. Next steps: key ids for rotation (`v3:<keyId>:`) and envelope encryption with a KMS.
