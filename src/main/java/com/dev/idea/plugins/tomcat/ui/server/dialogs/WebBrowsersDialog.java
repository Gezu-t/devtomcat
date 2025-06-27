/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Web Browsers and Preview configuration dialog - Complete implementation
 */

package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Professional Web Browsers and Preview configuration dialog
 * Manages browser configurations for web application preview
 */
public class WebBrowsersDialog extends DialogWrapper {

    private final Project project;

    // Browser table components
    private JBTable browserTable;
    private DefaultTableModel browserTableModel;
    private JButton addBrowserButton;
    private JButton removeBrowserButton;
    private JButton editBrowserButton;
    private JButton moveUpButton;
    private JButton moveDownButton;

    // Default browser settings
    private JComboBox<String> defaultBrowserComboBox;

    // Preview settings
    private JCheckBox showBrowserPopupForHtmlCheckBox;
    private JCheckBox showBrowserPopupForXmlCheckBox;
    private JRadioButton reloadOnSaveRadio;
    private JRadioButton reloadOnFrameDeactivationRadio;
    private JRadioButton reloadManuallyRadio;
    private ButtonGroup reloadButtonGroup;

    // Instance data storage
    private List<BrowserInfo> browsers;
    private String defaultBrowser;

    // Static global storage for cross-component access
    private static List<BrowserInfo> globalBrowsers = new ArrayList<>();
    private static String globalDefaultBrowser = "System default";
    private static boolean initialized = false;

    public WebBrowsersDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        this.browsers = new ArrayList<>();
        initializeDefaultBrowsers();
        setTitle("Web Browsers and Preview");
        setSize(700, 500);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(700, 500));

        // Create main content panel
        JPanel contentPanel = new JPanel(new BorderLayout());

        // Browser table panel
        contentPanel.add(createBrowserTablePanel(), BorderLayout.CENTER);

        // Settings panel
        contentPanel.add(createSettingsPanel(), BorderLayout.SOUTH);

        mainPanel.add(contentPanel, BorderLayout.CENTER);

        return mainPanel;
    }

    /**
     * Create browser table panel with management buttons
     */
    private JPanel createBrowserTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Browsers"));

        // Create browser table
        String[] columnNames = {"Active", "Name", "Family", "Path"};
        browserTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // Only Active column is editable
            }
        };

        browserTable = new JBTable(browserTableModel);
        browserTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        browserTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });

        // Set column widths
        browserTable.getColumnModel().getColumn(0).setPreferredWidth(60);  // Active
        browserTable.getColumnModel().getColumn(1).setPreferredWidth(120); // Name
        browserTable.getColumnModel().getColumn(2).setPreferredWidth(80);  // Family
        browserTable.getColumnModel().getColumn(3).setPreferredWidth(300); // Path

        // Custom renderer for Family column to show icons
        browserTable.getColumnModel().getColumn(2).setCellRenderer(new BrowserFamilyRenderer());

        JScrollPane tableScrollPane = new JScrollPane(browserTable);
        tableScrollPane.setPreferredSize(new Dimension(650, 200));
        panel.add(tableScrollPane, BorderLayout.CENTER);

        // Management buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        addBrowserButton = new JButton("Add");
        addBrowserButton.setToolTipText("Add new browser");
        addBrowserButton.addActionListener(e -> addBrowser());

        removeBrowserButton = new JButton("Remove");
        removeBrowserButton.setToolTipText("Remove selected browser");
        removeBrowserButton.addActionListener(e -> removeBrowser());

        editBrowserButton = new JButton("Edit");
        editBrowserButton.setToolTipText("Edit selected browser");
        editBrowserButton.addActionListener(e -> editBrowser());

        moveUpButton = new JButton("Move Up");
        moveUpButton.setToolTipText("Move selected browser up");
        moveUpButton.addActionListener(e -> moveBrowserUp());

        moveDownButton = new JButton("Move Down");
        moveDownButton.setToolTipText("Move selected browser down");
        moveDownButton.addActionListener(e -> moveBrowserDown());

        buttonPanel.add(addBrowserButton);
        buttonPanel.add(removeBrowserButton);
        buttonPanel.add(editBrowserButton);
        buttonPanel.add(moveUpButton);
        buttonPanel.add(moveDownButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        // Update initial button states
        updateButtonStates();
        updateBrowserTable();

        return panel;
    }

    /**
     * Create settings panel for default browser and preview options
     */
    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(10));

        // Default browser section
        JPanel defaultBrowserPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        defaultBrowserPanel.setBorder(BorderFactory.createTitledBorder("Default Browser"));

        defaultBrowserPanel.add(new JLabel("Default browser:"));
        defaultBrowserComboBox = new JComboBox<>();
        defaultBrowserComboBox.setPreferredSize(new Dimension(200, 25));
        updateDefaultBrowserComboBox();
        defaultBrowserPanel.add(defaultBrowserComboBox);

        panel.add(defaultBrowserPanel);

        // Preview settings section
        JPanel previewPanel = new JPanel();
        previewPanel.setLayout(new BoxLayout(previewPanel, BoxLayout.Y_AXIS));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Preview Settings"));

        // Show browser popup options
        JPanel popupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        showBrowserPopupForHtmlCheckBox = new JCheckBox("Show browser popup in the editor for HTML files", false);
        showBrowserPopupForXmlCheckBox = new JCheckBox("Show browser popup in the editor for XML files", false);

        popupPanel.add(showBrowserPopupForHtmlCheckBox);
        popupPanel.add(Box.createHorizontalStrut(20));
        popupPanel.add(showBrowserPopupForXmlCheckBox);
        previewPanel.add(popupPanel);

        // Reload behavior section
        JPanel reloadPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        reloadPanel.setBorder(BorderFactory.createTitledBorder("Reload Behavior"));

        reloadButtonGroup = new ButtonGroup();
        reloadOnSaveRadio = new JRadioButton("Reload on save", true);
        reloadOnFrameDeactivationRadio = new JRadioButton("Reload on frame deactivation", false);
        reloadManuallyRadio = new JRadioButton("Reload manually", false);

        reloadButtonGroup.add(reloadOnSaveRadio);
        reloadButtonGroup.add(reloadOnFrameDeactivationRadio);
        reloadButtonGroup.add(reloadManuallyRadio);

        reloadPanel.add(reloadOnSaveRadio);
        reloadPanel.add(Box.createHorizontalStrut(10));
        reloadPanel.add(reloadOnFrameDeactivationRadio);
        reloadPanel.add(Box.createHorizontalStrut(10));
        reloadPanel.add(reloadManuallyRadio);

        previewPanel.add(reloadPanel);
        panel.add(previewPanel);

        return panel;
    }

    /**
     * Initialize default browser configurations
     */
    private void initializeDefaultBrowsers() {
        // Detect and add common browsers
        detectAndAddBrowser("Chrome", "Chrome", findChromeExecutable());
        detectAndAddBrowser("Firefox", "Firefox", findFirefoxExecutable());
        detectAndAddBrowser("Edge", "Edge", findEdgeExecutable());
        detectAndAddBrowser("Safari", "Safari", findSafariExecutable());
        detectAndAddBrowser("Opera", "Opera", findOperaExecutable());
        detectAndAddBrowser("Internet Explorer", "Internet Explorer", findIEExecutable());

        // Set default browser
        defaultBrowser = "System default";
    }

    /**
     * Static method to initialize global browsers if needed
     */
    private static void initializeGlobalBrowsersIfNeeded() {
        if (!initialized) {
            globalBrowsers.clear();

            // Auto-detect browsers and add to global list
            detectAndAddGlobalBrowser("Chrome", "Chrome", findChromeExecutableStatic());
            detectAndAddGlobalBrowser("Firefox", "Firefox", findFirefoxExecutableStatic());
            detectAndAddGlobalBrowser("Edge", "Edge", findEdgeExecutableStatic());
            detectAndAddGlobalBrowser("Safari", "Safari", findSafariExecutableStatic());
            detectAndAddGlobalBrowser("Opera", "Opera", findOperaExecutableStatic());
            detectAndAddGlobalBrowser("Internet Explorer", "Internet Explorer", findIEExecutableStatic());

            initialized = true;
        }
    }

    /**
     * Static method to get configured browsers (called by ServerConfigurationTab)
     */
    public static List<BrowserInfo> getBrowserConfigurations() {
        initializeGlobalBrowsersIfNeeded();
        return new ArrayList<>(globalBrowsers);
    }

    /**
     * Static method to get global default browser
     */
    public static String getGlobalDefaultBrowser() {
        return globalDefaultBrowser;
    }

    /**
     * Detect and add browser if found
     */
    private void detectAndAddBrowser(String name, String family, String path) {
        if (path != null && new File(path).exists()) {
            browsers.add(new BrowserInfo(name, family, path, true));
        } else {
            // Add as inactive if not found
            browsers.add(new BrowserInfo(name, family, path != null ? path : "Not found", false));
        }
    }

    /**
     * Static helper method to detect and add global browsers
     */
    private static void detectAndAddGlobalBrowser(String name, String family, String path) {
        if (path != null && new File(path).exists()) {
            globalBrowsers.add(new BrowserInfo(name, family, path, true));
        } else {
            // Add as inactive if not found
            globalBrowsers.add(new BrowserInfo(name, family, path != null ? path : "Not found", false));
        }
    }

    /**
     * Find Chrome executable path
     */
    private String findChromeExecutable() {
        return findChromeExecutableStatic();
    }

    private static String findChromeExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] paths = {
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
            };
            for (String path : paths) {
                if (new File(path).exists()) return path;
            }
        } else if (os.contains("mac")) {
            return "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
        } else if (os.contains("nix") || os.contains("nux")) {
            return "/usr/bin/google-chrome";
        }
        return null;
    }

    /**
     * Find Firefox executable path
     */
    private String findFirefoxExecutable() {
        return findFirefoxExecutableStatic();
    }

    private static String findFirefoxExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] paths = {
                    "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
                    "C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe"
            };
            for (String path : paths) {
                if (new File(path).exists()) return path;
            }
        } else if (os.contains("mac")) {
            return "/Applications/Firefox.app/Contents/MacOS/firefox";
        } else if (os.contains("nix") || os.contains("nux")) {
            return "/usr/bin/firefox";
        }
        return null;
    }

    /**
     * Find Edge executable path
     */
    private String findEdgeExecutable() {
        return findEdgeExecutableStatic();
    }

    private static String findEdgeExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] paths = {
                    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
            };
            for (String path : paths) {
                if (new File(path).exists()) return path;
            }
        } else if (os.contains("mac")) {
            return "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
        }
        return null;
    }

    /**
     * Find Safari executable path
     */
    private String findSafariExecutable() {
        return findSafariExecutableStatic();
    }

    private static String findSafariExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return "/Applications/Safari.app/Contents/MacOS/Safari";
        }
        return null;
    }

    /**
     * Find Opera executable path
     */
    private String findOperaExecutable() {
        return findOperaExecutableStatic();
    }

    private static String findOperaExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] paths = {
                    "C:\\Program Files\\Opera\\opera.exe",
                    "C:\\Program Files (x86)\\Opera\\opera.exe"
            };
            for (String path : paths) {
                if (new File(path).exists()) return path;
            }
        } else if (os.contains("mac")) {
            return "/Applications/Opera.app/Contents/MacOS/Opera";
        } else if (os.contains("nix") || os.contains("nux")) {
            return "/usr/bin/opera";
        }
        return null;
    }

    /**
     * Find Internet Explorer executable path
     */
    private String findIEExecutable() {
        return findIEExecutableStatic();
    }

    private static String findIEExecutableStatic() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] paths = {
                    "C:\\Program Files\\Internet Explorer\\iexplore.exe",
                    "C:\\Program Files (x86)\\Internet Explorer\\iexplore.exe"
            };
            for (String path : paths) {
                if (new File(path).exists()) return path;
            }
        }
        return null;
    }

    /**
     * Update browser table with current browser list
     */
    private void updateBrowserTable() {
        browserTableModel.setRowCount(0);
        for (BrowserInfo browser : browsers) {
            browserTableModel.addRow(new Object[]{
                    browser.isActive(),
                    browser.getName(),
                    browser.getFamily(),
                    browser.getPath()
            });
        }
        updateDefaultBrowserComboBox();
    }

    /**
     * Update default browser combo box
     */
    private void updateDefaultBrowserComboBox() {
        defaultBrowserComboBox.removeAllItems();
        defaultBrowserComboBox.addItem("System default");

        for (BrowserInfo browser : browsers) {
            if (browser.isActive()) {
                defaultBrowserComboBox.addItem(browser.getName());
            }
        }

        // Set current default
        if (defaultBrowser != null) {
            defaultBrowserComboBox.setSelectedItem(defaultBrowser);
        }
    }

    /**
     * Update button states based on selection
     */
    private void updateButtonStates() {
        int selectedRow = browserTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeBrowserButton.setEnabled(hasSelection);
        editBrowserButton.setEnabled(hasSelection);
        moveUpButton.setEnabled(hasSelection && selectedRow > 0);
        moveDownButton.setEnabled(hasSelection && selectedRow < browsers.size() - 1);
    }

    /**
     * Add new browser
     */
    private void addBrowser() {
        BrowserConfigDialog dialog = new BrowserConfigDialog(project, null);
        if (dialog.showAndGet()) {
            BrowserInfo newBrowser = dialog.getBrowserInfo();
            browsers.add(newBrowser);
            updateBrowserTable();

            // Select the new browser
            int lastRow = browsers.size() - 1;
            browserTable.setRowSelectionInterval(lastRow, lastRow);
        }
    }

    /**
     * Remove selected browser
     */
    private void removeBrowser() {
        int selectedRow = browserTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < browsers.size()) {
            BrowserInfo browser = browsers.get(selectedRow);

            int result = Messages.showYesNoDialog(
                    getContentPane(),
                    "Remove browser '" + browser.getName() + "'?",
                    "Remove Browser",
                    Messages.getQuestionIcon()
            );

            if (result == Messages.YES) {
                browsers.remove(selectedRow);
                updateBrowserTable();
            }
        }
    }

    /**
     * Edit selected browser
     */
    private void editBrowser() {
        int selectedRow = browserTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < browsers.size()) {
            BrowserInfo browser = browsers.get(selectedRow);
            BrowserConfigDialog dialog = new BrowserConfigDialog(project, browser);
            if (dialog.showAndGet()) {
                BrowserInfo updatedBrowser = dialog.getBrowserInfo();
                browsers.set(selectedRow, updatedBrowser);
                updateBrowserTable();
            }
        }
    }

    /**
     * Move selected browser up
     */
    private void moveBrowserUp() {
        int selectedRow = browserTable.getSelectedRow();
        if (selectedRow > 0) {
            BrowserInfo browser = browsers.remove(selectedRow);
            browsers.add(selectedRow - 1, browser);
            updateBrowserTable();
            browserTable.setRowSelectionInterval(selectedRow - 1, selectedRow - 1);
        }
    }

    /**
     * Move selected browser down
     */
    private void moveBrowserDown() {
        int selectedRow = browserTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < browsers.size() - 1) {
            BrowserInfo browser = browsers.remove(selectedRow);
            browsers.add(selectedRow + 1, browser);
            updateBrowserTable();
            browserTable.setRowSelectionInterval(selectedRow + 1, selectedRow + 1);
        }
    }


    /**
     * Get default browser (instance method)
     */
    public String getDefaultBrowser() {
        return (String) defaultBrowserComboBox.getSelectedItem();
    }

    /**
     * Get preview settings
     */
    public PreviewSettings getPreviewSettings() {
        String reloadBehavior = "on_save";
        if (reloadOnFrameDeactivationRadio.isSelected()) {
            reloadBehavior = "on_frame_deactivation";
        } else if (reloadManuallyRadio.isSelected()) {
            reloadBehavior = "manually";
        }

        return new PreviewSettings(
                showBrowserPopupForHtmlCheckBox.isSelected(),
                showBrowserPopupForXmlCheckBox.isSelected(),
                reloadBehavior
        );
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        // Save current dialog configuration to global state
        globalBrowsers.clear();
        globalBrowsers.addAll(browsers);
        globalDefaultBrowser = (String) defaultBrowserComboBox.getSelectedItem();

        super.doOKAction();
    }

    /**
     * Browser information class
     */
    public static class BrowserInfo {
        private String name;
        private String family;
        private String path;
        private boolean active;

        public BrowserInfo(String name, String family, String path, boolean active) {
            this.name = name;
            this.family = family;
            this.path = path;
            this.active = active;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getFamily() { return family; }
        public void setFamily(String family) { this.family = family; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        @Override
        public String toString() {
            return name + " (" + family + ")";
        }
    }

    /**
     * Preview settings class
     */
    public static class PreviewSettings {
        private final boolean showHtmlPopup;
        private final boolean showXmlPopup;
        private final String reloadBehavior;

        public PreviewSettings(boolean showHtmlPopup, boolean showXmlPopup, String reloadBehavior) {
            this.showHtmlPopup = showHtmlPopup;
            this.showXmlPopup = showXmlPopup;
            this.reloadBehavior = reloadBehavior;
        }

        public boolean isShowHtmlPopup() { return showHtmlPopup; }
        public boolean isShowXmlPopup() { return showXmlPopup; }
        public String getReloadBehavior() { return reloadBehavior; }
    }

    /**
     * Custom renderer for browser family column
     */
    private static class BrowserFamilyRenderer implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = new JLabel(value != null ? value.toString() : "");

            if (isSelected) {
                label.setBackground(table.getSelectionBackground());
                label.setForeground(table.getSelectionForeground());
                label.setOpaque(true);
            } else {
                label.setBackground(table.getBackground());
                label.setForeground(table.getForeground());
                label.setOpaque(false);
            }

            // Set icon based on browser family
            String family = value != null ? value.toString() : "";
            switch (family.toLowerCase()) {
                case "chrome":
                    label.setText("🌐 " + family);
                    break;
                case "firefox":
                    label.setText("🦊 " + family);
                    break;
                case "edge":
                    label.setText("📘 " + family);
                    break;
                case "safari":
                    label.setText("🧭 " + family);
                    break;
                case "opera":
                    label.setText("🎭 " + family);
                    break;
                case "internet explorer":
                    label.setText("🔵 " + family);
                    break;
                default:
                    label.setText("🌍 " + family);
                    break;
            }

            return label;
        }
    }

    /**
     * Individual browser configuration dialog
     */
    private static class BrowserConfigDialog extends DialogWrapper {
        private final Project project;
        private JTextField nameField;
        private JComboBox<String> familyComboBox;
        private JTextField pathField;
        private JButton browseButton;
        private JCheckBox activeCheckBox;
        private BrowserInfo browserInfo;

        public BrowserConfigDialog(Project project, BrowserInfo existingBrowser) {
            super(project);
            this.project = project;
            this.browserInfo = existingBrowser;
            setTitle(existingBrowser == null ? "Add Browser" : "Edit Browser");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);

            // Name field
            gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JTextField(20);
            panel.add(nameField, gbc);

            // Family field
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Family:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            familyComboBox = new JComboBox<>(new String[]{
                    "Chrome", "Firefox", "Edge", "Safari", "Opera", "Internet Explorer", "Custom"
            });
            familyComboBox.setEditable(true);
            panel.add(familyComboBox, gbc);

            // Path field
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Path:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.8;
            JPanel pathPanel = new JPanel(new BorderLayout());
            pathField = new JTextField(30);
            browseButton = new JButton("Browse");
            browseButton.addActionListener(this::browseForExecutable);
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            panel.add(pathPanel, gbc);

            // Active checkbox
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
            activeCheckBox = new JCheckBox("Active", true);
            panel.add(activeCheckBox, gbc);

            // Populate fields if editing
            if (browserInfo != null) {
                nameField.setText(browserInfo.getName());
                familyComboBox.setSelectedItem(browserInfo.getFamily());
                pathField.setText(browserInfo.getPath());
                activeCheckBox.setSelected(browserInfo.isActive());
            }

            return panel;
        }

        private void browseForExecutable(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select Browser Executable");

            // Set file filter based on OS
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
                    @Override
                    public boolean accept(File f) {
                        return f.isDirectory() || f.getName().toLowerCase().endsWith(".exe");
                    }
                    @Override
                    public String getDescription() {
                        return "Executable Files (*.exe)";
                    }
                });
            }

            if (fileChooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }
        }

        public BrowserInfo getBrowserInfo() {
            if (browserInfo == null) {
                browserInfo = new BrowserInfo(
                        nameField.getText().trim(),
                        (String) familyComboBox.getSelectedItem(),
                        pathField.getText().trim(),
                        activeCheckBox.isSelected()
                );
            } else {
                browserInfo.setName(nameField.getText().trim());
                browserInfo.setFamily((String) familyComboBox.getSelectedItem());
                browserInfo.setPath(pathField.getText().trim());
                browserInfo.setActive(activeCheckBox.isSelected());
            }
            return browserInfo;
        }
    }
}