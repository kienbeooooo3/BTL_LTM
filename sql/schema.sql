-- MySQL 8+. Chạy 1 lần (hoặc để server tự tạo khi khởi động - Database.init()).
-- Tài khoản root / 1234:  ALTER USER 'root'@'localhost' IDENTIFIED BY '1234';
CREATE DATABASE IF NOT EXISTS bombgame CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bombgame;

-- Bảng người chơi.
--
-- LƯU Ý VỀ TÊN BẢNG: dùng `players` (chữ thường) cho mọi nơi. MySQL trên Windows mặc định
-- lower_case_table_names=1 nên `Players` và `players` là cùng một bảng, NHƯNG trên Linux
-- (mặc định = 0) chúng là hai bảng khác nhau -> dùng `Players` sẽ tạo bảng rỗng song song
-- và toàn bộ dữ liệu người chơi sẽ "biến mất" khi deploy lên Linux.
--
-- hash_algo: ghi rõ scheme băm của từng tài khoản để không phải đoán:
--   sha256salt    - scheme chuẩn của dự án: salt hex 32 ký tự + SHA-256(salt || password)
--   sha256        - dữ liệu cũ: SHA-256(password), không salt
--   sha256saltb64 - dữ liệu cũ: salt Base64 (cột `salt`) + hash Base64
-- Khi người chơi đăng nhập bằng scheme cũ, hệ thống tự rehash sang sha256salt.
--
-- `salt` (Base64) là cột của dữ liệu cũ - giữ lại để không mất tài khoản đang có.
-- `password_salt` là cột của dự án, dùng cho scheme chuẩn.
CREATE TABLE IF NOT EXISTS players(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32) NOT NULL UNIQUE,
    password_hash CHAR(64)    NOT NULL,
    password_salt CHAR(32)    NOT NULL DEFAULT '',
    hash_algo     VARCHAR(16) NOT NULL DEFAULT '',
    salt          VARCHAR(64)    NOT NULL DEFAULT '',
    score         DECIMAL(10,1) NOT NULL DEFAULT 0,
    wins          INT         NOT NULL DEFAULT 0,
    losses        INT         NOT NULL DEFAULT 0,
    draws         INT         NOT NULL DEFAULT 0,
    played        INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_score (score DESC, wins DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Với DB đã tạo từ phiên bản cũ, chạy các lệnh sau để nâng cấp:
--   ALTER TABLE players ADD COLUMN password_salt CHAR(32) NOT NULL DEFAULT '' AFTER password_hash;
--   ALTER TABLE players ADD COLUMN hash_algo     VARCHAR(16) NOT NULL DEFAULT '' AFTER password_salt;
--   ALTER TABLE players ADD COLUMN losses       INT NOT NULL DEFAULT 0 AFTER wins;
--   ALTER TABLE players ADD COLUMN draws        INT NOT NULL DEFAULT 0 AFTER losses;
--   ALTER TABLE players MODIFY COLUMN score DECIMAL(10,1) NOT NULL DEFAULT 0;
--   ALTER TABLE players ADD INDEX idx_score (score DESC, wins DESC);
-- Server tự chạy các lệnh trên (Database.init()) nếu thiếu.
