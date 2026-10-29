package com.bombgame.client.ui;

import com.bombgame.client.NetClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

public class LobbyPanel extends JPanel {
    private final NetClient net;
    private String me = "";
    private final DefaultTableModel pm = model("Người chơi", "Điểm", "Thắng", "Trạng thái");
    private final DefaultTableModel bm = model("Hạng", "Người chơi", "Điểm", "Thắng", "Trận");
    private final JTable pt = new Theme.NeonTable(pm, "Chưa có ai online"), bt = new Theme.NeonTable(bm, "Chưa có dữ liệu xếp hạng");
    private final NeonButton cancel = new NeonButton("HỦY LỜI MỜI", Theme.GOLD);
    private final NeonButton go = new NeonButton("THÁCH ĐẤU", Theme.PINK);
    private final Pill online = new Pill(Theme.GREEN), total = new Pill(Theme.GOLD);
    private final Chip chip = new Chip();
    private final Toast toast = new Toast();

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    }

    // ---------- thành phần nhỏ tự vẽ ----------
    /** Viên thuốc nhỏ hiển thị số liệu (vd. "5 online"). */
    private static final class Pill extends JComponent {
        private final Color c;
        private String text = "";

        Pill(Color c) { this.c = c; setOpaque(false); }

        void setText(String s) { text = s; revalidate(); repaint(); }

        @Override public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(Theme.font(Font.BOLD, 12)).stringWidth(text) + 26, 26);
        }

        @Override protected void paintComponent(Graphics g0) {
            if (text.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            RoundRectangle2D rr = new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 26, 26);
            g.setColor(Fx.rgba(c, .14)); g.fill(rr);
            g.setColor(Fx.rgba(c, .65)); g.setStroke(new BasicStroke(1.2f)); g.draw(rr);
            g.setFont(Theme.font(Font.BOLD, 12)); g.setColor(c);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 13, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** Chip người dùng ở góc trên phải: avatar + lời chào. */
    private static final class Chip extends JComponent {
        private String name = "";

        Chip() { setOpaque(false); }

        void setUser(String n) { name = n; revalidate(); repaint(); }

        @Override public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(Theme.font(Font.BOLD, 15)).stringWidth(name) + 98, 50);
        }

        @Override protected void paintComponent(Graphics g0) {
            if (name.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            int w = getWidth(), h = getHeight();
            Color c = Fx.avatarColor(name);
            RoundRectangle2D rr = new RoundRectangle2D.Double(1, 1, w - 2, h - 2, h, h);
            Fx.drawSoftRect(g, 1, 1, w - 2, h - 2, h, 8, c, .25);
            g.setColor(new Color(14, 16, 46, 220)); g.fill(rr);
            g.setColor(Fx.rgba(c, .7)); g.setStroke(new BasicStroke(1.3f)); g.draw(rr);
            Fx.drawAvatar(g, name, h / 2.0, h / 2.0, h / 2.0 - 6);
            g.setFont(Theme.font(Font.PLAIN, 11)); g.setColor(Theme.MUTED);
            g.drawString("XIN CHÀO", h, h / 2f - 3);
            g.setFont(Theme.font(Font.BOLD, 15)); g.setColor(Theme.TEXT);
            g.drawString(name, h, h / 2f + 14);
            g.dispose();
        }
    }

    /** Thanh thông báo nhỏ ở đáy, chỉ hiện khung khi có nội dung. */
    private static final class Toast extends JComponent {
        private String text = "";

        Toast() { setOpaque(false); setPreferredSize(new Dimension(10, 40)); }

        void set(String s) { text = s == null ? "" : s.trim(); repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            if (text.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            g.setFont(Theme.font(Font.BOLD, 14));
            int tw = g.getFontMetrics().stringWidth(text), w = Math.min(getWidth() - 2, tw + 56), h = 34;
            double x = 1, y = (getHeight() - h) / 2.0;
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, w, h, h, h);
            Fx.drawSoftRect(g, x, y, w, h, h, 8, Theme.GOLD, .18);
            g.setColor(new Color(20, 18, 46, 225)); g.fill(rr);
            g.setColor(Fx.rgba(Theme.GOLD, .6)); g.setStroke(new BasicStroke(1.2f)); g.draw(rr);
            double pulse = .6 + .4 * Math.sin(Anim.time() * 4);
            Fx.drawGlow(g, Theme.GOLD, x + 18, y + h / 2.0, 10, pulse);
            g.setColor(Theme.GOLD); g.fill(new Ellipse2D.Double(x + 14.5, y + h / 2.0 - 3.5, 7, 7));
            g.setColor(Theme.TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (float) x + 32, (float) (y + (h + fm.getAscent() - fm.getDescent()) / 2.0));
            g.dispose();
        }
    }

    public LobbyPanel(NetClient net) {
        this.net = net;
        setOpaque(false);
        setLayout(new BorderLayout(0, 14));
        setBorder(new EmptyBorder(22, 34, 14, 34));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        GradientLabel title = new GradientLabel("SẢNH CHỜ", 36f, Theme.CYAN, Theme.PINK, false);
        title.setAlignmentX(0);
        title.setBorder(new EmptyBorder(0, 14, 0, 18));
        JLabel sub = new JLabel("Chọn một đối thủ rồi thách đấu — né quả bom bí mật!");
        sub.setFont(Theme.font(Font.PLAIN, 13)); sub.setForeground(Theme.MUTED); sub.setAlignmentX(0); sub.setBorder(new EmptyBorder(0, 14, 0, 0));
        titles.add(title); titles.add(sub);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(titles, BorderLayout.WEST);
        JPanel chipBox = new JPanel(new GridBagLayout());
        chipBox.setOpaque(false); chipBox.add(chip);
        north.add(chipBox, BorderLayout.EAST);
        add(north, BorderLayout.NORTH);

        JPanel mid = new JPanel(new GridLayout(1, 2, 22, 0));
        mid.setOpaque(false);
        GlassPanel left = new GlassPanel(new BorderLayout(0, 8), Theme.GREEN, Theme.CYAN),
                right = new GlassPanel(new BorderLayout(0, 8), Theme.GOLD, Theme.PINK);
        left.setBorder(new EmptyBorder(18, 18, 14, 18));
        right.setBorder(new EmptyBorder(18, 18, 18, 18));
        left.add(head("NGƯỜI CHƠI ONLINE", Theme.GREEN, online), BorderLayout.NORTH);
        left.add(Theme.table(pt, false));
        NeonButton go = this.go;
        go.setPreferredSize(new Dimension(100, 54));
        cancel.setPreferredSize(new Dimension(100, 54));
        cancel.setEnabled(false);
        JLabel hint = new JLabel("Bấm đúp vào một người chơi để thách đấu nhanh", SwingConstants.CENTER);
        hint.setFont(Theme.font(Font.PLAIN, 12)); hint.setForeground(Theme.MUTED);
        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.setOpaque(false);
        JPanel goRow = new JPanel(new GridLayout(1, 2, 8, 0));
        goRow.setOpaque(false); goRow.add(go); goRow.add(cancel);
        south.add(goRow); south.add(hint, BorderLayout.SOUTH);
        left.add(south, BorderLayout.SOUTH);
        right.add(head("BẢNG XẾP HẠNG", Theme.GOLD, total), BorderLayout.NORTH);
        right.add(Theme.table(bt, true));
        mid.add(left); mid.add(right);
        add(mid);
        add(toast, BorderLayout.SOUTH);

        pt.setToolTipText("Chọn người chơi rồi bấm THÁCH ĐẤU; bấm đúp để thách đấu nhanh");
        bt.setToolTipText("Bảng xếp hạng người chơi");
        go.addActionListener(e -> challenge());
        cancel.addActionListener(e -> { net.send("CANCEL_INVITE"); setInvitePending(false); toast("Đã hủy lời thách đấu"); });
        pt.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) challenge(); }
        });
    }

    private JComponent head(String s, Color c, JComponent right) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(Font.BOLD, 15));
        l.setForeground(c);
        l.setIconTextGap(10);
        l.setIcon(new Icon() {                          // chấm sáng nhỏ trước tiêu đề
            @Override public void paintIcon(Component cmp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                Fx.drawGlow(g, c, x + 6, y + 6, 11, .55 + .3 * Math.sin(Anim.time() * 3));
                g.setColor(c);
                g.fill(new Ellipse2D.Double(x + 2.5, y + 2.5, 7, 7));
                g.dispose();
            }

            @Override public int getIconWidth() { return 12; }

            @Override public int getIconHeight() { return 12; }
        });
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 4, 4, 4));
        p.add(l, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void challenge() {
        int r = pt.getSelectedRow();
        if (r < 0) { toast("Hãy chọn một đối thủ trong danh sách"); return; }
        String name = ((String) pm.getValueAt(r, 0)).replace(" (bạn)", "");
        if (name.equals(me)) { toast("Bạn không thể tự thách đấu chính mình!"); return; }
        if (!String.valueOf(pm.getValueAt(r, 3)).contains("Rảnh")) { toast(name + " đang bận"); return; }
        net.send("CHALLENGE|" + name);
        setInvitePending(true);
        toast("Đã gửi lời thách đấu tới " + name + "… đang chờ phản hồi");
    }

    /** Bật/tắt nút HỦY LỜI MỜI (đang chờ đối thủ phản hồi hay không). */
    public void setInvitePending(boolean pending) {
        cancel.setEnabled(pending);
        go.setEnabled(!pending);
    }

    public void setMe(String n) {
        me = n;
        pt.putClientProperty("me", n);
        bt.putClientProperty("me", n);
        chip.setUser(n);
        toast.set("");
        setInvitePending(false);
    }

    public void toast(String s) { toast.set(s); }

    public void setPlayers(String data) {
        int sel = pt.getSelectedRow();
        String keep = sel >= 0 ? String.valueOf(pm.getValueAt(sel, 0)) : null;
        pm.setRowCount(0);
        int free = 0;
        if (!data.isEmpty()) for (String row : data.split(";")) {
            String[] f = row.split(",");
            boolean isFree = f[3].equals("FREE");
            if (isFree) free++;
            pm.addRow(new Object[]{f[0].equals(me) ? f[0] + " (bạn)" : f[0], f[1], f[2], isFree ? "● Rảnh" : "● Bận"});
        }
        online.setText(pm.getRowCount() + " online · " + free + " rảnh");
        if (keep != null) for (int i = 0; i < pm.getRowCount(); i++)
            if (keep.equals(pm.getValueAt(i, 0))) pt.setRowSelectionInterval(i, i);
    }

    public void setBoard(String data) {
        bm.setRowCount(0);
        if (data.isEmpty()) { total.setText(""); return; }
        int rank = 1;
        for (String row : data.split(";")) {
            String[] f = row.split(",");
            bm.addRow(new Object[]{"#" + rank++, f[0], f[1], f[2], f[3]});
        }
        total.setText("Top " + bm.getRowCount());
    }
}
