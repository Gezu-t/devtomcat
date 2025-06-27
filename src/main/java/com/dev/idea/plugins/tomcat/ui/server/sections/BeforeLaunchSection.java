package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Before Launch Section
 *
 * A collapsible "Before launch" section with "Build" task and +/-/↑/↓ buttons
 */
public class BeforeLaunchSection implements ConfigurationSection {

    private DefaultListModel<String> beforeLaunchModel;
    private JList<String> beforeLaunchList;
    private JButton addButton;
    private JButton removeButton;
    private JButton moveUpButton;
    private JButton moveDownButton;
    private JPanel panel;
    private boolean isExpanded = false;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new BorderLayout());

            // Create collapsible header
            JPanel headerPanel = createHeaderPanel();
            panel.add(headerPanel, BorderLayout.NORTH);

            // Create content panel (initially hidden)
            JPanel contentPanel = createContentPanel();
            contentPanel.setVisible(isExpanded);
            panel.add(contentPanel, BorderLayout.CENTER);
        }
        return panel;
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));

        // Collapsible arrow button
        JButton expandButton = new JButton();
        expandButton.setIcon(isExpanded ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
        expandButton.setBorderPainted(false);
        expandButton.setContentAreaFilled(false);
        expandButton.setFocusPainted(false);
        expandButton.setPreferredSize(new Dimension(16, 16));
        expandButton.addActionListener(e -> toggleExpanded());

        JLabel titleLabel = new JLabel("Before launch");

        headerPanel.add(expandButton);
        headerPanel.add(titleLabel);

        return headerPanel;
    }

    private JPanel createContentPanel() {
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(JBUI.Borders.empty(5, 20, 5, 5)); // Indent content

        // Task list
        beforeLaunchModel = new DefaultListModel<>();
        beforeLaunchModel.addElement("Build"); // Default task as shown in Ultimate

        beforeLaunchList = new JList<>(beforeLaunchModel);
        beforeLaunchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        beforeLaunchList.setPreferredSize(new Dimension(0, 60));

        JScrollPane scrollPane = new JScrollPane(beforeLaunchList);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Button panel
        JPanel buttonPanel = createButtonPanel();

        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.EAST);

        return contentPanel;
    }

    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(JBUI.Borders.empty(0, 5, 0, 0));

        // Create buttons with icons
        addButton = new JButton("+");
        addButton.setPreferredSize(new Dimension(25, 25));
        addButton.setToolTipText("Add");
        addButton.addActionListener(e -> addTask());

        removeButton = new JButton("-");
        removeButton.setPreferredSize(new Dimension(25, 25));
        removeButton.setToolTipText("Remove");
        removeButton.addActionListener(e -> removeTask());

        moveUpButton = new JButton("↑");
        moveUpButton.setPreferredSize(new Dimension(25, 25));
        moveUpButton.setToolTipText("Move Up");
        moveUpButton.addActionListener(e -> moveTaskUp());

        moveDownButton = new JButton("↓");
        moveDownButton.setPreferredSize(new Dimension(25, 25));
        moveDownButton.setToolTipText("Move Down");
        moveDownButton.addActionListener(e -> moveTaskDown());

        buttonPanel.add(addButton);
        buttonPanel.add(Box.createVerticalStrut(2));
        buttonPanel.add(removeButton);
        buttonPanel.add(Box.createVerticalStrut(2));
        buttonPanel.add(moveUpButton);
        buttonPanel.add(Box.createVerticalStrut(2));
        buttonPanel.add(moveDownButton);
        buttonPanel.add(Box.createVerticalGlue());

        return buttonPanel;
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;

        // Update arrow icon
        Component[] components = ((JPanel) panel.getComponent(0)).getComponents();
        if (components[0] instanceof JButton) {
            JButton expandButton = (JButton) components[0];
            expandButton.setIcon(isExpanded ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
        }

        // Show/hide content
        panel.getComponent(1).setVisible(isExpanded);
        panel.revalidate();
        panel.repaint();
    }

    private void addTask() {
        String taskName = JOptionPane.showInputDialog(panel, "Enter task name:", "Add Task", JOptionPane.PLAIN_MESSAGE);
        if (taskName != null && !taskName.trim().isEmpty()) {
            beforeLaunchModel.addElement(taskName.trim());
        }
    }

    private void removeTask() {
        int selectedIndex = beforeLaunchList.getSelectedIndex();
        if (selectedIndex != -1) {
            beforeLaunchModel.removeElementAt(selectedIndex);
        }
    }

    private void moveTaskUp() {
        int selectedIndex = beforeLaunchList.getSelectedIndex();
        if (selectedIndex > 0) {
            String task = beforeLaunchModel.getElementAt(selectedIndex);
            beforeLaunchModel.removeElementAt(selectedIndex);
            beforeLaunchModel.insertElementAt(task, selectedIndex - 1);
            beforeLaunchList.setSelectedIndex(selectedIndex - 1);
        }
    }

    private void moveTaskDown() {
        int selectedIndex = beforeLaunchList.getSelectedIndex();
        if (selectedIndex != -1 && selectedIndex < beforeLaunchModel.getSize() - 1) {
            String task = beforeLaunchModel.getElementAt(selectedIndex);
            beforeLaunchModel.removeElementAt(selectedIndex);
            beforeLaunchModel.insertElementAt(task, selectedIndex + 1);
            beforeLaunchList.setSelectedIndex(selectedIndex + 1);
        }
    }

    @Override
    public void loadConfiguration() {
        // Ensure "Build" task is present as default
        if (beforeLaunchModel.isEmpty()) {
            beforeLaunchModel.addElement("Build");
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Reset to default "Build" task
        beforeLaunchModel.clear();
        beforeLaunchModel.addElement("Build");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // Before launch tasks are typically handled at the IDE level
        System.out.println("DevTomcat: Before launch tasks count: " + beforeLaunchModel.getSize());
    }

    @Override
    public boolean isValid() {
        return true; // Before launch tasks are always valid
    }

    @Override
    public boolean shouldFillVertically() {
        return isExpanded; // Only fill vertically when expanded
    }

    public java.util.List<String> getTasks() {
        java.util.List<String> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < beforeLaunchModel.getSize(); i++) {
            tasks.add(beforeLaunchModel.getElementAt(i));
        }
        return tasks;
    }
}