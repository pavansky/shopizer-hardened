-- When an admin user's password last changed. Tokens issued before this
-- instant are refused, so a password change or reset ends older sessions.
ALTER TABLE USERS ADD COLUMN CREDENTIALS_CHANGED_AT DATETIME NULL;

-- Append-only record of every mutating admin API request: who, which store,
-- method, path and response status. No request or response bodies. Grant the
-- application user INSERT and SELECT only on this table in production.
CREATE TABLE ADMIN_AUDIT (
  ID BIGINT NOT NULL AUTO_INCREMENT,
  OCCURRED_AT DATETIME NOT NULL,
  USERNAME VARCHAR(100) NULL,
  STORE_CODE VARCHAR(100) NULL,
  HTTP_METHOD VARCHAR(10) NOT NULL,
  PATH VARCHAR(512) NOT NULL,
  STATUS INTEGER NOT NULL,
  CLIENT_IP VARCHAR(64) NULL,
  PRIMARY KEY (ID)
);

CREATE INDEX IDX_ADMIN_AUDIT_TIME ON ADMIN_AUDIT (OCCURRED_AT);
CREATE INDEX IDX_ADMIN_AUDIT_USER ON ADMIN_AUDIT (USERNAME, OCCURRED_AT);
