package com.bombgame.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Truy vấn bảng Players.
 * Dùng {@link DbPool} để tái sử dụng kết nối (mỗi kết nối mới tốn ~3 câu hỏi handshake).
 * Mọi method KHÔNG ném exception ra ngoài: lỗi DB chỉ được ghi log và trả giá trị an toàn,
 * để một sự cố MySQL không làm treo ván đấu hay đánh sập server.
 */
public final class PlayerDao {
    private PlayerDao() { }

    static String hash(String p) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Đăng ký tài khoản mới. Trả false nếu tên đã tồn tại HOẶC lỗi DB. */
    public static boolean register(String u, String p) {
        String sql = "INSERT IGNORE INTO Players(username,password_hash) VALUES(?,?)";
        Connection c = null;
        try {
            c = DbPool.get();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, u); ps.setString(2, hash(p));
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            GameRoom.logDb("đăng ký", u, e);
            return false;
        } finally { DbPool.release(c); }
    }

    /** Đăng nhập. Trả false nếu sai thông tin hoặc lỗi DB. */
    public static boolean login(String u, String p) {
        Connection c = null;
        try {
            c = DbPool.get();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM Players WHERE username=? AND password_hash=?")) {
                ps.setString(1, u); ps.setString(2, hash(p));
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
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
