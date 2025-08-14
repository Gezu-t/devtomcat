package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.NamedConfigurable;
import com.intellij.openapi.util.Comparing;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Tomcat Server Configurable
 *
 * Provides configuration UI for a single Tomcat server instance.
 * This class integrates with IntelliJ's settings system to allow
 * editing of server properties through the IDE settings dialog.
 *
 * @author Dev Tomcat Team
 */
public class TomcatInfoConfigurable extends NamedConfigurable<TomcatInfo> {

    private static final Logger LOG = Logger.getInstance(TomcatInfoConfigurable.class);

    @NotNull private final TomcatInfo tomcatInfo;
    @NotNull private final TomcatInfoComponent tomcatInfoView;
    @NotNull private String displayName;
    @Nullable private final TomcatNameValidator<String> nameValidator;

    private final String originalName;

    /**
     * Create a new configurable for a Tomcat server
     *
     * @param tomcatInfo The Tomcat server to configure
     * @param treeUpdater Runnable to update the settings tree
     * @param nameValidator Validator for server names
     */
    public TomcatInfoConfigurable(@NotNull TomcatInfo tomcatInfo,
                                  @NotNull Runnable treeUpdater,
                                  @Nullable TomcatNameValidator<String> nameValidator) {
        super(true, treeUpdater);
        this.tomcatInfo = tomcatInfo;
        this.tomcatInfoView = new TomcatInfoComponent(tomcatInfo);
        this.displayName = tomcatInfo.getName();
        this.originalName = tomcatInfo.getName();
        this.nameValidator = nameValidator;

        LOG.debug("Created configurable for server: " + tomcatInfo.getName());
    }

    @Override
    public void setDisplayName(@NotNull String name) {
        this.displayName = name;
    }

    @Override
    @NotNull
    public TomcatInfo getEditableObject() {
        return tomcatInfo;
    }

    @Override
    @Nullable
    public String getBannerSlogan() {
        // Can return a slogan to display in the settings
        return null;
    }

    @Override
    @NotNull
    public JComponent createOptionsPanel() {
        return tomcatInfoView.getMainPanel();
    }

    @Override
    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get the help topic ID for this configurable
     *
     * @return The help topic ID or null
     */
    @Override
    @Nullable
    @NonNls
    public String getHelpTopic() {
        return "dev.tomcat.server.configuration";
    }

    /**
     * Check the validity of the server name
     *
     * @param name The name to check
     * @throws ConfigurationException if the name is invalid
     */
    @Override
    protected void checkName(@NonNls @NotNull String name) throws ConfigurationException {
        super.checkName(name);

        // Don't validate if name hasn't changed
        if (name.equals(tomcatInfo.getName())) {
            return;
        }

        // Validate with custom validator if provided
        if (nameValidator != null) {
            nameValidator.validate(name);
        }
    }

    /**
     * Check if the configuration has been modified
     *
     * @return true if modified
     */
    @Override
    public boolean isModified() {
        // Check if display name has changed
        boolean nameModified = !Comparing.equal(displayName, tomcatInfo.getName());

        // Check if component has modifications
        boolean componentModified = tomcatInfoView.isModified();

        if (nameModified || componentModified) {
            LOG.debug("Configuration modified for server: " + originalName);
        }

        return nameModified || componentModified;
    }

    /**
     * Apply the changes to the configuration
     *
     * @throws ConfigurationException if validation fails
     */
    @Override
    public void apply() throws ConfigurationException {
        // Validate before applying
        if (!displayName.equals(tomcatInfo.getName())) {
            checkName(displayName);
        }

        // Apply name change
        String oldName = tomcatInfo.getName();
        tomcatInfo.setName(displayName);

        // Validate the server configuration
        try {
            tomcatInfo.validate();
        } catch (IllegalStateException e) {
            // Revert name on validation failure
            tomcatInfo.setName(oldName);
            throw new ConfigurationException("Invalid server configuration: " + e.getMessage());
        }

        // Refresh the view
        tomcatInfoView.refresh();

        LOG.info("Applied configuration for server: " + tomcatInfo.getName() +
                (oldName.equals(tomcatInfo.getName()) ? "" : " (was: " + oldName + ")"));
    }

    /**
     * Reset the configuration to its original state
     */
    @Override
    public void reset() {
        displayName = tomcatInfo.getName();
        tomcatInfoView.refresh();

        LOG.debug("Reset configuration for server: " + tomcatInfo.getName());
    }

    /**
     * Dispose of resources
     */
    @Override
    public void disposeUIResources() {
        tomcatInfoView.dispose();
        super.disposeUIResources();

        LOG.debug("Disposed UI resources for server: " + originalName);
    }

    /**
     * Get icon for this configurable
     *
     * @return The icon or null
     */
    @Override
    @Nullable
    public Icon getIcon(boolean expanded) {
        // Could return a Tomcat icon here
        return null;
    }

    /**
     * Get the weight for sorting
     *
     * @return The weight value
     */
    public int getWeight() {
        // Can be used to control sort order in the tree
        return 0;
    }
}

/**
 * Functional interface for validating Tomcat server names
 *
 * @param <T> The type to validate (typically String)
 */
@FunctionalInterface
interface TomcatNameValidator<T> {
    /**
     * Validate the given value
     *
     * @param t The value to validate
     * @throws ConfigurationException if validation fails
     */
    void validate(T t) throws ConfigurationException;
}