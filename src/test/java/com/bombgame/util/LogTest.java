package com.bombgame.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Unit test cho {@link Log}: level, placeholder, ghi file theo ngày. */
class LogTest {

    @Test
    @DisplayName("placeholder {} được thay bằng tham số")
    void placeholderIsReplaced() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            Log.info("Nghe cổng {}", 9999);
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Nghe cổng 9999"), "phải thay placeholder: " + out);
        assertFalse(out.contains("{}"), "không được còn placeholder: " + out);
    }

    @Test
    @DisplayName("nhiều placeholder được thay theo thứ tự")
    void multiplePlaceholders() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            Log.info("a={} b={}", 1, 2);
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("a=1 b=2"), out);
    }

    @Test
    @DisplayName("thiếu tham số thì giữ nguyên placeholder, không ném exception")
    void missingArgumentKeepsPlaceholder() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            Log.info("thiếu {}", "co", "khu", "vai");
        } finally {
            System.setOut(old);
        }
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("thiếu co"),
                "tham số dạng key/value phải được ghi nối");
    }

    @Test
    @DisplayName("log được ghi ra file theo ngày")
    void writesToDailyFile(@TempDir Path dir) throws Exception {
        System.setProperty("log.dir", dir.toString());
        try {
            Log.init("test");
            Log.info("dòng thử nghiệm");
            Log.shutdown();
            Path f = dir.resolve("bombgame-" + java.time.LocalDate.now() + ".log");
            assertTrue(Files.exists(f), "phải tạo file log theo ngày: " + f);
            String content = Files.readString(f, StandardCharsets.UTF_8);
            assertTrue(content.contains("dòng thử nghiệm"), "file phải chứa nội dung log");
        } finally {
            System.clearProperty("log.dir");
        }
    }

    @Test
    @DisplayName("level DEBUG bị lọc khi min level là INFO")
    void debugIsFiltered() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            Log.debug("khong duoc in");
            Log.info("duoc in");
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("khong duoc in"), "DEBUG phải bị lọc khi level=INFO");
        assertTrue(out.contains("duoc in"));
    }

    @Test
    @DisplayName("ERROR đi ra stderr, INFO đi ra stdout")
    void errorGoesToStderr() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Log.error("lỗi nghiêm trọng");
        } finally {
            System.setErr(oldErr);
        }
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("lỗi nghiêm trọng"));
    }
}
