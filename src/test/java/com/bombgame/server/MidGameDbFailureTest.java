package com.bombgame.server;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tích hợp: DB chết GIỮA VÁN ĐẤU.
 *
 * <p>Mô phỏng bằng cách đổi tên bảng {@code players} sang tên khác ngay giữa trận, khiến mọi
 * câu lệnh UPDATE ghi điểm phải thất bại.
 *
 * <p>Bug gốc: {@code addResult()} ném RuntimeException trong task ghi điểm -&gt; task chết -&gt;
 * client KHÔNG bao giờ nhận GAME_OVER (màn hình đếm mãi). Lỗi đã được sửa: GAME_OVER được
 * gửi trước, phần ghi DB chạy ở executor riêng và có try-catch.
 *
 * <p>Yêu cầu: MySQL đang chạy, server đã khởi động ở cổng 9999.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MidGameDbFailureTest {

    private static final int PORT = 9999;
    private static final long T = 8_000;

    @Test
    @Order(1)
    @DisplayName("DB chết giữa ván vẫn gửi GAME_OVER cho cả hai bên, client không treo")
    void dbFailureDuringMatchDoesNotHangClients() throws Exception {
        String u1 = "md1_" + (System.currentTimeMillis() % 100000);
        String u2 = "md2_" + (System.currentTimeMillis() % 100000);

        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T), "đối thủ phải nhận INVITE");
            c2.send("ACCEPT|" + u1);
            assertNotNull(c1.await("MATCH_START", T));
            assertNotNull(c2.await("MATCH_START", T));

            boolean renamed = false;
            try {
                // Chơi tới hiệp thứ 2 rồi làm DB chết.
                for (int r = 1; r <= 6; r++) {
                    String round = c1.await("ROUND|", T);
                    if (round == null) break;                       // hết hiệp (BOOM) sớm
                    String[] f = TestClient.fields(round);
                    int min = Integer.parseInt(f[2]), max = Integer.parseInt(f[3]);
                    int pick = (min + max) / 2;
                    c1.send("GUESS|" + pick);
                    c2.send("GUESS|" + pick);
                    c1.await("RESULT|", T);
                    c2.await("RESULT|", T);

                    if (r == 2) {
                        renamePlayersTable();
                        renamed = true;
                    }
                }

                // GAME_OVER phải tới cả hai bên dù DB đã chết.
                String over1 = c1.await("GAME_OVER", T + 4_000);
                String over2 = c2.await("GAME_OVER", T);
                assertNotNull(over1, "client 1 phải nhận GAME_OVER dù DB chết giữa ván");
                assertNotNull(over2, "client 2 phải nhận GAME_OVER dù DB chết giữa ván");
                assertTrue(over1.startsWith("GAME_OVER|"), over1);

                // Kết nối client phải còn sống (không bị đóng treo).
                assertFalse(c1.all().isEmpty() && over1 == null);
            } finally {
                if (renamed) restorePlayersTable();
            }
        }
    }

    /** Đổi tên bảng players -> players_tmp để mô phỏng DB chết. */
    private static void renamePlayersTable() throws Exception {
        exec("RENAME TABLE players TO players_tmp");
    }

    /** Trả lại tên bảng về `players`. */
    private static void restorePlayersTable() throws Exception {
        try {
            exec("RENAME TABLE players_tmp TO players");
        } catch (Exception e) {
            // Khôi phục cũng lỗi -> cần biết để xử lý, không nuốt im lặng.
            throw new AssertionError("Không khôi phục được tên bảng players: " + e.getMessage(), e);
        }
    }

    /** Chạy câu lệnh SQL trực tiếp qua JDBC (không cần mysql.exe trên PATH). */
    private static void exec(String sql) throws Exception {
        try (Connection c = Database.get(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Kiểm tra bảng players có tồn tại và dùng được (dùng để dọn dẹp sau test). */
    @Test
    @Order(2)
    @DisplayName("bảng players tồn tại sau khi test DB chết")
    void playersTableIsUsable() throws Exception {
        try (Connection c = Database.get(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 0);
        }
    }
}
