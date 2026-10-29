package com.bombgame.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit test cho {@link Config}: ưu tiên system property > file > mặc định. */
class ConfigTest {

    @Test
    @DisplayName("giá trị mặc định được dùng khi không có cấu hình nào")
    void defaultValue() {
        assertEquals("fallback", Config.get("khong.tontai.key.xyz", "fallback"));
        assertEquals(42, Config.getInt("khong.tontai.key.xyz", 42));
    }

    @Test
    @DisplayName("system property được ưu tiên cao nhất")
    void systemPropertyWins() {
        System.setProperty("test.abc", "tu-system-property");
        try {
            assertEquals("tu-system-property", Config.get("test.abc", "fallback"));
        } finally {
            System.clearProperty("test.abc");
        }
    }

    @Test
    @DisplayName("giá trị rỗng hoặc toàn khoảng trắng được coi là không có")
    void blankFallsBack() {
        System.setProperty("test.blank", "   ");
        try {
            assertEquals("fallback", Config.get("test.blank", "fallback"));
        } finally {
            System.clearProperty("test.blank");
        }
    }

    @Test
    @DisplayName("getInt trả về mặc định khi giá trị không phải số")
    void getIntInvalid() {
        System.setProperty("test.notnum", "abc");
        try {
            assertEquals(7, Config.getInt("test.notnum", 7));
        } finally {
            System.clearProperty("test.notnum");
        }
    }

    @Test
    @DisplayName("getInt đọc đúng số")
    void getIntValid() {
        System.setProperty("test.num", "1234");
        try {
            assertEquals(1234, Config.getInt("test.num", 7));
        } finally {
            System.clearProperty("test.num");
        }
    }

    @Test
    @DisplayName("giá trị được cắt khoảng trắng thừa")
    void trimsValue() {
        System.setProperty("test.trim", "  x  ");
        try {
            assertEquals("x", Config.get("test.trim", "fallback"));
        } finally {
            System.clearProperty("test.trim");
        }
    }
}
