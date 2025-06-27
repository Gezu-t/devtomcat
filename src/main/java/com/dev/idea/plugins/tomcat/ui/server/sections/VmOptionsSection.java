package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * VM Options Section - Single Responsibility
 * Handles ONLY VM options functionality
 */
public class VmOptionsSection implements ConfigurationSection {

    private JTextArea vmOptionsArea;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder("VM options"));

            vmOptionsArea = new JTextArea(4, 50);
            vmOptionsArea.setLineWrap(true);
            vmOptionsArea.setWrapStyleWord(true);
            vmOptionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            vmOptionsArea.setToolTipText("Enter JVM arguments (e.g., -Xmx512m -Xms256m -XX:+UseG1GC)");

            JScrollPane scrollPane = new JScrollPane(vmOptionsArea);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            scrollPane.setPreferredSize(new Dimension(0, 80));
            scrollPane.setBorder(JBUI.Borders.empty(5));

            panel.add(scrollPane, BorderLayout.CENTER);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        // No initial loading needed for VM options
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        String vmOptions = configuration.getVmOptions();
        vmOptionsArea.setText(vmOptions != null ? vmOptions : "");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String vmOptions = vmOptionsArea.getText().trim();
        configuration.setVmOptions(vmOptions.isEmpty() ? null : vmOptions);
    }

    @Override
    public boolean isValid() {
        return true; // VM options are always valid (can be empty)
    }

    @Override
    public boolean shouldFillVertically() {
        return true; // VM options area should expand
    }

    public String getVmOptions() {
        return vmOptionsArea.getText().trim();
    }
}