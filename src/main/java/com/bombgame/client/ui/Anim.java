package com.bombgame.client.ui;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Đồng hồ animation DUY NHẤT của cả ứng dụng.
 *
 * Trước đây mỗi nút / panel / nền tự giữ một Timer riêng và tự repaint, nên một khung hình bị vẽ lại
 * nhiều lần. Giờ: một Timer -> cập nhật logic (Tick) -> repaint đúng 1 lần cho mỗi cửa sổ (target).
 * Mọi thành phần chỉ việc đọc {@link #time()} khi vẽ.
 *
 * Có bộ "điều tốc" tự giảm chất lượng hiệu ứng ({@link #quality()}) nếu máy không theo kịp.
 */
public final class Anim {
    public interface Tick { void tick(double dt); }

    private static final List<Tick> TICKS = new CopyOnWriteArrayList<>();
    private static final List<Component> TARGETS = new CopyOnWriteArrayList<>();

    private static double time, dt = 1 / 60.0;
    private static volatile int quality = 2;          // 2 = cao, 1 = vừa, 0 = thấp
    private static int fps = 30, slow, fast;
    private static boolean idle;
    private static long last;
    private static Timer timer;

    private Anim() { }

    /** Thời gian animation (giây), chỉ tăng khi cửa sổ đang hiển thị. */
    public static double time() { return time; }

    public static double dt() { return dt; }

    /** 2 = đầy đủ hiệu ứng, 1 = giảm bớt, 0 = tối giản. Tự hạ khi máy yếu. */
    public static int quality() { return quality; }

    public static void addTick(Tick t) { if (!TICKS.contains(t)) TICKS.add(t); }

    public static void removeTick(Tick t) { TICKS.remove(t); }

    /** Cửa sổ / panel gốc sẽ được repaint mỗi khung hình. */
    public static void addTarget(Component c) { if (!TARGETS.contains(c)) TARGETS.add(c); }

    public static void removeTarget(Component c) { TARGETS.remove(c); }

    /** Trong ván đấu chạy 60 FPS cho mượt, ngoài sảnh 30 FPS cho nhẹ máy. */
    public static void setGameMode(boolean game) {
        fps = game ? 60 : 30;
        slow = 0;
        fast = 0;
        if (timer != null && !idle) timer.setDelay(1000 / fps);
    }

    public static synchronized void start() {
        if (timer != null || GraphicsEnvironment.isHeadless()) return;
        last = System.nanoTime();
        timer = new Timer(1000 / fps, e -> onTimer());
        timer.setCoalesce(true);
        timer.start();
    }

    private static void onTimer() {
        boolean showing = false;
        for (Component c : TARGETS) if (c.isShowing()) { showing = true; break; }
        long now = System.nanoTime();
        double real = (now - last) / 1e9;
        last = now;
        if (!showing) {                                   // cửa sổ ẩn / thu nhỏ: ngủ đông, không tốn CPU
            if (!idle) { idle = true; timer.setDelay(250); }
            return;
        }
        if (idle) { idle = false; timer.setDelay(1000 / fps); slow = 0; real = 1.0 / fps; }
        governor(real);
        step(Math.min(real, .05));
    }

    /**
     * Bộ điều tốc chất lượng hiệu ứng.
     * - Máy chậm liên tục -> hạ dần quality (2 -> 1 -> 0) để giữ FPS mượt.
     * - Máy chạy mượt trở lại -> NÂNG lại quality, nếu không thì một lần lag là giao diện
     *   xấu suốt phiên (trước đây quality chỉ giảm, không bao giờ hồi phục).
     */
    private static void governor(double real) {
        double expect = 1.0 / fps;
        if (real > expect * 2.2) {
            slow++;
            fast = 0;
            if (slow > 40 && quality > 0) { quality--; slow = 0; }
        } else {
            if (slow > 0) slow--;
            // Chạy mượt liên tục ~8 giây -> nâng chất lượng lên 1 bậc.
            if (real < expect * 1.25) {
                fast++;
                if (fast > (int) (8 * fps) && quality < 2) { quality++; fast = 0; slow = 0; }
            } else {
                fast = 0;
            }
        }
    }

    /** Chạy một khung hình: cập nhật logic rồi repaint. Public để test/benchmark gọi trực tiếp. */
    public static void step(double d) {
        dt = d;
        time += d;
        for (Tick t : TICKS) t.tick(d);
        for (Component c : TARGETS) c.repaint();
    }
}
