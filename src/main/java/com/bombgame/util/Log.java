package com.bombgame.util;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Logger đơn giản, không phụ thuộc thư viện ngoài.
 *
 * <ul>
 *   <li>Ghi ra console (stdout cho INFO/DEBUG, stderr cho WARN/ERROR).</li>
 *   <li>Ghi ra file theo ngày: {@code logs/bombgame-YYYY-MM-DD.log}.</li>
 *   <li>Có level tối thiểu, đọc từ {@code log.level} (config.properties / -Dlog.level=DEBUG).</li>
 * </ul>
 *
 * Ghi log chạy trên luồng riêng (daemon) để I/O chậm không chặn logic game; nếu luồng log
 * bị lỗi thì bỏ qua, tuyệt đối không được làm sập server.
 */
public final class Log {
    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    /** Khoá riêng cho thao tác ghi/đóng file - tách khỏi LOCK để tránh khoá chéo. */
    private static final Object WRITE_LOCK = new Object();

    private static volatile Level minLevel = Level.INFO;
    private static volatile Path logDir;
    private static volatile PrintWriter fileWriter;
    private static volatile LocalDate fileDate;
    private static volatile Path lastDir;
    private static boolean closed;
    private static Thread worker;
    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(10_000);

    private Log() { }

    /** Khởi tạo logger: đọc level + thư mục log từ cấu hình. */
    public static void init(String name) {
        String lv = prop("log.level", "INFO");
        try { minLevel = Level.valueOf(lv.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { minLevel = Level.INFO; }

        String dir = prop("log.dir", "logs");
        try {
            logDir = Paths.get(dir);
            Files.createDirectories(logDir);
        } catch (IOException e) {
            logDir = null;                      // không ghi được file -> chỉ log console
        }
        startWorker(name);
        info("Logger khởi động", "level=" + minLevel, "file=" + (logDir == null ? "(console only)" : dir));
    }

    /**
     * Đọc cấu hình: ưu tiên system property (-Dlog.level=DEBUG), sau đó tới config.properties.
     * Logger nằm ở gói util nên không dùng được {@code Config} của gói server.
     */
    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        try (InputStream in = new FileInputStream("config.properties")) {
            Properties p = new Properties();
            p.load(in);
            v = p.getProperty(key);
            if (v != null && !v.isBlank()) return v.trim();
        } catch (Exception ignored) { }
        return def;
    }

    private static void startWorker(String name) {
        synchronized (LOCK) {
            if (worker != null && worker.isAlive()) return;
            closed = false;
            worker = new Thread(() -> {
                while (!closed) {
                    String line;
                    try { line = QUEUE.take(); }
                    catch (InterruptedException e) { return; }
                    // Ghi dưới WRITE_LOCK để shutdown() không đóng file giữa lúc đang ghi.
                    synchronized (WRITE_LOCK) {
                        if (closed) { QUEUE.offer(line); return; }   // trả lại để shutdown xử lý
                        try { writeFile(line); } catch (Exception ignored) { }
                    }
                }
            }, "logger-" + name);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void writeFile(String line) throws IOException {
        LocalDate today = LocalDate.now();
        // Mở lại file nếu ngày đổi HOẶC thư mục log đổi (log.dir có thể đặt lại khi chạy lại).
        if (fileWriter == null || !today.equals(fileDate) || logDir != lastDir) {
            if (fileWriter != null) fileWriter.close();
            Path f = logDir.resolve("bombgame-" + today + ".log");
            fileWriter = new PrintWriter(new BufferedWriter(new FileWriter(f.toFile(), true)));
            fileDate = today;
            lastDir = logDir;
        }
        fileWriter.println(line);
        fileWriter.flush();
    }

    public static void debug(String msg, Object... kv) { log(Level.DEBUG, msg, kv); }
    public static void info(String msg, Object... kv) { log(Level.INFO, msg, kv); }
    public static void warn(String msg, Object... kv) { log(Level.WARN, msg, kv); }
    public static void error(String msg, Object... kv) { log(Level.ERROR, msg, kv); }

    /**
     * Ghi log. Mọi lỗi trong quá trình ghi đều bị nuốt - logger không được làm sập ứng dụng.
     *
     * <p>Định dạng tham số: dùng {@code {}} làm placeholder, các tham số sau dạng
     * {@code key, value, key, value} được ghi nối ở cuối dòng dưới dạng {@code key=value}.
     * Ví dụ: {@code Log.info("Nghe cổng {}", PORT)}.
     */
    public static void log(Level lv, String msg, Object... kv) {
        if (lv.ordinal() < minLevel.ordinal()) return;

        StringBuilder body = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < msg.length()) {
            int ph = msg.indexOf("{}", i);
            if (ph < 0) { body.append(msg, i, msg.length()); break; }
            body.append(msg, i, ph);
            if (argIndex < kv.length) body.append(String.valueOf(kv[argIndex++]));
            else body.append("{}");
            i = ph + 2;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(TS))
          .append(' ').append(String.format("%-5s", lv))
          .append(" [").append(Thread.currentThread().getName()).append("] ")
          .append(body);
        // Tham số còn lại (dạng key, value) ghi nối ở cuối.
        if (kv != null) {
            for (int k = argIndex; k + 1 < kv.length; k += 2) {
                sb.append(' ').append(kv[k]).append('=').append(kv[k + 1]);
            }
        }
        String line = sb.toString();

        if (lv.ordinal() >= Level.WARN.ordinal()) System.err.println(line);
        else System.out.println(line);

        // Đưa vào hàng đợi để luồng riêng ghi file (không chặn)
        if (QUEUE.size() < 9_000) QUEUE.offer(line);
    }

    /**
     * Đóng file log (gọi khi tắt server).
     * Trước khi đóng, đẩy hết dòng còn trong hàng đợi ra file để không mất log cuối cùng.
     */
    public static void flush() {
        synchronized (WRITE_LOCK) {
            // Đẩy nốt các dòng đang chờ trong hàng đợi (worker có thể chưa kịp ghi).
            String line;
            while ((line = QUEUE.poll()) != null) {
                try { writeFile(line); } catch (Exception ignored) { }
            }
            if (fileWriter != null) { fileWriter.flush(); fileWriter.close(); fileWriter = null; }
        }
    }

    /** Tắt hẳn logger: dừng luồng ghi file và đóng file. Dùng khi kết thúc ứng dụng/test. */
    public static void shutdown() {
        // Dừng worker trước để không có dòng nào đang được ghi dở.
        synchronized (LOCK) { closed = true; }
        Thread w = worker;
        if (w != null) { w.interrupt(); try { w.join(2000); } catch (InterruptedException ignored) { } }
        worker = null;
        // Dọn hàng đợi và đóng file (dưới WRITE_LOCK để không đụng worker).
        synchronized (WRITE_LOCK) {
            String line;
            while ((line = QUEUE.poll()) != null) {
                try { writeFile(line); } catch (Exception ignored) { }
            }
            if (fileWriter != null) { fileWriter.flush(); fileWriter.close(); fileWriter = null; }
        }
    }
}
