-- Widen order columns to match the columns they are copied from.
-- hibernate.hbm2ddl.auto=update never alters an existing column, so existing
-- databases must run this once. New databases get the new sizes automatically.
-- Back up the database before running.

-- MySQL / MariaDB
ALTER TABLE ORDER_PRODUCT MODIFY COLUMN PRODUCT_NAME VARCHAR(120) NOT NULL;
ALTER TABLE ORDERS MODIFY COLUMN CUSTOMER_EMAIL_ADDRESS VARCHAR(96) NOT NULL;

-- H2 (docker profile file database) - run these instead; prefix the schema name if hibernate.default_schema is set
-- ALTER TABLE ORDER_PRODUCT ALTER COLUMN PRODUCT_NAME VARCHAR(120) NOT NULL;
-- ALTER TABLE ORDERS ALTER COLUMN CUSTOMER_EMAIL_ADDRESS VARCHAR(96) NOT NULL;
