package com.bombgame.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tích hợp luồng ván đấu đầy đủ.
 *
 * <p>Kiểm tra: ván đấu 5 hiệp, REMATCH chỉ bắt đầu khi CẢ HAI đồng ý (bug cũ: mảng
 * {@code yes[]} không được reset nên một người đồng ý là đủ), {@code LEAVE} thông báo
 * cho đối thủ, thách đấu người không tồn tại bị từ chối, và lệnh sai không làm chết server.
 *
 * <p>Yêu cầu: MySQL đang chạy, server đã khởi động ở cổng 9999.
 */
@Tag("integration")
class MatchFlowIntegrationTest {

    private static final int PORT = 9999;
    private static final long T = 8_000;

    private static String user(String prefix) {
        return prefix + (System.currentTimeMillis() % 100000);
    }

    /** Chơi tới khi cả hai bên nhận GAME_OVER (tối đa 6 hiệp). */
    private static void playToEnd(TestClient p1, TestClient p2) throws Exception {
        for (int r = 0; r < 6; r++) {
            String rd = p1.await("ROUND|", T);
            if (rd == null) return;                          // hết hiệp (BOOM) sớm
            String[] f = TestClient.fields(rd);
            if (f.length < 4) return;
            int min = Integer.parseInt(f[2]), max = Integer.parseInt(f[3]);
            int pick = (min + max) / 2;
            p1.send("GUESS|" + pick);
            p2.send("GUESS|" + pick);
            p1.await("RESULT|", T);
            p2.await("RESULT|", T);
        }
    }

    @Test
    @DisplayName("ván đấu 5 hiệp kết thúc bằng GAME_OVER cho cả hai bên")
    void fullMatchEndsWithGameOver() throws Exception {
        String u1 = user("mf1_"), u2 = user("mf2_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T));
            c2.send("ACCEPT|" + u1);
            assertNotNull(c1.await("MATCH_START", T));
            assertNotNull(c2.await("MATCH_START", T));

            playToEnd(c1, c2);

            String over1 = c1.await("GAME_OVER", T + 3_000);
            String over2 = c2.await("GAME_OVER", T);
            assertNotNull(over1, "client 1 phải nhận GAME_OVER");
            assertNotNull(over2, "client 2 phải nhận GAME_OVER");
            assertTrue(over1.startsWith("GAME_OVER|"), over1);
        }
    }

    @Test
    @DisplayName("REMATCH chỉ bắt đầu khi cả hai đồng ý (mảng yes[] phải được reset)")
    void rematchRequiresBothPlayersToAgree() throws Exception {
        String u1 = user("mf3_"), u2 = user("mf4_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T));
            c2.send("ACCEPT|" + u1);
            assertNotNull(c1.await("MATCH_START", T));
            assertNotNull(c2.await("MATCH_START", T));

            playToEnd(c1, c2);
            assertNotNull(c1.await("GAME_OVER", T + 3_000));
            assertNotNull(c2.await("GAME_OVER", T));

            // Chỉ một người đồng ý -> KHÔNG được bắt đầu ván mới.
            c1.send("REMATCH|1");
            String only1 = c1.await("MATCH_START", 3_500);
            String only2 = c2.await("MATCH_START", 3_500);
            assertTrue(only1 == null && only2 == null,
                    "một người đồng ý không được bắt đầu ván mới (c1=" + only1 + ", c2=" + only2 + ")");

            // Cả hai đồng ý -> phải bắt đầu ván mới.
            c1.send("REMATCH|1");
            c2.send("REMATCH|1");
            assertNotNull(c1.await("MATCH_START", T), "cả hai đồng ý thì phải có ván mới");
            assertNotNull(c2.await("MATCH_START", T), "cả hai đồng ý thì phải có ván mới");

            // Ván mới xong, chỉ một người đồng ý lần nữa -> vẫn KHÔNG được bắt đầu.
            playToEnd(c1, c2);
            assertNotNull(c1.await("GAME_OVER", T + 3_000));
            assertNotNull(c2.await("GAME_OVER", T));

            c1.send("REMATCH|1");
            String again1 = c1.await("MATCH_START", 3_500);
            String again2 = c2.await("MATCH_START", 3_500);
            assertTrue(again1 == null && again2 == null,
                    "yes[] phải được reset giữa các ván (c1=" + again1 + ", c2=" + again2 + ")");
        }
    }

    @Test
    @DisplayName("LEAVE thông báo OPP_LEFT cho đối thủ")
    void leaveNotifiesOpponent() throws Exception {
        String u1 = user("mf5_"), u2 = user("mf6_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T));
            c2.send("ACCEPT|" + u1);
            assertNotNull(c1.await("MATCH_START", T));

            c2.send("LEAVE");
            assertNotNull(c1.await("OPP_LEFT", T), "đối thủ phải nhận OPP_LEFT");
        }
    }

    @Test
    @DisplayName("thách đấu người không tồn tại bị từ chối")
    void challengeUnknownUserFails() throws Exception {
        String u1 = user("mf7_");
        try (TestClient c1 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));

            c1.send("CHALLENGE|khong_ton_tai_xyz");
            assertNotNull(c1.await("INVITE_FAIL", T), "phải nhận INVITE_FAIL");
        }
    }

    @Test
    @DisplayName("đăng nhập trùng tên từ nơi khác bị từ chối")
    void duplicateLoginRejected() throws Exception {
        String u1 = user("mf8_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u1 + "|pw123");
            assertNotNull(c2.await("LOGIN_FAIL", T), "đăng nhập trùng phải bị từ chối");
        }
    }

    @Test
    @DisplayName("tiếng Việt giữ nguyên trên wire (UTF-8)")
    void utf8SurvivesWire() throws Exception {
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("LOGIN|khong_ton_tai_abc|xyz");
            String fail = c.awaitRequired("LOGIN_FAIL", T);
            assertTrue(fail.contains("đăng nhập") || fail.contains("Sai tên"),
                    "thông điệp tiếng Việt phải giữ nguyên: " + fail);
        }
    }
}
