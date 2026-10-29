package com.bombgame.server;

import com.bombgame.util.Log;

import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Server trung tâm: quản lý người chơi online và mở luồng cho từng client. */
public class GameServer {
    /** Cổng lắng nghe (đọc từ config.properties / -Dgame.port=...). */
    public static final int PORT = Config.getInt("game.port", 9999);
    static final Map<String, ClientHandler> ONLINE = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Log.init("server");
        // DB lỗi không được làm server chết: vẫn cho phép vào sảnh chờ, chỉ ghi điểm/leaderboard là lỗi.
        try {
            Database.init();
            Log.info("Đã kết nối MySQL thành công.");
        } catch (Exception e) {
            Log.warn("Không kết nối được MySQL ({}). Server vẫn chạy nhưng sẽ không lưu điểm và bảng xếp hạng.",
                    e.getMessage());
        }
        try (ServerSocket ss = new ServerSocket(PORT)) {
            Log.info("Đang lắng nghe cổng {}", PORT);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.info("Đang tắt server...");
                Log.flush();
            }));
            while (true) new ClientHandler(ss.accept()).start();
        }
    }

    /**
     * Gửi danh sách người chơi online TỚI TẤT CẢ client, kèm luôn bảng xếp hạng.
     *
     * Trước đây client tự gửi lệnh "BOARD" mỗi khi nhận "PLAYERS" -> với N người online là
     * N truy vấn leaderboard() cho mỗi sự kiện (login/leave/challenge). Nay server gửi kèm
     * luôn và cache bảng xếp hạng trong {@link #BOARD_CACHE_MS} để giảm tải MySQL.
     */
    static void broadcastPlayers() {
        String s = ONLINE.values().stream().filter(h -> h.name != null)
            .map(h -> h.name + "," + String.format(Locale.ROOT, "%.1f", h.score) + "," + h.wins + "," + (h.busy ? "BUSY" : "FREE"))
            .collect(Collectors.joining(";"));
        String board = leaderboardCached();
        ONLINE.values().forEach(h -> {
            h.send("PLAYERS|" + s);
            h.send("BOARD|" + board);
        });
    }

    /** Bảng xếp hạng có cache để không phải query DB liên tục. */
    static String leaderboardCached() {
        long now = System.currentTimeMillis();
        String cached = boardCache;
        if (cached != null && now - boardTime < BOARD_CACHE_MS) return cached;
        synchronized (GameServer.class) {
            if (boardCache != null && now - boardTime < BOARD_CACHE_MS) return boardCache;
            boardCache = PlayerDao.leaderboard();
            boardTime = System.currentTimeMillis();
            return boardCache;
        }
    }

    /** Xóa cache bảng xếp hạng (gọi sau khi có kết quả trận để điểm mới hiển thị ngay). */
    static void invalidateBoard() { boardCache = null; }

    private static final long BOARD_CACHE_MS = 3000;
    private static volatile String boardCache;
    private static volatile long boardTime;
}
