package com.bombgame.server;

import com.bombgame.util.Log;

import java.sql.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pool kết nối MySQL nhỏ gọn.
 *
 * Trước đây mỗi truy vấn đều mở một kết nối mới (DriverManager.getConnection) ->
 * mỗi kết nối tốn ~3 câu hỏi handshake/đặt biến (SET autocommit, SET character_set_results...)
 * nên tải query thực tế gấp nhiều lần so với số lệnh SQL. Pool giữ sẵn vài kết nối
 * để tái sử dụng, giảm tải MySQL đáng kể khi nhiều người chơi online.
 *
 * Kết nối hỏng sẽ bị loại bỏ và tạo mới.
 */
public final class DbPool {
    private static final int MAX = 8;
    private static final Deque<Connection> IDLE = new ArrayDeque<>();
    private static int created;

    private DbPool() { }

    /** Lấy một kết nối từ pool (tạo mới nếu hết). */
    public static synchronized Connection get() throws SQLException {
        Connection c = IDLE.poll();
        if (c != null) {
            try {
                if (!c.isClosed() && c.isValid(1)) return c;
                c.close();                                  // kết nối chết -> bỏ
            } catch (SQLException ignored) { }
        }
        Connection fresh = Database.get();
        created++;
        return fresh;
    }

    /** Trả kết nối về pool. */
    public static synchronized void release(Connection c) {
        if (c == null) return;
        try {
            if (c.isClosed()) return;
            if (IDLE.size() >= MAX) { c.close(); return; }
            // Chỉ đặt lại autoCommit khi thật sự cần -> tránh gửi "SET autocommit" mỗi lần.
            if (!c.getAutoCommit()) c.setAutoCommit(true);
            IDLE.push(c);
        } catch (SQLException e) {
            // Kết nối hỏng -> loại bỏ thay vì trả về pool (tránh lỗi lặp lại).
            Log.debug("Kết nối hỏng khi trả về pool: {}", e.getMessage());
            try { c.close(); } catch (SQLException ignored) { }
        }
    }

    /** Đóng toàn bộ pool (dùng khi tắt server). */
    public static synchronized void closeAll() {
        for (Connection c : IDLE) try { c.close(); } catch (SQLException ignored) { }
        IDLE.clear();
    }

    public static synchronized int created() { return created; }
}
