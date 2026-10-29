package com.bombgame.client;

import com.bombgame.util.Log;

import javax.swing.SwingUtilities;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Kết nối socket tới server.
 *
 * Có heartbeat PING/PONG: nếu server chết mà không gửi FIN/RST (rớt mạng, máy treo),
 * lệnh readLine sẽ treo vô hạn và client tưởng đang chơi bình thường. Timer gửi PING
 * mỗi {@link #PING_INTERVAL_MS} ms; nếu quá {@link #PONG_TIMEOUT_MS} ms không nhận được
 * PONG thì coi như mất kết nối và báo về giao diện.
 */
public class NetClient {
    /** Khoảng cách giữa 2 lần gửi PING. */
    public static final long PING_INTERVAL_MS = 15_000;
    /** Quá thời gian này không nhận được PONG thì coi như mất kết nối. */
    public static final long PONG_TIMEOUT_MS = 45_000;

    private Socket s;
    private PrintWriter out;
    private volatile long lastPong = System.currentTimeMillis();
    private volatile boolean alive = true;
    private Timer pingTimer;

    public boolean connected() { return s != null && !s.isClosed() && alive; }

    public void connect(String host, int port, Consumer<String[]> handler) throws IOException {
        s = new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setKeepAlive(true);
        out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        alive = true;
        lastPong = System.currentTimeMillis();

        final Socket sock = s;
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] m = line.split("\\|", -1);
                    if (m.length > 0 && "PONG".equals(m[0])) { lastPong = System.currentTimeMillis(); continue; }
                    SwingUtilities.invokeLater(() -> {
                        try { handler.accept(m); } catch (RuntimeException e) {
                            Log.error("Bỏ qua message lỗi: {} -> {}", String.join("|", m), e.toString());
                        }
                    });
                }
            } catch (IOException ignored) { }
            alive = false;
            SwingUtilities.invokeLater(() -> { if (sock == s) handler.accept(new String[]{"DISCONNECTED"}); });
        }, "net-reader");
        t.setDaemon(true);
        t.start();

        // Timer heartbeat: gửi PING, kiểm tra PONG, báo mất kết nối.
        pingTimer = new Timer(PING_INTERVAL_MS, () -> {
            if (!connected()) return;
            if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
                // Không còn nhận được PONG -> server đã chết/treo.
                alive = false;
                close();
                SwingUtilities.invokeLater(() -> handler.accept(new String[]{"DISCONNECTED"}));
                return;
            }
            send("PING");
        });
        pingTimer.start();
    }

    public void send(String m) { if (out != null) out.println(m); }

    public void close() {
        alive = false;
        if (pingTimer != null) { pingTimer.stop(); pingTimer = null; }
        try { if (s != null) s.close(); } catch (IOException ignored) { }
        s = null;
    }

    /** Timer đơn giản dùng java.util.Timer (javax.swing.Timer không phù hợp cho việc nền). */
    private static final class Timer {
        private final java.util.Timer t = new java.util.Timer("heartbeat", true);
        private final long period;
        private java.util.TimerTask task;

        Timer(long period, Runnable onTick) {
            this.period = period;
            this.task = new java.util.TimerTask() { @Override public void run() { onTick.run(); } };
        }
        void start() { t.scheduleAtFixedRate(task, period, period); }
        void stop() { task.cancel(); t.cancel(); }
    }
}
