# Defer the Spring Boot 3 upgrade

**Status:** accepted

## Context
Boot 2.5 is end of life, but the upgrade touches javax to jakarta, Hibernate 6, Ehcache and Spring Security across thousands of lines.

## Decision
Fix money, security and resilience first, with tests and migrations. Then move to Boot 2.7 on Java 17, then Boot 3.

## Consequences
Merchants get correctness fixes first. The upgrade starts with a test suite and migrations that catch regressions.
