package com.dev.idea.plugins.tomcat.setting;

import com.dev.idea.plugins.tomcat.utils.TomcatServerUtils;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.MasterDetailsComponent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CommonActionsPanel;
import com.intellij.util.IconUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dev Tomcat Servers Configurable
 *
 * Main configuration panel for managing Tomcat servers in the IDE settings.
 * Provides a tree view of all configured servers with add/remove/edit capabilities.
 *
 * This class integrates with IntelliJ's settings dialog to provide a professional
 * server management interface.
 *
 * @author Dev Tomcat Team
 */
public class TomcatServersConfigurable extends MasterDetailsComponent {

    private static final Logger LOG = Logger.getInstance(TomcatServersConfigurable.class);

    private static final String DISPLAY_NAME = "Dev Tomcat Servers";
    private static final String HELP_TOPIC = "dev.tomcat.servers";

    /**
     * Create a new servers configurable
     */
    public TomcatServersConfigurable() {
        initTree();
        LOG.info("Initialized Dev Tomcat servers configuration panel");
    }

    @Override
    @NotNull
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    @Nullable
    public String getHelpTopic() {
        return HELP_TOPIC;
    }

    /**
     * Create actions for the toolbar
     *
     * @param fromPopup Whether creating for popup menu
     * @return List of actions
     */
    @Override
    @Nullable
    protected List<AnAction> createActions(boolean fromPopup) {
        List<AnAction> actions = new ArrayList<>();
        actions.add(new AddTomcatAction());
        actions.add(new MyDeleteAction());
        actions.add(new DuplicateServerAction());

        LOG.debug("Created " + actions.size() + " server management actions");
        return actions;
    }

    /**
     * Check if the configuration has been modified
     *
     * @return true if modified
     */
    @Override
    public boolean isModified() {
        // Check base modifications
        if (super.isModified()) {
            return true;
        }

        // Check if server count has changed
        int configuredCount = TomcatServerManagerState.getInstance().getTomcatInfos().size();
        int displayedCount = myRoot.getChildCount();

        if (configuredCount != displayedCount) {
            LOG.debug("Server count changed: " + configuredCount + " → " + displayedCount);
            return true;
        }

        return false;
    }

    /**
     * Reset the configuration to saved state
     */
    @Override
    public void reset() {
        myRoot.removeAllChildren();

        // Load all configured servers
        TomcatServerManagerState state = TomcatServerManagerState.getInstance();
        List<TomcatInfo> servers = state.getTomcatInfos();

        for (TomcatInfo info : servers) {
            addServerNode(info, false);
        }

        super.reset();
        LOG.info("Reset configuration with " + servers.size() + " servers");
    }

    /**
     * Apply the configuration changes
     *
     * @throws ConfigurationException if validation fails
     */
    @Override
    public void apply() throws ConfigurationException {
        // Validate all servers before applying
        validateAllServers();

        super.apply();

        // Update the persistent state
        List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();
        tomcatInfos.clear();

        for (int i = 0; i < myRoot.getChildCount(); i++) {
            MyNode node = (MyNode) myRoot.getChildAt(i);
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) node.getConfigurable();
            TomcatInfo info = configurable.getEditableObject();
            tomcatInfos.add(info);
        }

        LOG.info("Applied configuration with " + tomcatInfos.size() + " servers");
    }

    /**
     * Validate all server configurations
     *
     * @throws ConfigurationException if any server is invalid
     */
    private void validateAllServers() throws ConfigurationException {
        for (int i = 0; i < myRoot.getChildCount(); i++) {
            MyNode node = (MyNode) myRoot.getChildAt(i);
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) node.getConfigurable();
            TomcatInfo info = configurable.getEditableObject();

            try {
                info.validate();
            } catch (IllegalStateException e) {
                throw new ConfigurationException(
                        "Invalid server configuration for '" + info.getName() + "': " + e.getMessage()
                );
            }
        }
    }

    /**
     * Check if an object was stored in the configuration
     *
     * @param editableObject The object to check
     * @return true if stored
     */
    @Override
    protected boolean wasObjectStored(Object editableObject) {
        if (!(editableObject instanceof TomcatInfo)) {
            return false;
        }

        TomcatInfo tomcatInfo = (TomcatInfo) editableObject;
        List<TomcatInfo> storedServers = TomcatServerManagerState.getInstance().getTomcatInfos();

        // Check by ID for accurate comparison
        boolean isStored = storedServers.stream()
                .anyMatch(stored -> stored.getId().equals(tomcatInfo.getId()));

        LOG.debug("Server '" + tomcatInfo.getName() + "' stored: " + isStored);
        return isStored;
    }

    /**
     * Add a new server node to the tree
     *
     * @param tomcatInfo The server to add
     * @param selectInTree Whether to select the node
     */
    private void addServerNode(@NotNull TomcatInfo tomcatInfo, boolean selectInTree) {
        try {
            TomcatInfoConfigurable configurable = new TomcatInfoConfigurable(
                    tomcatInfo,
                    TREE_UPDATER,
                    this::validateServerName
            );

            MyNode node = new MyNode(configurable);
            addNode(node, myRoot);

            if (selectInTree) {
                selectNodeInTree(node);
            }

            LOG.info("Added server node: " + tomcatInfo.getDisplayString());

        } catch (Exception e) {
            LOG.error("Failed to add server node", e);
            Messages.showErrorDialog(
                    "Failed to add Tomcat server: " + e.getMessage(),
                    "Error Adding Server"
            );
        }
    }

    /**
     * Validate server name uniqueness
     *
     * @param name The name to validate
     * @throws ConfigurationException if invalid
     */
    private void validateServerName(@NotNull String name) throws ConfigurationException {
        // Check for empty name
        if (name.trim().isEmpty()) {
            throw new ConfigurationException("Server name cannot be empty");
        }

        // Check for duplicate names
        String trimmedName = name.trim();
        List<String> existingNames = new ArrayList<>();

        for (int i = 0; i < myRoot.getChildCount(); i++) {
            MyNode node = (MyNode) myRoot.getChildAt(i);
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) node.getConfigurable();
            String existingName = configurable.getEditableObject().getName();

            if (!existingName.equals(configurable.getDisplayName())) {
                // Skip if this is the node being edited
                continue;
            }

            existingNames.add(existingName);

            if (existingName.equals(trimmedName)) {
                throw new ConfigurationException("Server name already exists: \"" + trimmedName + "\"");
            }
        }

        LOG.debug("Validated server name '" + trimmedName + "' (checked " + existingNames.size() + " existing names)");
    }

    /**
     * Action to add a new Tomcat server
     */
    private class AddTomcatAction extends DumbAwareAction {

        AddTomcatAction() {
            super("Add Tomcat Server", "Add a new Tomcat server configuration", IconUtil.getAddIcon());
            registerCustomShortcutSet(CommonActionsPanel.getCommonShortcut(CommonActionsPanel.Buttons.ADD), myTree);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LOG.debug("Add Tomcat server action triggered");

            try {
                TomcatServerUtils.selectTomcatInstallation(
                        this::createUniqueName,
                        tomcatInfo -> {
                            addServerNode(tomcatInfo, true);
                            LOG.info("Successfully added Tomcat server: " + tomcatInfo.getDisplayString());
                        }
                );
            } catch (Exception ex) {
                LOG.error("Error adding Tomcat server", ex);
                Messages.showErrorDialog(
                        "Failed to add Tomcat server: " + ex.getMessage(),
                        "Error"
                );
            }
        }

        /**
         * Create a unique name for the new server
         */
        @NotNull
        private String createUniqueName(@NotNull String preferredName) {
            List<String> existingNames = getServerNames();
            String uniqueName = TomcatServerUtils.generateUniqueName(existingNames, preferredName);

            LOG.debug("Generated unique name: '" + uniqueName + "' (from: '" + preferredName + "')");
            return uniqueName;
        }
    }

    /**
     * Action to duplicate a server configuration
     */
    private class DuplicateServerAction extends DumbAwareAction {

        DuplicateServerAction() {
            super("Duplicate", "Duplicate the selected server configuration", IconUtil.getAddIcon());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            MyNode selectedNode = getSelectedNode();
            if (selectedNode == null) {
                return;
            }

            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) selectedNode.getConfigurable();
            TomcatInfo original = configurable.getEditableObject();

            // Create a copy
            TomcatInfo copy = original.clone();
            copy.setId(java.util.UUID.randomUUID().toString());
            copy.setName(createUniqueName(original.getName() + " Copy"));

            addServerNode(copy, true);

            LOG.info("Duplicated server: " + original.getName() + " → " + copy.getName());
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(getSelectedNode() != null);
        }

        private String createUniqueName(String baseName) {
            List<String> existingNames = getServerNames();
            return TomcatServerUtils.generateUniqueName(existingNames, baseName);
        }
    }

    // === UTILITY METHODS ===

    /**
     * Get count of configured servers
     *
     * @return The server count
     */
    public int getServerCount() {
        return myRoot.getChildCount();
    }

    /**
     * Get all configured server names
     *
     * @return List of server names
     */
    @NotNull
    public List<String> getServerNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < myRoot.getChildCount(); i++) {
            MyNode node = (MyNode) myRoot.getChildAt(i);
            names.add(node.getDisplayName());
        }
        return names;
    }

    /**
     * Get all configured servers
     *
     * @return List of TomcatInfo objects
     */
    @NotNull
    public List<TomcatInfo> getServers() {
        List<TomcatInfo> servers = new ArrayList<>();
        for (int i = 0; i < myRoot.getChildCount(); i++) {
            MyNode node = (MyNode) myRoot.getChildAt(i);
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) node.getConfigurable();
            servers.add(configurable.getEditableObject());
        }
        return servers;
    }

    /**
     * Check if any servers are configured
     *
     * @return true if at least one server is configured
     */
    public boolean hasConfiguredServers() {
        return getServerCount() > 0;
    }

    /**
     * Find a server by name
     *
     * @param name The server name
     * @return The server info or null
     */
    @Nullable
    public TomcatInfo findServerByName(@NotNull String name) {
        return getServers().stream()
                .filter(info -> name.equals(info.getName()))
                .findFirst()
                .orElse(null);
    }
}