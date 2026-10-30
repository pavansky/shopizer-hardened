-- When an admin user's password last changed. Tokens issued before this
-- instant are refused, so a password change or reset ends older sessions.
ALTER TABLE USERS ADD COLUMN CREDENTIALS_CHANGED_AT DATETIME NULL;
