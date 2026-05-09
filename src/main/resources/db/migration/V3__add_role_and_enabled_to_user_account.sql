ALTER TABLE user_account
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER display_name,
    ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER role;

UPDATE user_account SET role = 'USER' WHERE role IS NULL;
UPDATE user_account SET enabled = 1 WHERE enabled IS NULL;
