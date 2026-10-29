package com.bombgame.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/**
 * Hộp thoại neon thay cho JOptionPane (trắng, lệch tông). Modal, Enter = đồng ý, Esc = từ chối, kéo để di chuyển.
 *
 * Có 2 kiểu dùng:
 *  - {@link #ask()} — chặn (modal). Dùng cho thông báo người chơi chủ động bấm.
 *  - {@link #askAsync} — không chặn (modeless). Dùng cho lời mời: server có thể gửi
 *    tin khác (đối thủ thoát, bắt đầu ván mới) trong lúc hộp thoại đang mở.
 */
public final class NeonDialog {
    private final JDialog d;
    private final JComponent outer;
    private boolean result;
    /** Callback cho kiểu không chặn. */
    private java.util.function.Consumer<Boolean> asyncCb;

    /** @param no null nếu chỉ muốn một nút (thông báo). */
    public NeonDialog(Window owner, String title, String message, String yes, String no) {
        this(owner, title, message, yes, no, true);
    }

    /**
     * @param modal true = chặn (dùng {@link #ask()}), false = không chặn (dùng {@link #askAsync}).
     */
    public NeonDialog(Window owner, String title, String message, String yes, String no, boolean modal) {
        d = new JDialog(owner, modal ? Dialog.ModalityType.APPLICATION_MODAL : Dialog.ModalityType.MODELESS);
        d.setUndecorated(true);
        boolean translucent = true;
        try { d.setBackground(new Color(0, 0, 0, 0)); } catch (Exception e) { translucent = false; }

        JPanel o = new JPanel(new BorderLayout());
        outer = o;
        o.setBorder(new EmptyBorder(12, 12, 12, 12));
        o.setOpaque(!translucent);
        o.setBackground(new Color(10, 8, 30));

        GlassPanel card = new GlassPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(22, 34, 24, 34));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        c.insets = new Insets(0, 0, 6, 0);
        card.add(new GradientLabel(title.toUpperCase(), 21f, Theme.CYAN, Theme.PINK, true), c);

        JLabel msg = new JLabel("<html><div style='width:300px;text-align:center'>" + esc(message) + "</div></html>", SwingConstants.CENTER);
        msg.setFont(Theme.font(Font.PLAIN, 15));
        msg.setForeground(Theme.TEXT);
        c.insets = new Insets(4, 0, 20, 0);
        card.add(msg, c);

        NeonButton yesBtn = new NeonButton(yes, Theme.CYAN);
        yesBtn.setPreferredSize(new Dimension(150, 48));
        yesBtn.addActionListener(e -> finish(true));
        c.gridwidth = 1; c.weightx = .5; c.insets = new Insets(0, 0, 0, no == null ? 0 : 6);
        if (no == null) c.gridwidth = 2;
        card.add(yesBtn, c);
        if (no != null) {
            NeonButton noBtn = new NeonButton(no, Theme.PINK);
            noBtn.setPreferredSize(new Dimension(150, 48));
            noBtn.addActionListener(e -> finish(false));
            c.gridx = 1; c.insets = new Insets(0, 6, 0, 0);
            card.add(noBtn, c);
        }
        o.add(card);
        d.setContentPane(o);

        InputMap im = o.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "yes");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "no");
        o.getActionMap().put("yes", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { finish(true); } });
        o.getActionMap().put("no", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { finish(no == null); } });

        MouseAdapter drag = new MouseAdapter() {
            Point start;
            @Override public void mousePressed(MouseEvent e) { start = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                Point p = d.getLocation();
                d.setLocation(p.x + e.getX() - start.x, p.y + e.getY() - start.y);
            }
        };
        card.addMouseListener(drag);
        card.addMouseMotionListener(drag);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { Anim.removeTarget(outer); }
        });
        d.pack();
        d.setLocationRelativeTo(owner);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void finish(boolean r) {
        result = r;
        d.dispose();
        // Kiểu không chặn: gọi callback sau khi đóng.
        java.util.function.Consumer<Boolean> cb = asyncCb;
        if (cb != null) { asyncCb = null; cb.accept(r); }
    }

    /** Hiện hộp thoại (chặn cho tới khi người chơi chọn). Trả về true nếu bấm nút đồng ý. */
    public boolean ask() {
        Anim.addTarget(outer);
        d.setVisible(true);
        Anim.removeTarget(outer);
        d.dispose();
        return result;
    }

    /**
     * Hiện hộp thoại KHÔNG chặn: trả về ngay, gọi callback khi người chơi chọn.
     * Dùng cho lời mời để không chặn EDT (nếu chặn, các tin khác của server sẽ phải xếp hàng chờ).
     */
    public void askAsync(java.util.function.Consumer<Boolean> cb) {
        asyncCb = cb;
        Anim.addTarget(outer);
        d.setVisible(true);
        // Không dispose ở đây: hộp thoại tự đóng khi người chơi bấm nút.
    }

    /** Đóng từ bên ngoài (ví dụ đối thủ đã thoát khi đang hỏi ván mới) — ask() sẽ trả về false. */
    public void close() {
        result = false;
        d.dispose();
        java.util.function.Consumer<Boolean> cb = asyncCb;
        if (cb != null) { asyncCb = null; cb.accept(false); }
    }

    public static boolean confirm(Component parent, String title, String msg, String yes, String no) {
        return new NeonDialog(SwingUtilities.getWindowAncestor(parent), title, msg, yes, no).ask();
    }

    /** Bản không chặn của {@link #confirm}: callback được gọi khi người chơi chọn. */
    public static void confirmAsync(Component parent, String title, String msg, String yes, String no,
                                   java.util.function.Consumer<Boolean> cb) {
        new NeonDialog(SwingUtilities.getWindowAncestor(parent), title, msg, yes, no, false).askAsync(cb);
    }

    public static void info(Component parent, String title, String msg) {
        new NeonDialog(SwingUtilities.getWindowAncestor(parent), title, msg, "ĐÃ HIỂU", null).ask();
    }
}
