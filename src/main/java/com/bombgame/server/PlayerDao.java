package com.bombgame.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Truy vấn bảng Players.
 * Dùng {@link DbPool} để tái sử dụng kết nối (mỗi kết nối mới tốn ~3 câu hỏi handshake).
 * Mọi method KHÔNG ném exception ra ngoài: lỗi DB chỉ được ghi log và trả giá trị an toàn,
 * để một sự cố MySQL không làm treo ván đấu hay đánh sập server.
 *
 * Mật khẩu được băm SHA-256 kèm salt ngẫu nhiên riêng cho từng tài khoản
 * (trước đây băm trần không salt: hai tài khoản cùng mật khẩu cho cùng hash,
 * và bảng hash dễ bị dịch ngược bằng bảng tra cứu).
 */
public final class PlayerDao {
    private static final SecureRandom RND = new SecureRandom();

    private PlayerDao() { }

    /** Tạo salt ngẫu nhiên 16 byte (32 ký tự hex). */
    static String newSalt() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Băm mật khẩu: SHA-256(salt + password) -> 64 ký tự hex. */
    static String hash(String salt, String p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Băm không salt (giữ lại cho tương thích dữ liệu cũ đã đăng ký trước khi thêm cột salt). */
    static String hashLegacy(String p) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Đăng ký tài khoản mới (có salt ngẫu nhiên). Trả false nếu tên đã tồn tại HOẶC lỗi DB. */
    public static boolean register(String u, String p) {
        String sql = "INSERT IGNORE INTO Players(username,password_hash,password_salt) VALUES(?,?,?)";
        Connection c = null;
        try {
            c = DbPool.get();
            String salt = newSalt();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, u); ps.setString(2, hash(salt, p)); ps.setString(3, salt);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            GameRoom.logDb("đăng ký", u, e);
            return false;
        } finally { DbPool.release(c); }
    }

    /**
     * Đăng nhập. Trả false nếu sai thông tin hoặc lỗi DB.
     * Chấp nhận cả tài khoản cũ (hash không salt) và tài khoản mới (có salt).
     */
    public static boolean login(String u, String p) {
        Connection c = null;
        try {
            c = DbPool.get();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT password_hash,password_salt FROM Players WHERE username=?")) {
                ps.setString(1, u);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String h = rs.getString(1);
                    String salt = rs.getString(2);
                    if (salt == null || salt.isEmpty()) {
                        // Tài khoản cũ: so khớp bằng hash không salt.
                        return h != null && h.equals(hashLegacy(p));
                    }
                    return h != null && h.equals(hash(salt, p));
                }
            }
        } catch (Exception e) {
            GameRoom.logDb("đăng nhập", u, e);
            return false;
        } finally { DbPool.release(c); }
    }

    /**
     * Lấy thống kê người chơi. Trả về {0,0} nếu lỗi DB.
     *
     * @return {score, wins}
     */
    public static double[] stats(String u) {
        Connection c = null;
        try {
            c = DbPool.get();
            try (PreparedStatement ps = c.prepareStatement("SELECT score,wins FROM Players WHERE username=?")) {
                ps.setString(1, u);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new double[]{rs.getDouble(1), rs.getInt(2)} : new double[]{0, 0}; }
            }
        } catch (Exception e) {
            GameRoom.logDb("đọc thống kê", u, e);
            return new double[]{0, 0};
        } finally { DbPool.release(c); }
    }

    /** Ghi kết quả trận. Nuốt lỗi DB và ghi log. */
    public static void addResult(String u, double pts) {
        Connection c = null;
        try {
            c = DbPool.get();
            try (PreparedStatement ps = c.prepareStatement("UPDATE Players SET score=score+?, wins=wins+?, played=played+1 WHERE username=?")) {
                ps.setDouble(1, pts); ps.setInt(2, pts == 1.0 ? 1 : 0); ps.setString(3, u);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            GameRoom.logDb("ghi điểm", u, e);
        } finally { DbPool.release(c); }
    }

    /**
     * Bảng xếp hạng: tổng điểm giảm dần, rồi số trận thắng giảm dần. Định dạng: name,score,wins,played;...
     * Trả chuỗi rỗng nếu lỗi DB (client hiển thị bảng trống thay vì crash).
     */
    public static String leaderboard() {
        StringBuilder sb = new StringBuilder();
        Connection c = null;
        try {
            c = DbPool.get();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT username,score,wins,played FROM Players ORDER BY score DESC, wins DESC, played ASC LIMIT 20")) {
                while (rs.next()) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(rs.getString(1)).append(',').append(String.format(Locale.ROOT, "%.1f", rs.getDouble(2)))
                      .append(',').append(rs.getInt(3)).append(',').append(rs.getInt(4));
                }
            }
        } catch (Exception e) {
            GameRoom.logDb("đọc bảng xếp hạng", "-", e);
            return "";
        } finally { DbPool.release(c); }
        return sb.toString();
    }
}
