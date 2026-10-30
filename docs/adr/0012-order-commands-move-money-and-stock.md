# Order commands move money and stock; status updates do not

**Status:** accepted

## Context
The admin could set any status. Setting CANCELED or REFUNDED changed a label but refunded nothing and returned no stock, so the order, the ledger and the inventory disagreed.

## Decision
Statuses that move money or stock are commands, not updates. `POST /orders/{id}/cancel` refunds or voids the payment, returns the stock and sets CANCELED, and a second cancel is 409. A full refund also returns stock. Orders in PAYMENT_REVIEW are settled by `recheck` (ask the gateway again) or `confirm` (a person confirms the payment). `PUT /status` is kept for fulfilment moves only (ORDERED, PROCESSED, DELIVERED), with a compare-and-set so it cannot overwrite a concurrent cancel. The admin offers only the legal next steps from `GET /orders/{id}/transitions`.

## Consequences
Every status the merchant sees matches the ledger and the stock. Shopify-style separate financial and fulfilment statuses are the next step; this change gets the behaviour right without a schema change.
