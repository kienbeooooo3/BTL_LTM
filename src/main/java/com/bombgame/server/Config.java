package com.bombgame.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/**
 * Cấu hình server: đọc từ file {@code config.properties} (cùng thư mục chạy) hoặc từ
 * system property (-Ddb.pass=...), sau đó mới fallback về giá trị mặc định.
 *
 * <pre>
 *   db.url   = jdbc:mysql://localhost:3306/bombgame
 *   db.user  = root
 *   db.pass  = 1234
 *   game.port= 9999
 * </pre>
 *
 * Nhờ vậy có thể chạy 2 máy / nhiều môi trường mà không phải sửa code rồi build lại.
 */
public final class Config {
    private static final Properties P = new Properties();

    static {
        // 1) file config.properties cạnh file .jar hoặc thư mục làm việc
        try {
            Path f = Paths.get("config.properties");
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { P.load(r); }
            }
        } catch (Exception ignored) { }
        // System property (-Ddb.pass=...) có độ ưu tiên CAO HƠN file, nên không ghi đè ở đây.
        // Xem Config.get().
    }

    private Config() { }

    /** Đọc cấu hình: ưu tiên system property -> file -> mặc định. */
    public static String get(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = P.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
