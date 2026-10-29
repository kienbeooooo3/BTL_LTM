package com.bombgame.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Truy vấn bảng players.
 * Dùng {@link DbPool} để tái sử dụng kết nối (mỗi kết nối mới tốn ~3 câu hỏi handshake).
 * Mọi method KHÔNG ném exception ra ngoài: lỗi DB chỉ được ghi log và trả giá trị an toàn,
 * để một sự cố MySQL không làm treo ván đấu hay đánh sập server.
 *
 * <h2>Mật khẩu</h2>
 * Scheme chuẩn của dự án: salt ngẫu nhiên 16 byte + SHA-256(salt || password), lưu salt
 * dạng hex 32 ký tự trong {@code password_salt}. Cột {@code hash_algo} ghi scheme đang dùng
 * để không phải đoán:
 * <ul>
 *   <li>{@code sha256salt} - scheme chuẩn (hex salt + hex hash).</li>
 *   <li>{@code sha256} - dữ liệu cũ: SHA-256(password), không salt.</li>
 *   <li>{@code sha256saltb64} - dữ liệu cũ: salt Base64 + hash Base64.</li>
 * </ul>
 * Khi đăng nhập thành công bằng scheme cũ, hash được nâng cấp (rehash) sang {@code sha256salt}.
 */
public final class PlayerDao {
    private static final SecureRandom RND = new SecureRandom();

    /** Scheme chuẩn: SHA-256(salt || password). */
    public static final String ALGO_SALT = "sha256salt";
    /** Dữ liệu cũ: SHA-256(password), không salt. */
    public static final String ALGO_PLAIN = "sha256";
    /** Dữ liệu cũ: salt Base64 + hash Base64. */
    public static final String ALGO_SALT_B64 = "sha256saltb64";

    private PlayerDao() { }

    // ================= băm mật khẩu =================

    /** Tạo salt ngẫu nhiên 16 byte (32 ký tự hex). */
    static String newSalt() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** SHA-256(salt || password) -> 64 ký tự hex. */
    static String hash(String salt, String p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** SHA-256(password) -> 64 ký tự hex (scheme cũ, không salt). */
    static String hashLegacy(String p) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Kiểm tra mật khẩu theo scheme của tài khoản.
     *
     * @param saltNew    salt theo scheme chuẩn (cột {@code password_salt}, hex 32).
     * @param saltLegacy salt của dữ liệu cũ (cột {@code salt}, Base64).
     * @return null nếu khớp, hoặc lý do không khớp.
     */
    static String verify(String algo, String storedHash, String saltNew, String saltLegacy, String p) {
        if (algo == null || algo.isEmpty()) {
            // Chưa gán scheme -> tự nhận diện theo dạng dữ liệu.
            if (saltNew != null && !saltNew.isEmpty()) algo = ALGO_SALT;
            else if (saltLegacy != null && !saltLegacy.isEmpty()) algo = ALGO_SALT_B64;
            else algo = ALGO_PLAIN;
        }
        switch (algo) {
            case ALGO_SALT:
                if (storedHash == null || !storedHash.equals(hash(saltNew, p))) return "mật khẩu sai";
                return null;
            case ALGO_PLAIN:
                if (storedHash == null || !storedHash.equals(hashLegacy(p))) return "mật khẩu sai";
                return null;
            case ALGO_SALT_B64:
                if (!verifyB64(saltLegacy, storedHash, p)) return "mật khẩu sai";
                return null;
            default:
                return "scheme băm không được hỗ trợ: " + algo;
        }
    }

    /** Kiểm tra scheme salt Base64: SHA-256(saltBytes || password) -> Base64. */
    private static boolean verifyB64(String saltB64, String hashB64, String p) {
        if (saltB64 == null || hashB64 == null) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(saltB64);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            String computed = Base64.getEncoder().encodeToString(md.digest(p.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                                        hashB64.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }

    /** Nâng cấp hash của tài khoản sang scheme chuẩn (gọi sau khi đăng nhập thành công bằng scheme cũ). */
    static void rehash(String user, String password) {
        Connection c = null;
        try {
            c = DbPool.get();
            String salt = newSalt();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + Database.T_PLAYERS + " SET password_hash=?, password_salt=?, hash_algo=? WHERE username=?")) {
                ps.setString(1, hash(salt, password));
                ps.setString(2, salt);
                ps.setString(3, ALGO_SALT);
                ps.setString(4, user);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            GameRoom.logDb("nâng cấp hash", user, e);
        } finally { DbPool.release(c); }
    }

    // ================= truy vấn =================

    /** Đăng ký tài khoản mới (có salt ngẫu nhiên). Trả false nếu tên đã tồn tại HOẶC lỗi DB. */
    public static boolean register(String u, String p) {
        String sql = "INSERT IGNORE INTO " + Database.T_PLAYERS + "(username,password_hash,password_salt,hash_algo) VALUES(?,?,?,?)";
        Connection c = null;
        try {
            c = DbPool.get();
            String salt = newSalt();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, u); ps.setString(2, hash(salt, p)); ps.setString(3, salt); ps.setString(4, ALGO_SALT);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            GameRoom.logDb("đăng ký", u, e);
            return false;
        } finally { DbPool.release(c); }
    }

    /**
     * Đăng nhập. Trả false nếu sai thông tin hoặc lỗi DB.
     * Tài khoản dùng scheme cũ sẽ được rehash sang scheme chuẩn sau khi đăng nhập thành công.
     */
    public static boolean login(String u, String p) {
        Connection c = null;
        try {
            c = DbPool.get();
            String algo, h, saltNew, saltLegacy;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT password_hash,password_salt,hash_algo,salt FROM " + Database.T_PLAYERS + " WHERE username=?")) {
                ps.setString(1, u);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    h = rs.getString(1);
                    saltNew = rs.getString(2);
                    algo = rs.getString(3);
                    saltLegacy = rs.getString(4);
                }
            }
            if (verify(algo, h, saltNew, saltLegacy, p) != null) return false;
            // Đăng nhập OK -> nâng cấp hash lên scheme chuẩn nếu đang dùng scheme cũ.
            if (!ALGO_SALT.equals(effectiveAlgo(algo, saltNew, saltLegacy))) rehash(u, p);
            return true;
        } catch (Exception e) {
            GameRoom.logDb("đăng nhập", u, e);
            return false;
        } finally { DbPool.release(c); }
    }

    /** Scheme thực sự đang dùng của tài khoản (tự nhận diện nếu cột hash_algo còn trống). */
    private static String effectiveAlgo(String algo, String saltNew, String saltLegacy) {
        if (algo != null && !algo.isEmpty()) return algo;
        if (saltNew != null && !saltNew.isEmpty()) return ALGO_SALT;
        if (saltLegacy != null && !saltLegacy.isEmpty()) return ALGO_SALT_B64;
        return ALGO_PLAIN;
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
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT score,wins FROM " + Database.T_PLAYERS + " WHERE username=?")) {
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
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + Database.T_PLAYERS
                    + " SET score=score+?, wins=wins+?, played=played+1,"
                    + " losses=losses+IF(?<0.5,1,0), draws=draws+IF(?=0.5,1,0)"
                    + " WHERE username=?")) {
                ps.setDouble(1, pts); ps.setInt(2, pts == 1.0 ? 1 : 0);
                ps.setDouble(3, pts); ps.setDouble(4, pts); ps.setString(5, u);
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
                 ResultSet rs = st.executeQuery("SELECT username,score,wins,played FROM " + Database.T_PLAYERS
                         + " ORDER BY score DESC, wins DESC, played ASC LIMIT 20")) {
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
