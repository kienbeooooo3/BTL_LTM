package com.bombgame.server;

import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tích hợp protocol client &lt;-&gt; server qua socket thật.
 * Yêu cầu: MySQL đang chạy và server đã khởi động ở cổng 9999.
 *
 * Chạy: {@code mvn test -Pintegration}
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtocolIntegrationTest {

    private static final int PORT = 9999;
    private static final long T = 8_000;

    private static String uniqueUser(String prefix) {
        return prefix + (System.currentTimeMillis() % 100000);
    }

    @Test
    @Order(1)
    @DisplayName("đăng ký và đăng nhập hoạt động")
    void registerAndLogin() throws Exception {
        String u = uniqueUser("t1_");
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("REGISTER|" + u + "|pw123");
            assertNotNull(c.await("REGISTER", T), "phải nhận REGISTER/REGISTER_OK");

            try (TestClient c2 = TestClient.connect(PORT)) {
                c2.send("LOGIN|" + u + "|pw123");
                String ok = c2.awaitRequired("LOGIN_OK", T);
                assertTrue(ok.startsWith("LOGIN_OK|" + u));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("đăng nhập sai mật khẩu bị từ chối")
    void loginWrongPassword() throws Exception {
        String u = uniqueUser("t2_");
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("REGISTER|" + u + "|pw123");
            c.await("REGISTER", T);
        }
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("LOGIN|" + u + "|sai_mat_khau");
            assertNotNull(c.await("LOGIN_FAIL", T), "phải nhận LOGIN_FAIL");
        }
    }

    @Test
    @Order(3)
    @DisplayName("PING luôn được trả lời PONG, kể cả trước khi đăng nhập")
    void pingPongBeforeLogin() throws Exception {
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("PING");
            assertNotNull(c.await("PONG", T), "phải nhận PONG");
        }
    }

    @Test
    @Order(4)
    @DisplayName("lời mời, nhận lời, chơi ván và nhận GAME_OVER")
    void fullMatchFlow() throws Exception {
        String u1 = uniqueUser("t4a_"), u2 = uniqueUser("t4b_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T), "đối thủ phải nhận INVITE");

            c2.send("ACCEPT|" + u1);
            assertNotNull(c1.await("MATCH_START", T), "phải bắt đầu ván");
            assertNotNull(c2.await("MATCH_START", T), "phải bắt đầu ván");

            // Chơi tới khi có GAME_OVER (tối đa 6 hiệp)
            boolean over = false;
            for (int r = 0; r < 6 && !over; r++) {
                String round = c1.await("ROUND|", T + 2_000);
                if (round == null) break;
                String[] f = TestClient.fields(round);
                int min = Integer.parseInt(f[2]), max = Integer.parseInt(f[3]);
                int pick = (min + max) / 2;
                c1.send("GUESS|" + pick);
                c2.send("GUESS|" + pick);
                c1.await("RESULT|", T);
                c2.await("RESULT|", T);
            }
            String go = c1.await("GAME_OVER", T + 3_000);
            assertNotNull(go, "phải nhận GAME_OVER");
            assertTrue(go.startsWith("GAME_OVER|"), go);
            assertNotNull(c2.await("GAME_OVER", T), "cả hai bên đều phải nhận GAME_OVER");
        }
    }

    @Test
    @Order(5)
    @DisplayName("lệnh sai định dạng không làm chết server")
    void malformedCommandsDoNotKillServer() throws Exception {
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("GARBAGE");
            c.send("GUESS|abc");
            c.send("ACCEPT|khong_ton_tai");
            c.send("CHALLENGE|khong_ton_tai");
            c.send("|");
            c.send("PING");
            assertNotNull(c.await("PONG", T), "server vẫn phản hồi sau khi nhận lệnh rác");
        }
    }

    @Test
    @Order(6)
    @DisplayName("đăng xuất đóng kết nối và tài khoản đăng nhập lại được")
    void logoutThenLoginAgain() throws Exception {
        String u = uniqueUser("t6_");
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("REGISTER|" + u + "|pw123"); c.await("REGISTER", T);
            c.send("LOGIN|" + u + "|pw123"); assertNotNull(c.await("LOGIN_OK", T));
            c.send("LOGOUT");
            Thread.sleep(1200);
        }
        Thread.sleep(600);
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("LOGIN|" + u + "|pw123");
            assertNotNull(c.await("LOGIN_OK", T), "đăng nhập lại phải được");
        }
    }

    @Test
    @Order(7)
    @DisplayName("bảng xếp hạng trả về đúng định dạng")
    void boardFormat() throws Exception {
        String u = uniqueUser("t7_");
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("REGISTER|" + u + "|pw123"); c.await("REGISTER", T);
            c.send("LOGIN|" + u + "|pw123"); assertNotNull(c.await("LOGIN_OK", T));

            c.send("BOARD");
            String b = c.awaitRequired("BOARD", T);
            String payload = b.substring("BOARD|".length());
            if (!payload.isEmpty()) {
                for (String row : payload.split(";")) {
                    String[] f = row.split(",");
                    assertEquals(4, f.length, "mỗi dòng phải có 4 trường: " + row);
                }
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("lệnh lạ khi chưa đăng nhập phải được báo lỗi rõ ràng, không im lặng")
    void unknownCommandBeforeLoginGivesError() throws Exception {
        try (TestClient c = TestClient.connect(PORT)) {
            c.send("BOARD");
            String err = c.await("ERROR", T);
            assertNotNull(err, "server phải báo lỗi khi chưa đăng nhập");
        }
    }
}
