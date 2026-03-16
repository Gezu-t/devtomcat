package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.NamedConfigurable;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

/**
 * Tomcat Server Configurable
 *
 * Provides configuration UI for a single Tomcat server instance.
 */
public class TomcatInfoConfigurable extends NamedConfigurable<TomcatInfo> {

    private static final Logger LOG = Logger.getInstance(TomcatInfoConfigurable.class);

    @NotNull private final TomcatInfo tomcatInfo;
    @NotNull private final TomcatInfoComponent tomcatInfoView;
    @NotNull private String displayName;
    @Nullable private final TomcatNameValidator nameValidator;
    private final String originalName;

    public TomcatInfoConfigurable(@NotNull TomcatInfo tomcatInfo,
                                  @NotNull Runnable treeUpdater,
                                  @Nullable TomcatNameValidator nameValidator) {
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

    @Override
    @Nullable
    @NonNls
    public String getHelpTopic() {
        return "dev.tomcat.server.configuration";
    }

    @Override
    protected void checkName(@NonNls @NotNull String name) throws ConfigurationException {
        super.checkName(name);

        if (name.equals(tomcatInfo.getName())) {
            return;
        }

        if (nameValidator != null) {
            nameValidator.validate(name);
        }
    }

    @Override
    public boolean isModified() {
        boolean nameModified = !Objects.equals(displayName, tomcatInfo.getName());
        boolean componentModified = tomcatInfoView.isModified();

        if (nameModified || componentModified) {
            LOG.debug("Configuration modified for server: " + originalName);
        }

        return nameModified || componentModified;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (!displayName.equals(tomcatInfo.getName())) {
            checkName(displayName);
        }

        String oldName = tomcatInfo.getName();
        tomcatInfo.setName(displayName);

        try {
            tomcatInfo.validate();
        } catch (IllegalStateException e) {
            tomcatInfo.setName(oldName);
            throw new ConfigurationException("Invalid server configuration: " + e.getMessage());
        }

        tomcatInfoView.refresh();

        LOG.info("Applied configuration for server: " + tomcatInfo.getName() +
                (oldName.equals(tomcatInfo.getName()) ? "" : " (was: " + oldName + ")"));
    }

    @Override
    public void reset() {
        displayName = tomcatInfo.getName();
        tomcatInfoView.refresh();

        LOG.debug("Reset configuration for server: " + tomcatInfo.getName());
    }

    @Override
    public void disposeUIResources() {
        tomcatInfoView.dispose();
        super.disposeUIResources();

        LOG.debug("Disposed UI resources for server: " + originalName);
    }

    @Override
    @Nullable
    public Icon getIcon(boolean expanded) {
        return null;
    }

    public int getWeight() {
        return 0;
    }
}