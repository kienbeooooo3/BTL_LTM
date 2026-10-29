package com.bombgame.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeUnit;

/** Xử lý 1 client: đăng nhập, thách đấu, chuyển lệnh vào phòng chơi. */
public class ClientHandler extends Thread {
    private final Socket sock;
    private PrintWriter out;
    volatile String name;
    volatile double score;
    volatile int wins;
    volatile boolean busy;
    volatile GameRoom room;
    volatile String invitedBy, invitedTarget;
    /** Lệnh hủy lời mời đang chờ (dùng để hủy timer khi accept/reject xử lý xong). */
    private volatile String inviteCancelToken;
    /** Hẹn giờ tự hủy lời mời nếu đối thủ không phản hồi trong {@link #INVITE_TIMEOUT_MS}. */
    private static final long INVITE_TIMEOUT_MS = 30_000;

    ClientHandler(Socket s) { this.sock = s; setDaemon(true); }

    synchronized void send(String s) { if (out != null) out.println(s); }

    /** Làm mới điểm/thắng từ DB. Lỗi DB không được làm treo luồng client. */
    void refreshStats() {
        try {
            double[] st = PlayerDao.stats(name);
            score = st[0]; wins = (int) st[1];
        } catch (Exception e) {
            GameRoom.logDb("làm mới thống kê", name, e);
        }
    }

    @Override public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
            String line;
            while ((line = in.readLine()) != null) {
                try { handle(line.split("\\|", -1)); }
                catch (Exception e) { send("ERROR|" + e.getMessage()); }
            }
        } catch (IOException ignored) {
        } finally { cleanup(); }
    }

    private void handle(String[] m) {
        if (name == null) {
            if (m.length < 3) return;
            String u = m[1].trim().toLowerCase(), p = m[2];
            if (m[0].equals("REGISTER")) {
                if (!u.matches("[a-z0-9_]{3,16}") || p.length() < 3) { send("LOGIN_FAIL|Tên 3-16 ký tự (a-z, 0-9, _), mật khẩu ≥ 3 ký tự"); return; }
                send(PlayerDao.register(u, p) ? "REGISTER_OK" : "LOGIN_FAIL|Tên đã tồn tại");
            } else if (m[0].equals("LOGIN")) {
                if (!PlayerDao.login(u, p)) { send("LOGIN_FAIL|Sai tên đăng nhập hoặc mật khẩu"); return; }
                synchronized (GameServer.class) {
                    if (GameServer.ONLINE.containsKey(u)) { send("LOGIN_FAIL|Tài khoản đang đăng nhập ở nơi khác"); return; }
                    name = u; GameServer.ONLINE.put(u, this);
                }
                refreshStats();
                send("LOGIN_OK|" + u);
                GameServer.broadcastPlayers();
            }
            return;
        }
        switch (m[0]) {
            case "CHALLENGE" -> challenge(m[1]);
            case "ACCEPT" -> accept(m[1]);
            case "REJECT" -> reject(m[1]);
            case "CANCEL_INVITE" -> cancelInvite();
            case "GUESS" -> { GameRoom r = room; if (r != null) r.guess(this, Integer.parseInt(m[1])); }
            case "LEAVE" -> { GameRoom r = room; if (r != null) r.leave(this); }
            case "REMATCH" -> { GameRoom r = room; if (r != null) r.rematch(this, m[1].equals("1")); }
            case "BOARD" -> send("BOARD|" + GameServer.leaderboardCached());
            default -> { }
        }
    }

    private void challenge(String target) {
        synchronized (GameServer.class) {
            ClientHandler t = GameServer.ONLINE.get(target);
            if (t == null || t == this || t.busy || busy) { send("INVITE_FAIL|Đối thủ không rảnh"); return; }
            busy = t.busy = true; invitedTarget = target; t.invitedBy = name;
            t.send("INVITE|" + name);
            scheduleInviteTimeout(t);   // tự hủy sau 30s nếu đối thủ im lặng -> không kẹt BUSY vĩnh viễn
        }
        GameServer.broadcastPlayers();
    }

    /** Hẹn giờ hủy lời mời nếu sau {@link #INVITE_TIMEOUT_MS} đối thủ không phản hồi. */
    private void scheduleInviteTimeout(ClientHandler target) {
        final String token = name + "#" + System.nanoTime();
        inviteCancelToken = token;
        target.inviteCancelToken = token;
        GameRoom.SCH.schedule(() -> {
            synchronized (GameServer.class) {
                // Chỉ hủy nếu lời mời vẫn còn nguyên (chưa accept/reject/hủy).
                if (!token.equals(inviteCancelToken) || !token.equals(target.inviteCancelToken)) return;
                inviteCancelToken = null; target.inviteCancelToken = null;
                if (invitedTarget != null) { invitedTarget = null; busy = false; }
                if (target.invitedBy != null) {
                    target.invitedBy = null; target.busy = false;
                    target.send("INVITE_EXPIRED|" + name);
                }
            }
            GameServer.broadcastPlayers();
        }, INVITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Người thách tự hủy lời mời đang chờ. */
    private void cancelInvite() {
        synchronized (GameServer.class) {
            ClientHandler t = invitedTarget == null ? null : GameServer.ONLINE.get(invitedTarget);
            invitedTarget = null; busy = false;
            if (t != null && name.equals(t.invitedBy)) {
                t.invitedBy = null; t.busy = false;
                t.send("INVITE_CANCELLED|" + name);
            }
        }
        GameServer.broadcastPlayers();
    }

    private void accept(String from) {
        synchronized (GameServer.class) {
            ClientHandler f = GameServer.ONLINE.get(from);
            // Kiểm tra đúng chiều: chính mình phải là người được mời (invitedBy của mình trỏ tới from).
            if (f == null || !from.equals(invitedBy)) { busy = false; invitedBy = null; }
            else {
                invitedBy = null;
                f.invitedTarget = null; f.busy = false;
                inviteCancelToken = null; f.inviteCancelToken = null;   // hủy hẹn giờ của cả hai bên
                GameRoom r = new GameRoom(f, this);
                f.room = room = r;
                r.start();
            }
        }
        GameServer.broadcastPlayers();
    }

    private void reject(String from) {
        synchronized (GameServer.class) {
            ClientHandler f = GameServer.ONLINE.get(from);
            if (f != null && from.equals(invitedBy)) {
                invitedBy = null; f.invitedTarget = null; f.busy = false;
                f.inviteCancelToken = null; inviteCancelToken = null;
                f.send("INVITE_REJECTED|" + name);
            }
            invitedBy = null; busy = false;
        }
        GameServer.broadcastPlayers();
    }

    private void cleanup() {
        if (name != null) {
            GameServer.ONLINE.remove(name);
            GameRoom r = room;
            if (r != null) r.leave(this);
            synchronized (GameServer.class) {
                if (invitedBy != null) {
                    ClientHandler f = GameServer.ONLINE.get(invitedBy);
                    if (f != null) { f.busy = false; f.invitedTarget = null; f.inviteCancelToken = null; f.send("INVITE_REJECTED|" + name); }
                }
                if (invitedTarget != null) {
                    ClientHandler t = GameServer.ONLINE.get(invitedTarget);
                    if (t != null) { t.busy = false; t.invitedBy = null; t.inviteCancelToken = null; t.send("INVITE_CANCELLED|" + name); }
                }
                inviteCancelToken = null;
            }
            GameServer.broadcastPlayers();
        }
        try { sock.close(); } catch (IOException ignored) { }
    }
}
