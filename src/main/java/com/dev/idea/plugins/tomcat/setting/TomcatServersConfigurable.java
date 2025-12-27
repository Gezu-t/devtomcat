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

    import java.util.ArrayList;
    import java.util.List;

    /**
     * Dev Tomcat Servers Configurable
     *
     * Main configuration panel for managing Tomcat servers in the IDE settings.
     */
    public class TomcatServersConfigurable extends MasterDetailsComponent {

        private static final Logger LOG = Logger.getInstance(TomcatServersConfigurable.class);
        private static final String DISPLAY_NAME = "Dev Tomcat Servers";
        private static final String HELP_TOPIC = "dev.tomcat.servers";

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

        @Override
        public boolean isModified() {
            if (super.isModified()) {
                return true;
            }

            int configuredCount = TomcatServerManagerState.getInstance().getTomcatInfos().size();
            int displayedCount = myRoot.getChildCount();

            if (configuredCount != displayedCount) {
                LOG.debug("Server count changed: " + configuredCount + " → " + displayedCount);
                return true;
            }

            return false;
        }

        @Override
        public void reset() {
            myRoot.removeAllChildren();

            TomcatServerManagerState state = TomcatServerManagerState.getInstance();
            List<TomcatInfo> servers = state.getTomcatInfos();

            for (TomcatInfo info : servers) {
                addServerNode(info, false);
            }

            super.reset();
            LOG.info("Reset configuration with " + servers.size() + " servers");
        }

        @Override
        public void apply() throws ConfigurationException {
            validateAllServers();

            super.apply();

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

        @Override
        protected boolean wasObjectStored(Object editableObject) {
            if (!(editableObject instanceof TomcatInfo)) {
                return false;
            }

            TomcatInfo tomcatInfo = (TomcatInfo) editableObject;
            List<TomcatInfo> storedServers = TomcatServerManagerState.getInstance().getTomcatInfos();

            boolean isStored = storedServers.stream()
                    .anyMatch(stored -> stored.getId().equals(tomcatInfo.getId()));

            LOG.debug("Server '" + tomcatInfo.getName() + "' stored: " + isStored);
            return isStored;
        }

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

                LOG.info("Added server node: " + tomcatInfo.getName());

            } catch (Exception e) {
                LOG.error("Failed to add server node", e);
                Messages.showErrorDialog(
                        "Failed to add Tomcat server: " + e.getMessage(),
                        "Error Adding Server"
                );
            }
        }

        private void validateServerName(@NotNull String name) throws ConfigurationException {
            if (name.trim().isEmpty()) {
                throw new ConfigurationException("Server name cannot be empty");
            }

            String trimmedName = name.trim();

            for (int i = 0; i < myRoot.getChildCount(); i++) {
                MyNode node = (MyNode) myRoot.getChildAt(i);
                TomcatInfoConfigurable configurable = (TomcatInfoConfigurable) node.getConfigurable();
                String existingName = configurable.getEditableObject().getName();

                if (existingName.equals(trimmedName)) {
                    throw new ConfigurationException("Server name already exists: \"" + trimmedName + "\"");
                }
            }

            LOG.debug("Validated server name '" + trimmedName + "'");
        }

        public int getServerCount() {
            return myRoot.getChildCount();
        }

        @NotNull
        public List<String> getServerNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < myRoot.getChildCount(); i++) {
                MyNode node = (MyNode) myRoot.getChildAt(i);
                names.add(node.getDisplayName());
            }
            return names;
        }

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

        public boolean hasConfiguredServers() {
            return getServerCount() > 0;
        }

        @Nullable
        public TomcatInfo findServerByName(@NotNull String name) {
            return getServers().stream()
                    .filter(info -> name.equals(info.getName()))
                    .findFirst()
                    .orElse(null);
        }

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
                                LOG.info("Successfully added Tomcat server: " + tomcatInfo.getName());
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

            @NotNull
            private String createUniqueName(@NotNull String preferredName) {
                List<String> existingNames = getServerNames();
                String uniqueName = TomcatServerUtils.generateUniqueName(existingNames, preferredName);

                LOG.debug("Generated unique name: '" + uniqueName + "' (from: '" + preferredName + "')");
                return uniqueName;
            }
        }

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

                String uniqueName = createUniqueName(original.getName() + " Copy");
                TomcatInfo copy = new TomcatInfo(
                        uniqueName,
                        original.getVersion(),
                        original.getPath()
                );

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
    }