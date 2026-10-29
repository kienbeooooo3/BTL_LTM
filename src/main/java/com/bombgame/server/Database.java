package com.bombgame.server;

import java.sql.*;

/**
 * Kết nối MySQL và tự khởi tạo / nâng cấp DB + bảng.
 * Thông tin kết nối đọc từ {@link Config} (config.properties / -Ddb.pass=...), mặc định root/1234.
 */
public final class Database {
    private static final String OPTS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    private static final String URL = Config.get("db.url", "jdbc:mysql://localhost:3306/bombgame") + OPTS + "&createDatabaseIfNotExist=true";
    private static final String USER = Config.get("db.user", "root");
    private static final String PASS = Config.get("db.pass", "1234");
    private Database() { }

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /** Tạo bảng nếu chưa có, rồi nâng cấp cho các bảng đã tồn tại từ phiên bản cũ. */
    public static void init() throws SQLException {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS Players(" +
                "id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE, password_hash CHAR(64) NOT NULL," +
                "password_salt CHAR(32) NOT NULL DEFAULT ''," +
                "score DECIMAL(10,1) NOT NULL DEFAULT 0, wins INT NOT NULL DEFAULT 0, played INT NOT NULL DEFAULT 0," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_score (score DESC, wins DESC)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // Nâng cấp DB cũ: thêm cột salt, đổi kiểu score, thêm index (bỏ qua nếu đã có).
            addColumnIfMissing(s, "Players", "password_salt", "CHAR(32) NOT NULL DEFAULT ''");
            try { s.execute("ALTER TABLE Players MODIFY COLUMN score DECIMAL(10,1) NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) { }
            try { s.execute("ALTER TABLE Players ADD INDEX idx_score (score DESC, wins DESC)"); }
            catch (SQLException ignored) { }
        }
    }

    private static void addColumnIfMissing(Statement s, String table, String column, String definition) {
        try (ResultSet rs = s.executeQuery("SHOW COLUMNS FROM " + table + " LIKE '" + column + "'")) {
            if (rs.next()) return;                       // đã có rồi
        } catch (SQLException ignored) { }
        try { s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition); }
        catch (SQLException ignored) { }
    }
}
