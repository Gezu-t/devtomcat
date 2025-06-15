package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.MasterDetailsComponent;
import com.intellij.ui.CommonActionsPanel;
import com.intellij.util.IconUtil;
import com.dev.idea.plugins.tomcat.utils.PluginUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * DevTomcat Server Configuration UI
 * Provides professional server management interface
 * Allows adding, editing, and removing Tomcat server instances
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatServersConfigurable extends MasterDetailsComponent {

    @Override
    public String getDisplayName() {
        return "Tomcat Server";
    }

    @Override
    public String getHelpTopic() {
        return "DevTomcat Help";
    }

    public TomcatServersConfigurable() {
        initTree();
        System.out.println("DevTomcat: TomcatServersConfigurable initialized");
    }

    @Override
    protected @Nullable List<AnAction> createActions(boolean fromPopup) {
        List<AnAction> actions = new ArrayList<>();
        actions.add(new AddTomcatAction());
        actions.add(new MyDeleteAction());
        System.out.println("DevTomcat: Created server management actions");
        return actions;
    }

    @Override
    public boolean isModified() {
        boolean modified = super.isModified();
        if (modified) {
            return true;
        }

        int configuredServers = TomcatServerManagerState.getInstance().getTomcatInfos().size();
        int displayedServers = myRoot.getChildCount();
        boolean hasChanges = displayedServers != configuredServers;

        if (hasChanges) {
            System.out.println("DevTomcat: Server configuration modified - " +
                    configuredServers + " configured, " + displayedServers + " displayed");
        }

        return hasChanges;
    }

    @Override
    public void reset() {
        myRoot.removeAllChildren();

        TomcatServerManagerState state = TomcatServerManagerState.getInstance();
        List<TomcatInfo> servers = state.getTomcatInfos();

        for (TomcatInfo info : servers) {
            addNode(info, false);
        }

        super.reset();
        System.out.println("DevTomcat: Reset server configuration - loaded " + servers.size() + " servers");
    }

    @Override
    public void apply() throws ConfigurationException {
        super.apply();

        List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();
        tomcatInfos.clear();

        for (int i = 0; i < myRoot.getChildCount(); i++) {
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) ((MyNode) myRoot.getChildAt(i)).getConfigurable();
            tomcatInfos.add(configurable.getEditableObject());
        }

        System.out.println("DevTomcat: Applied server configuration - saved " + tomcatInfos.size() + " servers");
    }

    @Override
    protected boolean wasObjectStored(Object editableObject) {
        List<TomcatInfo> storedServers = TomcatServerManagerState.getInstance().getTomcatInfos();
        boolean isStored = storedServers.contains(editableObject);

        if (editableObject instanceof TomcatInfo) {
            TomcatInfo tomcatInfo = (TomcatInfo) editableObject;
            System.out.println("DevTomcat: Checking if server '" + tomcatInfo.getName() + "' is stored: " + isStored);
        }

        return isStored;
    }

    /**
     * Add a new Tomcat server node to the tree
     */
    private void addNode(TomcatInfo tomcatInfo, boolean selectInTree) {
        try {
            TomcatInfoConfigurable configurable = new TomcatInfoConfigurable(tomcatInfo, TREE_UPDATER, this::validateName);
            MyNode node = new MyNode(configurable);
            addNode(node, myRoot);

            if (selectInTree) {
                selectNodeInTree(node);
            }

            System.out.println("DevTomcat: Added server node - " + tomcatInfo.getName() +
                    " (version: " + tomcatInfo.getVersion() + ")");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error adding server node: " + e.getMessage());
            throw new RuntimeException("Failed to add Tomcat server: " + tomcatInfo.getName(), e);
        }
    }

    /**
     * Validate server name uniqueness
     */
    private void validateName(String name) throws ConfigurationException {
        if (name == null || name.trim().isEmpty()) {
            throw new ConfigurationException("Server name cannot be empty");
        }

        String trimmedName = name.trim();
        List<String> existingNames = new ArrayList<>();

        for (int i = 0; i < myRoot.getChildCount(); i++) {
            TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) ((MyNode) myRoot.getChildAt(i)).getConfigurable();
            String existingName = configurable.getEditableObject().getName();
            existingNames.add(existingName);

            if (existingName.equals(trimmedName)) {
                throw new ConfigurationException("Duplicate name: \"" + trimmedName + "\"");
            }
        }

        System.out.println("DevTomcat: Validated server name '" + trimmedName + "' - unique among " + existingNames.size() + " servers");
    }

    /**
     * Action to add new Tomcat server
     */
    private class AddTomcatAction extends DumbAwareAction {
        public AddTomcatAction() {
            super("Add", "Add a Tomcat server", IconUtil.getAddIcon());
            registerCustomShortcutSet(CommonActionsPanel.getCommonShortcut(CommonActionsPanel.Buttons.ADD), myTree);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            System.out.println("DevTomcat: Add Tomcat server action triggered");

            try {
                PluginUtils.chooseTomcat(this::createUniqueName, tomcatInfo -> {
                    addNode(tomcatInfo, true);
                    System.out.println("DevTomcat: Successfully added new Tomcat server: " + tomcatInfo.getName());
                });
            } catch (Exception ex) {
                System.err.println("DevTomcat: Error adding Tomcat server: " + ex.getMessage());
            }
        }

        /**
         * Create a unique name for the new server
         */
        private String createUniqueName(String preferredName) {
            List<String> existingNames = new ArrayList<>();

            for (int i = 0; i < myRoot.getChildCount(); i++) {
                String displayName = ((MyNode) myRoot.getChildAt(i)).getDisplayName();
                existingNames.add(displayName);
            }

            String uniqueName = PluginUtils.generateSequentName(existingNames, preferredName);
            System.out.println("DevTomcat: Generated unique server name: '" + uniqueName +
                    "' from preferred: '" + preferredName + "'");

            return uniqueName;
        }
    }

    /**
     * Get count of configured servers
     */
    public int getServerCount() {
        return myRoot.getChildCount();
    }

    /**
     * Get all configured server names
     */
    public List<String> getServerNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < myRoot.getChildCount(); i++) {
            String displayName = ((MyNode) myRoot.getChildAt(i)).getDisplayName();
            names.add(displayName);
        }
        return names;
    }

    /**
     * Check if any servers are configured
     */
    public boolean hasConfiguredServers() {
        return getServerCount() > 0;
    }
}