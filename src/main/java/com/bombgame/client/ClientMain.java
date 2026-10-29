package com.bombgame.client;

import com.bombgame.client.ui.*;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class ClientMain {
    private final JFrame f = new JFrame("Bomb Guess Online");
    private final CardLayout cl = new CardLayout();
    private final JPanel cards = new JPanel(cl);
    private final NetClient net = new NetClient();
    private final LoginPanel login;
    private final LobbyPanel lobby;
    private final GamePanel game;
    private String me, oppName = "";
    private boolean inGame;
    private NeonDialog rematchDlg;

    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        Theme.installDefaults();
        SwingUtilities.invokeLater(ClientMain::new);
    }

    private ClientMain() {
        AnimatedBackground bg = new AnimatedBackground(); bg.setLayout(new BorderLayout());
        cards.setOpaque(false);
        login = new LoginPanel(this::doLogin);
        lobby = new LobbyPanel(net);
        lobby.onLogout = this::doLogout;
        game = new GamePanel(net, this::toLobby);
        cards.add(login, "login"); cards.add(lobby, "lobby"); cards.add(game, "game");
        bg.add(cards);
        f.setContentPane(bg); f.setSize(1040, 720); f.setMinimumSize(new Dimension(900, 660));
        f.setLocationRelativeTo(null);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setIconImage(appIcon());
        Anim.addTarget(bg);                       // một đồng hồ duy nhất repaint cả cửa sổ
        Anim.start();
        f.setVisible(true);
        // Nạp trước âm thanh / giọng đọc ở luồng nền để lần phát đầu tiên không bị khựng
        Thread warm = new Thread(() -> { SoundFx.warmUp(); Speaker.warmUp(); }, "warm-up");
        warm.setDaemon(true); warm.start();
    }

    private static Image appIcon() {
        BufferedImage im = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setPaint(new GradientPaint(0, 0, new Color(0x302b63), 64, 64, new Color(0x0f0c29)));
        g.fillRoundRect(0, 0, 64, 64, 16, 16);
        Theme.bomb(g, 30, 38, 17, 0);
        g.dispose();
        return im;
    }

    private void show(String card) {
        cl.show(cards, card);
        Anim.setGameMode(card.equals("game"));   // 60 FPS trong ván, 30 FPS ngoài sảnh cho nhẹ máy
    }

    private void doLogin(String host, String cmd, String u, String p) {
        try {
            if (!net.connected()) {
                String h = host.trim(); int port = 9999;
                int c = h.lastIndexOf(':');
                if (c > 0) {
                    String ps = h.substring(c + 1).trim();
                    h = h.substring(0, c).trim();
                    if (!ps.isEmpty()) port = Integer.parseInt(ps);
                }
                if (h.isEmpty()) h = "localhost";
                if (port < 1 || port > 65535) throw new NumberFormatException("cổng không hợp lệ: " + port);
                net.connect(h, port, this::onMsg);
            }
            net.send(cmd + "|" + u + "|" + p);
        } catch (IOException | NumberFormatException e) { login.error("Không kết nối được server: " + e.getMessage()); }
    }

    /** Đăng xuất: báo server, đóng kết nối, quay về màn hình đăng nhập. */
    private void doLogout() {
        if (!NeonDialog.confirm(f, "Đăng xuất", "Bạn muốn đăng xuất khỏi tài khoản hiện tại?", "ĐĂNG XUẤT", "Ở LẠI")) return;
        inGame = false;
        me = ""; oppName = "";
        if (rematchDlg != null) rematchDlg.close();
        net.send("LOGOUT");
        net.close();
        show("login");
        login.info("Bạn đã đăng xuất.");
    }

    private void toLobby() {
        inGame = false;
        if (rematchDlg != null) rematchDlg.close();
        show("lobby"); lobby.toast(" "); lobby.setInvitePending(false); net.send("BOARD");
    }

    /**
     * Xử lý message từ server.
     * Bọc toàn bộ trong try-catch: một gói tin sai định dạng chỉ được bỏ qua chứ không được
     * làm nổ exception trên EDT (sẽ treo toàn bộ giao diện).
     */
    private void onMsg(String[] m) {
        try {
            dispatch(m);
        } catch (Exception e) {
            System.err.println("[Client] Bỏ qua message lỗi: " + String.join("|", m) + " -> " + e);
        }
    }

    private void dispatch(String[] m) {
        switch (m[0]) {
            case "LOGIN_OK" -> { me = m[1]; lobby.setMe(me); show("lobby"); net.send("BOARD"); }
            case "LOGIN_FAIL" -> login.error(m[1]);
            case "REGISTER_OK" -> login.info("Đăng ký thành công! Hãy đăng nhập.");
            case "PLAYERS" -> lobby.setPlayers(m[1]);   // server đã gửi kèm BOARD, không cần xin lại
            case "BOARD" -> lobby.setBoard(m[1]);
            case "INVITE_FAIL" -> { lobby.toast(m[1]); lobby.setInvitePending(false); }
            case "INVITE_REJECTED" -> { lobby.toast(m[1] + " đã từ chối lời thách đấu"); lobby.setInvitePending(false); }
            case "INVITE_EXPIRED" -> { lobby.toast(m[1] + " không phản hồi — lời thách đấu đã hết hạn"); lobby.setInvitePending(false); }
            case "INVITE_CANCELLED" -> { lobby.toast(m[1] + " đã hủy lời thách đấu"); lobby.setInvitePending(false); }
            case "INVITE" -> {
                // Hộp thoại KHÔNG chặn: nếu chặn, các tin khác của server (đối thủ thoát,
                // bắt đầu ván mới) sẽ phải xếp hàng chờ. Kèm timeout tự từ chối sau 30s.
                String from = m[1];
                NeonDialog dlg = new NeonDialog(f, "Lời thách đấu", from + " thách đấu bạn một ván Bom!", "CHẤP NHẬN", "TỪ CHỐI", false);
                Timer to = new Timer(30_000, e -> { dlg.close(); net.send("REJECT|" + from); });
                to.setRepeats(false); to.start();
                dlg.askAsync(ok -> {
                    to.stop();                                   // đã chọn -> bỏ timeout
                    net.send((ok ? "ACCEPT|" : "REJECT|") + from);
                });
            }
            case "MATCH_START" -> { oppName = m[1]; inGame = true; lobby.setInvitePending(false); game.start(me, oppName); show("game"); }
            case "ROUND" -> game.onRound(num(m, 1), num(m, 2), num(m, 3), num(m, 4));
            case "RESULT" -> game.onResult(num(m, 1), num(m, 2), num(m, 3), num(m, 4));
            case "GAME_OVER" -> {
                game.onGameOver(m[1], num(m, 2), m[3], dbl(m, 4), dbl(m, 5));
                Timer t = new Timer(4500, e -> askRematch());
                t.setRepeats(false); t.start();
            }
            case "OPP_LEFT" -> { if (inGame) { NeonDialog.info(f, "Đối thủ đã thoát", m[1] + " đã thoát — trận đấu bị hủy."); toLobby(); } }
            case "REMATCH_END" -> { if (inGame) { NeonDialog.info(f, "Ván đấu kết thúc", m[1] + " không chơi tiếp. Ván đấu kết thúc."); toLobby(); } }
            case "DISCONNECTED" -> {
                net.close(); inGame = false; show("login"); login.error("Mất kết nối tới server");
            }
            default -> { }
        }
    }

    /** Đọc số nguyên an toàn từ message; giá trị thiếu/sai định dạng -> 0. */
    private static int num(String[] m, int i) {
        try { return (i < m.length) ? Integer.parseInt(m[i].trim()) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    /** Đọc số thực an toàn từ message; giá trị thiếu/sai định dạng -> 0. */
    private static double dbl(String[] m, int i) {
        try { return (i < m.length) ? Double.parseDouble(m[i].trim()) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private void askRematch() {
        if (!inGame) return;
        rematchDlg = new NeonDialog(f, "Ván mới", "Chơi ván mới với " + oppName + "?", "CHƠI TIẾP", "THOÁT");
        boolean yes = rematchDlg.ask();
        rematchDlg = null;
        if (!inGame) return;
        net.send("REMATCH|" + (yes ? "1" : "0"));
        if (yes) game.setStatus("Đang chờ đối thủ đồng ý…"); else toLobby();
    }
}
