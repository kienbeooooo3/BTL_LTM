-- MySQL 8+. Chạy 1 lần (hoặc để server tự tạo khi khởi động).
-- Tài khoản root / 1234:  ALTER USER 'root'@'localhost' IDENTIFIED BY '1234';
CREATE DATABASE IF NOT EXISTS bombgame CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bombgame;

-- Bảng người chơi.
--   score        : DECIMAL(10,1) thay vì DOUBLE để tránh lỗi dấu phẩy động khi cộng 0.5/1.0
--   password_salt: salt ngẫu nhiên cho mỗi tài khoản (bảo mật mật khẩu)
--   idx_score    : index phục vụ truy vấn bảng xếp hạng ORDER BY score DESC, wins DESC
CREATE TABLE IF NOT EXISTS Players(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32) NOT NULL UNIQUE,
    password_hash CHAR(64)    NOT NULL,
    password_salt CHAR(32)    NOT NULL DEFAULT '',
    score         DECIMAL(10,1) NOT NULL DEFAULT 0,
    wins          INT         NOT NULL DEFAULT 0,
    played        INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_score (score DESC, wins DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Với DB đã tạo từ phiên bản cũ, chạy 2 lệnh sau để nâng cấp:
--   ALTER TABLE Players ADD COLUMN password_salt CHAR(32) NOT NULL DEFAULT '' AFTER password_hash;
--   ALTER TABLE Players MODIFY COLUMN score DECIMAL(10,1) NOT NULL DEFAULT 0;
--   ALTER TABLE Players ADD INDEX idx_score (score DESC, wins DESC);
