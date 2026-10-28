# Flyway migrations with hbm2ddl validate

**Status:** accepted

## Context
hbm2ddl update never alters existing columns and gives no history of schema changes.

## Decision
Flyway owns schema changes on the mysql, gcp and cloud profiles, with baseline-on-migrate for existing databases and Hibernate in validate mode. The H2 demo profiles keep update for zero-setup local runs.

## Consequences
Schema changes are reviewed SQL files. A fresh production database is created from V1__baseline_schema.sql.
