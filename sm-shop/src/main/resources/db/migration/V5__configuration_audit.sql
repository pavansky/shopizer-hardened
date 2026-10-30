-- Append-only record of changes to a store's module configuration (payment
-- gateway credentials). Holds the names of the settings that changed, never
-- their values. The application only inserts and selects; in production grant
-- the application user INSERT and SELECT on this table only.
CREATE TABLE CONFIGURATION_AUDIT (
  ID BIGINT NOT NULL AUTO_INCREMENT,
  MERCHANT_ID INTEGER NOT NULL,
  CONFIG_TYPE VARCHAR(32) NOT NULL,
  MODULE_CODE VARCHAR(64) NOT NULL,
  ACTOR VARCHAR(255) NOT NULL,
  CHANGED_KEYS VARCHAR(2000) NULL,
  DATE_CREATED DATETIME NOT NULL,
  PRIMARY KEY (ID)
);

CREATE INDEX IDX_CONFIGURATION_AUDIT_STORE ON CONFIGURATION_AUDIT (MERCHANT_ID, DATE_CREATED);
