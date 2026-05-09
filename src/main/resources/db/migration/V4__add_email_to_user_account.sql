ALTER TABLE user_account
    ADD COLUMN email VARCHAR(128) NULL AFTER username,
    ADD UNIQUE KEY uk_user_account_email (email);
