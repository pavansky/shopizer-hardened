# Bounded executors and timeouts everywhere

**Status:** accepted

## Context
Unbounded thread creation and calls without timeouts turned one slow vendor into a full outage.

## Decision
Bounded pools with caller-runs back pressure and graceful shutdown. 3 s connect and 10 s read timeouts on the shipping, geocoding, reCaptcha and mail clients, overridable by property.

**Correction (third pass).** The first version of this record said every outbound call had timeouts. The payment gateways did not: Stripe and Braintree ran on their SDK defaults, up to about four minutes per checkout. They now use 3 s connect and 15 s read with bounded retries, and each gateway sits behind a bulkhead that answers 503 with Retry-After before any charge when it is saturated. A read timeout after the request was sent stays an unknown outcome (202), never a decline.

## Consequences
Slow vendors now fail fast with an error instead of hanging. Under saturation, event listeners run on the request thread.
