package com.bombgame.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiện ích cho test: tạo một "client giả" nối thật vào server và thu thập message trả về.
 * Dùng socket loopback thật nên test được cả đường truyền (encode/decode UTF-8) chứ không
 * chỉ logic bên trong.
 */
public final class TestClient implements AutoCloseable {
    private final Socket sock;
    private final PrintWriter out;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());

    private TestClient(Socket sock) throws IOException {
        this.sock = sock;
        this.out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) received.add(line);
            } catch (IOException ignored) { }
        }, "test-client-reader");
        t.setDaemon(true);
        t.start();
    }

    /** Kết nối tới server (mặc định localhost:9999). */
    public static TestClient connect(int port) throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(30_000);
        return new TestClient(s);
    }

    public void send(String msg) { out.println(msg); }

    /** Chờ message có tiền tố, trả về null nếu hết thời gian. */
    public String await(String prefix, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            synchronized (received) {
                for (int i = 0; i < received.size(); i++) {
                    if (received.get(i).startsWith(prefix)) return received.remove(i);
                }
            }
            Thread.sleep(30);
        }
        return null;
    }

    /** Chờ message có tiền tố, không tìm thấy thì fail test. */
    public String awaitRequired(String prefix, long timeoutMs) throws InterruptedException {
        String m = await(prefix, timeoutMs);
        if (m == null) throw new AssertionError("Không nhận được message '" + prefix + "' trong " + timeoutMs + "ms");
        return m;
    }

    /** Đọc các trường của message dạng "A|B|C". */
    public static String[] fields(String msg) { return msg.split("\\|", -1); }

    /** Đợi cho tới khi message chứa chuỗi con xuất hiện. */
    public String awaitContains(String needle, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            synchronized (received) {
                for (int i = 0; i < received.size(); i++) {
                    if (received.get(i).contains(needle)) return received.remove(i);
                }
            }
            Thread.sleep(30);
        }
        return null;
    }

    /** Tất cả message đã nhận (dùng để debug khi test fail). */
    public List<String> all() {
        synchronized (received) { return new ArrayList<>(received); }
    }

    @Override public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }

    /** Chờ server nhận kết nối (dùng khi muốn tự quản lý vòng đời). */
    public static Socket acceptOn(ServerSocket ss) throws IOException { return ss.accept(); }
}
