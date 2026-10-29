package com.bombgame.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tích hợp vòng đời lời mời và cờ BUSY.
 *
 * <p>Kiểm tra: khi hủy / từ chối lời mời, cả hai bên đều phải được giải phóng BUSY
 * (nếu không, người chơi bị kẹt không thể thách đấu ai nữa). Cũng kiểm tra {@code ACCEPT}
 * với sai tên không được làm kẹt BUSY.
 *
 * <p>Yêu cầu: MySQL đang chạy, server đã khởi động ở cổng 9999.
 */
@Tag("integration")
class InviteLifecycleTest {

    private static final int PORT = 9999;
    private static final long T = 8_000;

    private static String user(String prefix) {
        return prefix + (System.currentTimeMillis() % 100000);
    }

    /** Đọc trạng thái BUSY/FREE của một người chơi từ message PLAYERS gần nhất. */
    private static String stateOf(TestClient c, String user) throws InterruptedException {
        Thread.sleep(900);                       // chờ broadcast mới tới
        String last = null;
        for (String m : c.all()) if (m.startsWith("PLAYERS|")) last = m;
        if (last == null) return "UNKNOWN";
        for (String row : last.substring("PLAYERS|".length()).split(";")) {
            String[] f = row.split(",");
            if (f.length >= 4 && f[0].equals(user)) return f[3];
        }
        return "UNKNOWN";
    }

    @Test
    @DisplayName("hủy lời mời giải phóng BUSY cho cả hai bên và thách đấu lại được")
    void cancelInviteFreesBothPlayers() throws Exception {
        String u1 = user("iv1_"), u2 = user("iv2_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            // Thách đấu -> cả hai BUSY
            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T), "đối thủ phải nhận INVITE");
            assertEquals("BUSY", stateOf(c1, u1), "người gửi phải BUSY");
            assertEquals("BUSY", stateOf(c2, u2), "người nhận phải BUSY");

            // Hủy lời mời
            c1.send("CANCEL_INVITE");
            assertNotNull(c2.await("INVITE_CANCELLED", T), "đối thủ phải nhận INVITE_CANCELLED");
            assertEquals("FREE", stateOf(c1, u1), "người gửi phải FREE sau khi hủy");
            assertEquals("FREE", stateOf(c2, u2), "người nhận phải FREE sau khi hủy");

            // Thách đấu lại được (BUSY đã được giải phóng)
            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T), "phải thách đấu lại được");
        }
    }

    @Test
    @DisplayName("từ chối lời mời giải phóng BUSY cho cả hai bên")
    void rejectInviteFreesBothPlayers() throws Exception {
        String u1 = user("iv3_"), u2 = user("iv4_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T));
            c2.send("REJECT|" + u1);
            assertNotNull(c1.await("INVITE_REJECTED", T), "phải nhận INVITE_REJECTED");
            assertEquals("FREE", stateOf(c1, u1), "người gửi phải FREE sau khi bị từ chối");
            assertEquals("FREE", stateOf(c2, u2), "người từ chối phải FREE");
        }
    }

    @Test
    @DisplayName("ACCEPT với sai tên không được làm kẹt BUSY")
    void acceptWithWrongNameDoesNotStuckBusy() throws Exception {
        String u1 = user("iv5_"), u2 = user("iv6_");
        try (TestClient c1 = TestClient.connect(PORT); TestClient c2 = TestClient.connect(PORT)) {
            c1.send("REGISTER|" + u1 + "|pw123"); c1.await("REGISTER", T);
            c2.send("REGISTER|" + u2 + "|pw123"); c2.await("REGISTER", T);
            c1.send("LOGIN|" + u1 + "|pw123"); assertNotNull(c1.await("LOGIN_OK", T));
            c2.send("LOGIN|" + u2 + "|pw123"); assertNotNull(c2.await("LOGIN_OK", T));

            c1.send("CHALLENGE|" + u2);
            assertNotNull(c2.await("INVITE|", T));
            // Người nhận ACCEPT nhưng ghi sai tên người gửi -> phải bị từ chối.
            c2.send("ACCEPT|khong_phai_" + u1);
            Thread.sleep(1_200);
            assertEquals("FREE", stateOf(c2, u2), "người nhận phải FREE khi accept sai tên");

            // Dọn dẹp
            c1.send("CANCEL_INVITE");
            Thread.sleep(800);
        }
    }
}
