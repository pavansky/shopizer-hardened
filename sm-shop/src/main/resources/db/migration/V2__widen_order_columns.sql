-- Widen order columns to match the columns they are copied from.
-- Applied by Flyway on the mysql, gcp and cloud profiles.
-- hibernate.hbm2ddl.auto=update never alters an existing column, which is why this needs a migration.

-- MySQL / MariaDB
ALTER TABLE ORDER_PRODUCT MODIFY COLUMN PRODUCT_NAME VARCHAR(120) NOT NULL;
ALTER TABLE ORDERS MODIFY COLUMN CUSTOMER_EMAIL_ADDRESS VARCHAR(96) NOT NULL;
