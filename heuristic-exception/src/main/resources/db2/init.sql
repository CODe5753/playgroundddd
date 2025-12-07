CREATE TABLE IF NOT EXISTS ums_send_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_id VARCHAR(64) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_approval_id (approval_id)
);
GRANT PROCESS ON *.* TO 'app'@'%';
SET GLOBAL innodb_lock_wait_timeout = 2;
SET GLOBAL innodb_rollback_on_timeout = ON;
SET GLOBAL net_read_timeout = 2;
SET GLOBAL net_write_timeout = 2;
