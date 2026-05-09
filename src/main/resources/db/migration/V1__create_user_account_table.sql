CREATE TABLE IF NOT EXISTS user_account (
                                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                            username VARCHAR(64) NOT NULL,
                                            password_hash VARCHAR(255) NOT NULL,
                                            display_name VARCHAR(64) NOT NULL,
                                            created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                            updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                            UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
