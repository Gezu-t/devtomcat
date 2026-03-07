package com.dev.idea.plugins.tomcat.ui.history;

import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Dialog that displays startup time trends across all tracked Tomcat configurations.
 * Shows a mini bar chart per configuration and key statistics (average, fastest, last, run count).
 */
public class StartupTimeTrendDialog extends DialogWrapper {

    private final StartupTimeTracker tracker;

    public StartupTimeTrendDialog(@Nullable com.intellij.openapi.project.Project project) {
        super(project, false);
        this.tracker = ApplicationManager.getApplication().getService(StartupTimeTracker.class);
        setTitle("DevTomcat — Startup Time Trends");
        setSize(700, 450);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        Map<String, List<Long>> allTimes = tracker.getState() != null
                ? tracker.getState().startupTimes
                : Map.of();

        if (allTimes.isEmpty()) {
            JBLabel emptyLabel = new JBLabel("No startup data recorded yet. Run a Tomcat configuration to begin tracking.",
                    AllIcons.General.Information, SwingConstants.CENTER);
            return emptyLabel;
        }

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(8));

        for (Map.Entry<String, List<Long>> entry : allTimes.entrySet()) {
            String configName = entry.getKey();
            List<Long> times = entry.getValue();
            if (times == null || times.isEmpty()) continue;

            mainPanel.add(createConfigPanel(configName, times));
            mainPanel.add(Box.createVerticalStrut(JBUI.scale(12)));
        }

        JBScrollPane scrollPane = new JBScrollPane(mainPanel);
        scrollPane.setPreferredSize(JBUI.size(680, 420));
        scrollPane.setBorder(JBUI.Borders.empty());
        return scrollPane;
    }

    @NotNull
    private JPanel createConfigPanel(@NotNull String configName, @NotNull List<Long> times) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(10, 12)));

        // Header: config name + stats
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JBLabel nameLabel = new JBLabel(configName, AllIcons.RunConfigurations.Application, SwingConstants.LEFT);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        header.add(nameLabel, BorderLayout.WEST);

        long avg = (long) times.stream().mapToLong(Long::longValue).average().orElse(0);
        long fastest = times.stream().mapToLong(Long::longValue).min().orElse(0);
        long last = times.get(times.size() - 1);

        String statsText = String.format("Last: %s  |  Avg: %s  |  Best: %s  |  Runs: %d",
                formatMs(last), formatMs(avg), formatMs(fastest), times.size());
        JBLabel statsLabel = new JBLabel(statsText);
        statsLabel.setForeground(UIUtil.getContextHelpForeground());
        header.add(statsLabel, BorderLayout.EAST);

        panel.add(header, BorderLayout.NORTH);

        // Trend comparison
        if (times.size() >= 2) {
            long prev = times.get(times.size() - 2);
            long diff = last - prev;
            String trend;
            Color color;
            if (diff < 0) {
                trend = "\u2193 " + formatMs(Math.abs(diff)) + " faster than previous";
                color = JBColor.namedColor("DevTomcat.deployedForeground",
                        new JBColor(0x008000, 0x6AAB73));
            } else if (diff > 0) {
                trend = "\u2191 " + formatMs(diff) + " slower than previous";
                color = JBColor.RED;
            } else {
                trend = "Same as previous run";
                color = UIUtil.getContextHelpForeground();
            }
            JBLabel trendLabel = new JBLabel(trend);
            trendLabel.setForeground(color);
            trendLabel.setBorder(JBUI.Borders.emptyTop(2));
            panel.add(trendLabel, BorderLayout.SOUTH);
        }

        // Bar chart
        panel.add(new BarChartPanel(times), BorderLayout.CENTER);

        return panel;
    }

    @NotNull
    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    // ======== Mini Bar Chart ========

    private static final class BarChartPanel extends JPanel {
        private final List<Long> times;

        BarChartPanel(@NotNull List<Long> times) {
            this.times = times;
            setPreferredSize(new Dimension(0, JBUI.scale(60)));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (times.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int barCount = times.size();
            long maxTime = times.stream().mapToLong(Long::longValue).max().orElse(1);

            int gap = JBUI.scale(2);
            int totalGaps = (barCount - 1) * gap;
            int barWidth = Math.max(JBUI.scale(4), (w - totalGaps) / barCount);

            // Limit bars to fit
            int maxBars = (w + gap) / (barWidth + gap);
            int startIndex = Math.max(0, barCount - maxBars);

            Color barColor = JBColor.namedColor("DevTomcat.chartBar",
                    new JBColor(new Color(0x4A86C8), new Color(0x589DF6)));
            Color lastBarColor = JBColor.namedColor("DevTomcat.chartBarLast",
                    new JBColor(new Color(0x2E7D32), new Color(0x6AAB73)));

            int x = 0;
            for (int i = startIndex; i < barCount; i++) {
                long time = times.get(i);
                int barHeight = (int) ((double) time / maxTime * (h - JBUI.scale(4)));
                barHeight = Math.max(JBUI.scale(2), barHeight);

                g2.setColor(i == barCount - 1 ? lastBarColor : barColor);
                g2.fillRoundRect(x, h - barHeight, barWidth, barHeight,
                        JBUI.scale(2), JBUI.scale(2));

                x += barWidth + gap;
                if (x + barWidth > w) break;
            }

            g2.dispose();
        }
    }
}
