# Bounded executors and timeouts everywhere

**Status:** accepted

## Context
Unbounded thread creation and calls without timeouts turned one slow vendor into a full outage.

## Decision
Bounded pools with caller-runs back pressure and graceful shutdown. 3 s connect and 10 s read timeouts on every outbound client, overridable by property.

## Consequences
Slow vendors now fail fast with an error instead of hanging. Under saturation, event listeners run on the request thread.
