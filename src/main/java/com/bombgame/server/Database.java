package com.bombgame.server;

import java.sql.*;

/**
 * Kết nối MySQL và tự khởi tạo / nâng cấp DB + bảng.
 * Thông tin kết nối đọc từ {@link Config} (config.properties / -Ddb.pass=...), mặc định root/1234.
 *
 * <h2>Về tên bảng</h2>
 * Dùng {@code players} (chữ thường) cho mọi câu lệnh. Lý do: MySQL trên Windows mặc định
 * {@code lower_case_table_names=1} nên {@code Players} và {@code players} là CÙNG một bảng, nhưng
 * trên Linux (mặc định = 0) chúng là hai bảng khác nhau — dùng {@code Players} sẽ tạo ra một
 * bảng rỗng song song với bảng cũ, khiến toàn bộ dữ liệu người chơi "biến mất" khi deploy
 * lên máy chạy Linux. Vì vậy chuẩn hoá về {@code players} ở mọi nơi.
 *
 * <h2>hash_algo</h2>
 * Ghi rõ thuật toán băm của từng tài khoản, để không phải đoán:
 * {@code sha256} (không salt), {@code sha256salt} (có salt - chuẩn của dự án),
 * {@code sha256saltb64} (có salt Base64 - định dạng của dữ liệu cũ).
 * Khi người chơi đăng nhập thành công bằng scheme cũ, hệ thống sẽ rehash sang scheme chuẩn.
 */
public final class Database {
    private static final String OPTS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    private static final String URL = Config.get("db.url", "jdbc:mysql://localhost:3306/bombgame") + OPTS + "&createDatabaseIfNotExist=true";
    private static final String USER = Config.get("db.user", "root");
    private static final String PASS = Config.get("db.pass", "1234");

    /** Tên bảng chuẩn (chữ thường) - xem javadoc lớp để biết vì sao. */
    static final String T_PLAYERS = "players";

    private Database() { }

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /** Tạo bảng nếu chưa có, rồi nâng cấp cho các bảng đã tồn tại từ phiên bản cũ. */
    public static void init() throws SQLException {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + T_PLAYERS + "(" +
                "id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE, password_hash CHAR(64) NOT NULL," +
                "password_salt CHAR(32) NOT NULL DEFAULT '', hash_algo VARCHAR(16) NOT NULL DEFAULT ''," +
                "score DECIMAL(10,1) NOT NULL DEFAULT 0, wins INT NOT NULL DEFAULT 0," +
                "losses INT NOT NULL DEFAULT 0, draws INT NOT NULL DEFAULT 0, played INT NOT NULL DEFAULT 0," +
                "display_name VARCHAR(64) NOT NULL DEFAULT ''," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_score (score DESC, wins DESC)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // Nâng cấp DB cũ (bỏ qua nếu đã có rồi).
            addColumnIfMissing(s, T_PLAYERS, "password_salt", "CHAR(32) NOT NULL DEFAULT ''");
            addColumnIfMissing(s, T_PLAYERS, "hash_algo", "VARCHAR(16) NOT NULL DEFAULT ''");
            addColumnIfMissing(s, T_PLAYERS, "losses", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(s, T_PLAYERS, "draws", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(s, T_PLAYERS, "display_name", "VARCHAR(64) NOT NULL DEFAULT ''");
            try { s.execute("ALTER TABLE " + T_PLAYERS + " MODIFY COLUMN score DECIMAL(10,1) NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) { }
            try { s.execute("ALTER TABLE " + T_PLAYERS + " ADD INDEX idx_score (score DESC, wins DESC)"); }
            catch (SQLException ignored) { }

            // Gán hash_algo cho các tài khoản đã tồn tại, dựa trên dạng dữ liệu đang lưu.
            s.executeUpdate("UPDATE " + T_PLAYERS + " SET hash_algo='sha256salt' "
                + "WHERE hash_algo='' AND password_salt<>'' AND password_hash REGEXP '^[0-9a-f]{64}$'");
            s.executeUpdate("UPDATE " + T_PLAYERS + " SET hash_algo='sha256' "
                + "WHERE hash_algo='' AND password_salt='' AND salt='' AND password_hash REGEXP '^[0-9a-f]{64}$'");
            s.executeUpdate("UPDATE " + T_PLAYERS + " SET hash_algo='sha256saltb64' "
                + "WHERE hash_algo='' AND salt<>'' AND password_hash LIKE '%='");
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
