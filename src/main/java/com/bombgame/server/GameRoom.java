package com.bombgame.server;

import java.util.Random;
import java.util.concurrent.*;

/** Phòng chơi: server là trọng tài, giữ bí mật quả bom. */
public class GameRoom {
    /** Scheduler cho logic ván đấu (timeout, chuyển hiệp). Tách khỏi DB để query chậm không chặn game. */
    static final ScheduledExecutorService SCH = Executors.newScheduledThreadPool(8, r -> { Thread t = new Thread(r, "game"); t.setDaemon(true); return t; });
    /** Executor riêng cho truy vấn DB: lỗi/kết nối chậm không được làm treo logic trận đấu. */
    static final ExecutorService DB = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "db"); t.setDaemon(true); return t; });
    static final int MAX_ROUND = 5, TURN_SEC = 15;

    private final ClientHandler[] p;
    private final Integer[] g = new Integer[2];
    private final boolean[] yes = new boolean[2];
    private final Random rnd = new Random();
    private int bomb, round, min, max;
    private boolean over, ended;
    private ScheduledFuture<?> timer;

    GameRoom(ClientHandler a, ClientHandler b) { p = new ClientHandler[]{a, b}; }

    private int idx(ClientHandler h) { return h == p[0] ? 0 : 1; }

    synchronized void start() { newGame(); }

    private void newGame() {
        bomb = 1 + rnd.nextInt(100); round = 0; min = 1; max = 100; over = false;
        yes[0] = yes[1] = false;
        for (int i = 0; i < 2; i++) p[i].send("MATCH_START|" + p[1 - i].name);
        nextRound();
    }

    private void nextRound() {
        round++; g[0] = g[1] = null;
        for (ClientHandler h : p) h.send("ROUND|" + round + "|" + min + "|" + max + "|" + TURN_SEC);
        final int r = round;
        timer = SCH.schedule(() -> timeout(r), TURN_SEC + 1, TimeUnit.SECONDS);
    }

    /** Hết giờ: người chưa chọn bị server chọn ngẫu nhiên trong dải an toàn. */
    private synchronized void timeout(int r) {
        if (over || ended || r != round) return;
        for (int i = 0; i < 2; i++) if (g[i] == null) g[i] = min + rnd.nextInt(max - min + 1);
        resolve();
    }

    synchronized void guess(ClientHandler h, int n) {
        if (over || ended) return;
        int i = idx(h);
        if (g[i] != null || n < min || n > max) return;
        g[i] = n;
        if (g[0] != null && g[1] != null) resolve();
    }

    private void resolve() {
        if (timer != null) timer.cancel(false);
        boolean h0 = g[0] == bomb, h1 = g[1] == bomb;
        if (h0 || h1) {
            sendResult();
            finish(h0 && h1 ? 0.5 : h1 ? 1 : 0, "BOOM");
            return;
        }
        for (int i = 0; i < 2; i++) {
            if (g[i] < bomb) min = Math.max(min, g[i] + 1);
            else max = Math.min(max, g[i] - 1);
        }
        sendResult();
        if (round >= MAX_ROUND) {
            int d0 = Math.abs(g[0] - bomb), d1 = Math.abs(g[1] - bomb);
            finish(d0 < d1 ? 1 : d0 > d1 ? 0 : 0.5, "FINAL");
        } else {
            SCH.schedule(() -> { synchronized (this) { if (!over && !ended) nextRound(); } }, 3, TimeUnit.SECONDS);
        }
    }

    private void sendResult() {
        for (int i = 0; i < 2; i++) p[i].send("RESULT|" + g[i] + "|" + g[1 - i] + "|" + min + "|" + max);
    }

    /**
     * Kết thúc ván: ghi điểm và báo kết quả cho cả hai bên.
     * Mọi thao tác DB chạy ở executor riêng và được bọc try-catch, để khi MySQL chết
     * thì client VẪN nhận được GAME_OVER (không treo màn hình đếm mãi).
     */
    private void finish(double p0, String reason) {
        over = true;
        final double[] pts = {p0, 1 - p0};
        final int b = bomb;
        SCH.schedule(() -> {
            for (int i = 0; i < 2; i++) {
                String o = pts[i] == 1 ? "WIN" : pts[i] == 0 ? "LOSE" : "DRAW";
                p[i].send("GAME_OVER|" + o + "|" + b + "|" + reason + "|" + pts[i] + "|" + pts[1 - i]);
            }
            GameServer.broadcastPlayers();
        }, 2500, TimeUnit.MILLISECONDS);

        // Ghi điểm + làm mới thống kê ở luồng DB riêng; lỗi DB không được ảnh hưởng kết quả trận.
        DB.execute(() -> {
            for (int i = 0; i < 2; i++) {
                try { PlayerDao.addResult(p[i].name, pts[i]); }
                catch (Exception e) { logDb("ghi điểm", p[i].name, e); }
            }
            for (int i = 0; i < 2; i++) {
                try { p[i].refreshStats(); }
                catch (Exception e) { logDb("làm mới thống kê", p[i].name, e); }
            }
            GameServer.invalidateBoard();      // điểm mới -> bảng xếp hạng phải cập nhật ngay
            try { GameServer.broadcastPlayers(); }
            catch (Exception e) { logDb("broadcast", "-", e); }
        });
    }

    /** Ghi log lỗi DB ra console, không làm sập tiến trình server. */
    static void logDb(String action, String who, Exception e) {
        System.err.println("[BombServer] Lỗi DB khi " + action + " (" + who + "): " + e);
    }

    synchronized void rematch(ClientHandler h, boolean ok) {
        if (!over || ended) return;
        if (!ok) { leave(h); return; }
        yes[idx(h)] = true;
        if (yes[0] && yes[1]) newGame();
    }

    synchronized void leave(ClientHandler h) {
        if (ended) return;
        ended = true;
        if (timer != null) timer.cancel(false);
        p[1 - idx(h)].send((over ? "REMATCH_END|" : "OPP_LEFT|") + h.name);
        for (ClientHandler q : p) { q.room = null; q.busy = false; }
        GameServer.broadcastPlayers();
    }
}
