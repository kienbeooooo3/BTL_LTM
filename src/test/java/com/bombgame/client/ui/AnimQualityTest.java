package com.bombgame.client.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho bộ điều tốc chất lượng hiệu ứng của {@link Anim}.
 *
 * <p>Yêu cầu: máy chạy liên tục -&gt; quality phải hạ; sau đó máy chạy mượt -&gt; quality
 * phải HỒI PHỤC về mức tối đa (trước đây quality chỉ đi xuống mà không quay lại).
 *
 * <p>Không cần MySQL/server.
 */
class AnimQualityTest {

    /** Gọi private static governor() để mô phỏng các khung hình thật (timer gọi hàm này). */
    private static void governor(double real) throws Exception {
        Method m = Anim.class.getDeclaredMethod("governor", double.class);
        m.setAccessible(true);
        m.invoke(null, real);
    }

    @Test
    @DisplayName("máy chậm liên tục -> quality hạ xuống")
    void qualityDropsOnSlowFrames() throws Exception {
        int q0 = Anim.quality();
        for (int i = 0; i < 200; i++) governor(0.10);       // mỗi khung hình kéo dài gấp 3
        int qLow = Anim.quality();
        assertTrue(qLow < q0, "quality phải hạ khi máy chậm (" + q0 + " -> " + qLow + ")");
    }

    @Test
    @DisplayName("máy chạy mượt -> quality hồi phục về mức tối đa")
    void qualityRecoversOnSmoothFrames() throws Exception {
        for (int i = 0; i < 200; i++) governor(0.10);       // làm chậm trước
        int qLow = Anim.quality();

        for (int i = 0; i < 60 * 30; i++) governor(1.0 / 60.0);   // 30 giây khung hình mượt
        int qBack = Anim.quality();

        assertTrue(qBack > qLow, "quality phải hồi phục (" + qLow + " -> " + qBack + ")");
        assertEquals(2, qBack, "quality phải quay lại mức tối đa (2)");
    }

    @Test
    @DisplayName("quality luôn nằm trong khoảng 0..2")
    void qualityStaysInRange() throws Exception {
        for (int i = 0; i < 50; i++) {
            governor(0.10);
            int q = Anim.quality();
            assertTrue(q >= 0 && q <= 2, "quality ngoài khoảng hợp lệ: " + q);
        }
    }
}
