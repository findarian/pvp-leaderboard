package com.pvp.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class AdditionalStatsPanel extends JPanel {
    private final JLabel highestRankLabel = new JLabel("-");
    private final JLabel highestTimeLabel = new JLabel("-");
    private final JLabel lowestRankLabel = new JLabel("-");
    private final JLabel lowestTimeLabel = new JLabel("-");
    private final JButton overallBtn = new JButton("Overall");
    private final JButton nhBtn = new JButton("NH");
    private final JButton vengBtn = new JButton("Veng");
    private final JButton multiBtn = new JButton("Multi");
    private final JButton dmmBtn = new JButton("DMM");
    private final TierGraphPlotPanel graphPlot = new TierGraphPlotPanel();
    private final TierGraphLabelsPanel graphLabels = new TierGraphLabelsPanel();

    private final SimpleDateFormat fmt = new SimpleDateFormat("M/d/yyyy, h:mm:ss a");
    private String selectedBucket = "overall";
    private java.util.List<Double> tierSeries = new ArrayList<>();
    private JsonArray matches = new JsonArray();

    public AdditionalStatsPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Additional Stats"));

        JPanel content = new JPanel(new GridBagLayout());
        GridBagConstraints root = new GridBagConstraints();
        root.gridx = 0; root.gridy = 0; root.anchor = GridBagConstraints.WEST; root.fill = GridBagConstraints.HORIZONTAL; root.weightx = 1.0;

        JPanel statsRow = new JPanel(new GridLayout(2, 2, 24, 2));
        statsRow.add(makeStat("Highest Rank Defeated", highestRankLabel, highestTimeLabel));
        statsRow.add(makeStat("Lowest Rank Lost To",   lowestRankLabel,  lowestTimeLabel));
        content.add(statsRow, root);
        root.gridy++;

        // no extra controls here; simplified per request

        // Ensure all chips are visible in narrow sidebar by splitting into multiple short rows
        // and forcing the rows to occupy full available width anchored left.
        JPanel chips = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 1.0;
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        java.util.List<JButton> allChips = Arrays.asList(overallBtn, nhBtn, vengBtn, multiBtn, dmmBtn);
        for (int i = 0; i < allChips.size(); i++) {
            JButton b = allChips.get(i);
            b.setFocusPainted(false);
            b.setMargin(new Insets(2,10,2,10));
            Dimension chip = new Dimension(64, 24);
            b.setPreferredSize(chip);
            b.setMinimumSize(chip);
            b.setMaximumSize(chip);
            Dimension pd = b.getPreferredSize();
            b.setMinimumSize(pd);
            if (i < 2) { row1.add(b); }
            else if (i < 4) { row2.add(b); }
            else { row3.add(b); }
        }
        row1.setAlignmentX(LEFT_ALIGNMENT);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        row3.setAlignmentX(LEFT_ALIGNMENT);
        // Add rows to a filling GridBag to eliminate centering and ensure full-width rows
        chips.add(row1, gbc);
        gbc.gridy = 1; chips.add(row2, gbc);
        gbc.gridy = 2; chips.add(row3, gbc);
        chips.setAlignmentX(LEFT_ALIGNMENT);
        chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        overallBtn.addActionListener(e -> setBucket("overall"));
        nhBtn.addActionListener(e -> setBucket("nh"));
        vengBtn.addActionListener(e -> setBucket("veng"));
        multiBtn.addActionListener(e -> setBucket("multi"));
        dmmBtn.addActionListener(e -> setBucket("dmm"));

        // Place chips block in the root content grid to keep it hard-left
        content.add(chips, root);
        root.gridy++;

        graphPlot.setPreferredSize(new Dimension(800, 260));
        JScrollPane graphScroll = new JScrollPane(graphPlot);
        graphScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        graphScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        graphScroll.setBorder(null);
        graphScroll.setPreferredSize(new Dimension(0, 260));
        JPanel graphRow = new JPanel(new BorderLayout());
        graphLabels.setPreferredSize(new Dimension(80, 260));
        graphRow.add(graphLabels, BorderLayout.WEST);
        graphRow.add(graphScroll, BorderLayout.CENTER);
        content.add(graphRow, root);
        root.gridy++;

        // Add content to BorderLayout.NORTH so everything is anchored to top-left of panel
        add(content, BorderLayout.NORTH);

        updateChipStyles();
    }

    public void setMatches(JsonArray newMatches) {
        this.matches = (newMatches != null) ? newMatches : new JsonArray();
        updateAdditionalStats();
        rebuildSeries();
        updateGraphSize();
    }

    public void setBucket(String bucket) {
        this.selectedBucket = (bucket == null ? "overall" : bucket.toLowerCase(Locale.ROOT));
        updateChipStyles();
        rebuildSeries();
        updateGraphSize();
    }

    private JPanel makeStat(String title, JLabel value, JLabel time) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 16f));
        value.setFont(value.getFont().deriveFont(Font.BOLD, 18f));
        time.setForeground(new Color(230, 200, 80));
        p.add(t);
        p.add(Box.createVerticalStrut(4));
        p.add(value);
        p.add(time);
        return p;
    }

    private void updateChipStyles() {
        Map<String, JButton> map = new HashMap<>();
        map.put("overall", overallBtn);
        map.put("nh", nhBtn);
        map.put("veng", vengBtn);
        map.put("multi", multiBtn);
        map.put("dmm", dmmBtn);
        for (Map.Entry<String, JButton> e : map.entrySet()) {
            boolean active = e.getKey().equals(selectedBucket);
            e.getValue().setEnabled(!active);
            e.getValue().setBackground(active ? Color.DARK_GRAY : UIManager.getColor("Panel.background"));
            e.getValue().setForeground(active ? Color.YELLOW : UIManager.getColor("Label.foreground"));
        }
    }

    private void updateAdditionalStats() {
        String bestRank = null; Long bestTs = null;
        String worstRank = null; Long worstTs = null;

        for (int i = 0; i < matches.size(); i++) {
            JsonObject m = matches.get(i).getAsJsonObject();
            String bucket = asStr(m, "bucket").toLowerCase(Locale.ROOT);
            if (!selectedBucket.equals("overall") && !bucket.equals(selectedBucket)) continue;

            String result = asStr(m, "result").toLowerCase(Locale.ROOT);
            long ts = m.has("when") ? (long) Math.floor(m.get("when").getAsDouble()) : 0;

            String oppRank = rankLabel(asStr(m, "opponent_rank"), asInt(m, "opponent_division"));
            if (oppRank.isEmpty()) continue;

            if ("win".equals(result)) {
                if (bestRank == null || rankOrder(oppRank) > rankOrder(bestRank)) {
                    bestRank = oppRank; bestTs = ts;
                }
            } else if ("loss".equals(result)) {
                if (worstRank == null || rankOrder(oppRank) < rankOrder(worstRank)) {
                    worstRank = oppRank; worstTs = ts;
                }
            }
        }

        highestRankLabel.setText(bestRank != null ? bestRank : "-");
        highestTimeLabel.setText(bestTs != null ? fmt.format(new Date(bestTs * 1000)) : "-");
        lowestRankLabel.setText(worstRank != null ? worstRank : "-");
        lowestTimeLabel.setText(worstTs != null ? fmt.format(new Date(worstTs * 1000)) : "-");
    }

    private void rebuildSeries() {
        java.util.List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) items.add(matches.get(i).getAsJsonObject());
        // Sort ascending by time like the site (oldest → newest)
        items = items.stream()
                .filter(m -> selectedBucket.equals("overall") || selectedBucket.equalsIgnoreCase(asStr(m, "bucket")))
                .sorted(Comparator.comparingDouble(m -> m.has("when") ? m.get("when").getAsDouble() : 0))
                .limit(1000)
                .collect(Collectors.toList());

        final String want = selectedBucket;
        final double maxY = 8 * 3.0; // 9 ranks → indices 0..8; 3 divisions each → 0..24
        final int MAX_MMR_DELTA_PER_MATCH = 250;

        java.util.List<Double> series = new ArrayList<>();
        if ("overall".equals(want)) {
            // Weighted reconstruction across buckets
            final Map<String, Double> weights = new HashMap<>();
            weights.put("nh", 0.55);
            weights.put("veng", 0.30);
            weights.put("multi", 0.05);
            weights.put("dmm", 0.10);
            final Map<String, Double> last = new HashMap<>();
            last.put("nh", 1000.0);
            last.put("veng", 1000.0);
            last.put("multi", 1000.0);
            last.put("dmm", 1000.0);

            for (JsonObject m : items) {
                String b = asStr(m, "bucket").toLowerCase(Locale.ROOT);
                if (weights.containsKey(b)) {
                    Double prev = last.get(b);
                    double mu = resolveMuFromMatch(m, prev);
                    if (Double.isFinite(mu)) {
                        if (!Double.isFinite(prev) || Math.abs(mu - prev) <= MAX_MMR_DELTA_PER_MATCH) {
                            last.put(b, mu);
                        }
                    }
                }
                double overallMu = 0.0;
                for (Map.Entry<String, Double> e : weights.entrySet()) {
                    double mu = last.getOrDefault(e.getKey(), 1000.0);
                    overallMu += mu * e.getValue();
                }
                double y = encodeRankValue(overallMu);
                double norm = Math.max(0.0, Math.min(100.0, (y / maxY) * 100.0));
                series.add(norm);
            }
        } else {
            Double prevMu = null;
            for (JsonObject m : items) {
                double mu = resolveMuFromMatch(m, prevMu);
                double chosen;
                if (Double.isFinite(mu)) {
                    if (prevMu != null && Double.isFinite(prevMu) && Math.abs(mu - prevMu) > MAX_MMR_DELTA_PER_MATCH) {
                        chosen = prevMu; // outlier guard
                    } else {
                        chosen = mu;
                        prevMu = mu;
                    }
                } else {
                    chosen = (prevMu != null) ? prevMu : 1000.0;
                }
                double y = encodeRankValue(chosen);
                double norm = Math.max(0.0, Math.min(100.0, (y / maxY) * 100.0));
                series.add(norm);
            }
        }
        tierSeries = series;
        graphPlot.setSeries(series);
        graphLabels.repaint();
    }

    // Choose the post-match MMR for a row when available; otherwise fall back.
    private static double resolveMuFromMatch(JsonObject m, Double prevMu) {
        try {
            // Prefer explicit post-match fields if present
            if (m.has("player_mmr_after") && !m.get("player_mmr_after").isJsonNull()) {
                return m.get("player_mmr_after").getAsDouble();
            }
            if (m.has("player_new_mmr") && !m.get("player_new_mmr").isJsonNull()) {
                return m.get("player_new_mmr").getAsDouble();
            }
            if (m.has("player_mmr") && !m.get("player_mmr").isJsonNull()) {
                return m.get("player_mmr").getAsDouble();
            }
            // Reconstruct from delta if possible
            if (m.has("rating_change") && m.get("rating_change").isJsonObject()) {
                JsonObject rc = m.getAsJsonObject("rating_change");
                if (rc.has("mmr_delta") && !rc.get("mmr_delta").isJsonNull() && prevMu != null) {
                    double delta = rc.get("mmr_delta").getAsDouble();
                    return prevMu + delta;
                }
            }
        } catch (Exception ignore) {}
        return Double.NaN;
    }

    // Encode MMR to rank-scale value like the website (baseIndex*3 + divisionOffset + progress)
    private static double encodeRankValue(double mmr) {
        final String[] ORDER = {"Bronze","Iron","Steel","Black","Mithril","Adamant","Rune","Dragon","3rd Age"};
        double v = mmr;
        String[] curr = THRESHOLDS[0];
        for (String[] t : THRESHOLDS) { if (v >= Double.parseDouble(t[2])) curr = t; else break; }
        int idxCurr = indexOfThreshold(curr);
        String[] next = THRESHOLDS[Math.min(idxCurr + 1, THRESHOLDS.length - 1)];
        int baseIdx = 0;
        for (int i = 0; i < ORDER.length; i++) { if (ORDER[i].equals(curr[0])) { baseIdx = i; break; } }
        int div = "3rd Age".equals(curr[0]) ? 0 : Integer.parseInt(curr[1]);
        int divOffset = "3rd Age".equals(curr[0]) ? 0 : (3 - div);
        double tierBase = baseIdx * 3 + divOffset;
        if ("3rd Age".equals(curr[0])) return tierBase;
        double span = Math.max(1.0, Double.parseDouble(next[2]) - Double.parseDouble(curr[2]));
        double prog = Math.max(0.0, Math.min(1.0, (v - Double.parseDouble(curr[2])) / span));
        return tierBase + prog;
    }

    private void updateGraphSize() {
        int count = (tierSeries != null) ? tierSeries.size() : 0;
        int width = Math.max(800, count * 6); // ~6px per point, similar to site scaling
        graphPlot.setPreferredSize(new Dimension(width, 260));
        graphPlot.revalidate();
        graphPlot.repaint();
        graphLabels.setPreferredSize(new Dimension(80, 260));
        graphLabels.revalidate();
        graphLabels.repaint();
    }

    private static String asStr(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }
    private static int asInt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; } catch (Exception e) { return 0; }
    }
    private static double safeDouble(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0.0; } catch (Exception e) { return 0.0; }
    }

    private static String rankLabel(String rank, int division) {
        if (rank == null || rank.isEmpty()) return "";
        return division > 0 ? (rank + " " + division) : rank;
    }

    private static final java.util.List<String> RANKS = Arrays.asList(
            "Bronze","Iron","Steel","Black","Mithril","Adamant","Rune","Dragon","3rd Age"
    );
    private static int rankOrder(String label) {
        String[] parts = label.trim().split("\\s+");
        String base = parts[0];
        int div = (parts.length > 1) ? parseInt(parts[1], 0) : 0;
        int baseIdx = RANKS.indexOf(base);
        if (baseIdx < 0) return -1;
        int divOrder = (base.equals("3rd") || base.equals("3rd Age")) ? 0 : (4 - div);
        return baseIdx * 10 + (10 - divOrder);
    }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }

    private static final String[][] THRESHOLDS = {
            {"Bronze","3","0"}, {"Bronze","2","170"}, {"Bronze","1","240"},
            {"Iron","3","310"}, {"Iron","2","380"}, {"Iron","1","450"},
            {"Steel","3","520"}, {"Steel","2","590"}, {"Steel","1","660"},
            {"Black","3","730"}, {"Black","2","800"}, {"Black","1","870"},
            {"Mithril","3","940"}, {"Mithril","2","1010"}, {"Mithril","1","1080"},
            {"Adamant","3","1150"},{"Adamant","2","1250"},{"Adamant","1","1350"},
            {"Rune","3","1450"}, {"Rune","2","1550"}, {"Rune","1","1650"},
            {"Dragon","3","1750"},{"Dragon","2","1850"},{"Dragon","1","1950"},
            {"3rd Age","0","2100"}
    };
    private static double tierValueFromMMR(double mmr) {
        String[] curr = THRESHOLDS[0];
        for (String[] t : THRESHOLDS) { if (mmr >= Double.parseDouble(t[2])) curr = t; else break; }
        if ("3rd Age".equals(curr[0])) return 100.0;
        int idx = indexOfThreshold(curr);
        String[] next = (idx >= 0 && idx < THRESHOLDS.length - 1) ? THRESHOLDS[idx + 1] : curr;
        double lo = Double.parseDouble(curr[2]), hi = Double.parseDouble(next[2]);
        return Math.max(0, Math.min(100, (mmr - lo) / Math.max(1, (hi - lo)) * 100.0));
    }
    private static int indexOfThreshold(String[] t) {
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (Arrays.equals(THRESHOLDS[i], t)) return i;
        }
        return -1;
    }

    // Fixed-width labels + guides panel
    private static class TierGraphLabelsPanel extends JPanel {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int top = 20, bottom = 20; int innerH = Math.max(1, h - top - bottom);
            String[] order = {"Bronze","Iron","Steel","Black","Mithril","Adamant","Rune","Dragon","3rd Age"};
            g2.setColor(new Color(120,120,120));
            int steps = 24;
            for (int gi = 0; gi <= steps; gi++) {
                double frac = gi / (double) steps;
                int y = top + innerH - (int) Math.round(frac * innerH);
                g2.drawLine(0, y, w, y);
                int base = gi / 3; int off = gi % 3; String baseRank = order[base];
                String label = baseRank.equals("3rd Age") ? baseRank : baseRank + " " + (3 - off);
                g2.drawString(label, 2, y + 5);
            }
        }
    }

    // Scrollable plot panel that renders the series and full-width guides
    private static class TierGraphPlotPanel extends JPanel {
        private java.util.List<Double> series = new ArrayList<>();
        void setSeries(java.util.List<Double> s) { this.series = (s != null) ? s : new ArrayList<>(); repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int left = 0, bottom = 20, top = 20, right = 20;
            int innerW = Math.max(1, w - left - right);
            int innerH = Math.max(1, h - top - bottom);

            String[] order = {"Bronze","Iron","Steel","Black","Mithril","Adamant","Rune","Dragon","3rd Age"};
            g2.setColor(new Color(120,120,120));
            int steps = 24;
            for (int gi = 0; gi <= steps; gi++) {
                double frac = gi / (double) steps; // 0 bottom .. 1 top
                int y = top + innerH - (int) Math.round(frac * innerH);
                g2.drawLine(left, y, left + innerW, y);
            }

            if (series.size() > 1) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                for (int i = 0; i < series.size() - 1; i++) {
                    int x1 = left + (i * innerW / Math.max(1, series.size() - 1));
                    int x2 = left + ((i + 1) * innerW / Math.max(1, series.size() - 1));
                    int y1 = top + innerH - (int) Math.round((series.get(i) / 100.0) * innerH);
                    int y2 = top + innerH - (int) Math.round((series.get(i + 1) / 100.0) * innerH);
                    g2.drawLine(x1, y1, x2, y2);
                }
            } else {
                g2.setColor(Color.GRAY);
                g2.drawString("No tier data available", w / 2 - 60, h / 2);
            }
        }
    }

    // Layout that wraps components to new rows and reports correct preferred size for BoxLayout parents
    private static class WrapFlowLayout extends FlowLayout {
        public WrapFlowLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
        @Override public Dimension preferredLayoutSize(Container target) { return layoutSize(target, true); }
        @Override public Dimension minimumLayoutSize(Container target) { Dimension d = layoutSize(target, false); d.width -= (getHgap() + 1); return d; }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int maxWidth = target.getParent() == null ? target.getWidth() : target.getParent().getWidth();
                if (maxWidth <= 0) maxWidth = Integer.MAX_VALUE; // during first pass, no width yet

                Insets insets = target.getInsets();
                int hgap = getHgap();
                int vgap = getVgap();
                int maxOnLine = maxWidth - (insets.left + insets.right + hgap * 2);

                int x = 0;
                int y = insets.top + vgap;
                int rowHeight = 0;
                int requiredWidth = 0;

                for (Component m : target.getComponents()) {
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (x == 0 || x + d.width <= maxOnLine) {
                        if (x > 0) x += hgap;
                        x += d.width;
                        rowHeight = Math.max(rowHeight, d.height);
                    } else {
                        // new row
                        y += rowHeight + vgap;
                        requiredWidth = Math.max(requiredWidth, x);
                        x = d.width;
                        rowHeight = d.height;
                    }
                }

                y += rowHeight + insets.bottom + vgap;
                requiredWidth = Math.max(requiredWidth, x) + insets.left + insets.right + hgap * 2;
                return new Dimension(requiredWidth, y);
            }
        }
    }
}
