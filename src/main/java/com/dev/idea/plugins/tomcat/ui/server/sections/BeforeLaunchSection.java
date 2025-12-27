package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Before Launch Section
 * Collapsible section with task list and buttons
 */
public class BeforeLaunchSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(BeforeLaunchSection.class);

    private DefaultListModel<String> beforeLaunchModel;
    private JList<String> beforeLaunchList;
    private JButton addButton;
    private JButton removeButton;
    private JButton moveUpButton;
    private JButton moveDownButton;
    private JPanel panel;
    private boolean isExpanded = true;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new BorderLayout());

            JPanel headerPanel = createHeaderPanel();
            panel.add(headerPanel, BorderLayout.NORTH);

            JPanel contentPanel = createContentPanel();
            contentPanel.setVisible(isExpanded);
            panel.add(contentPanel, BorderLayout.CENTER);
        }
        return panel;
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));

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
        contentPanel.setBorder(JBUI.Borders.empty(5, 20, 5, 5));

        beforeLaunchModel = new DefaultListModel<>();
        beforeLaunchModel.addElement("Build");

        beforeLaunchList = new JList<>(beforeLaunchModel);
        beforeLaunchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        beforeLaunchList.setPreferredSize(new Dimension(0, 60));

        JBScrollPane scrollPane = new JBScrollPane(beforeLaunchList);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel buttonPanel = createButtonPanel();

        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.EAST);

        return contentPanel;
    }

    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(JBUI.Borders.empty(0, 5, 0, 0));

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

        Component[] components = ((JPanel) panel.getComponent(0)).getComponents();
        if (components[0] instanceof JButton) {
            JButton expandButton = (JButton) components[0];
            expandButton.setIcon(isExpanded ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
        }

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
        if (beforeLaunchModel.isEmpty()) {
            beforeLaunchModel.addElement("Build");
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        beforeLaunchModel.clear();
        beforeLaunchModel.addElement("Build");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        LOG.info("Before launch tasks count: " + beforeLaunchModel.getSize());
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        // Before launch tasks are not currently stored in configuration
        // so we always return false (no changes to persist)
        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        // Before launch section has no validation requirements
        return Collections.emptyList();
    }

    @Override
    public boolean shouldFillVertically() {
        return isExpanded;
    }

    public List<String> getTasks() {
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < beforeLaunchModel.getSize(); i++) {
            tasks.add(beforeLaunchModel.getElementAt(i));
        }
        return tasks;
    }
}
