package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBRadioButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * "Update 'ConfigName'" dialog — mirrors IntelliJ Ultimate's Tomcat update dialog.
 *
 * <p>Presents mutually exclusive options and returns the user's choice as an
 * {@link UpdateConfig} action constant. Defaults to the action configured in the
 * run configuration's {@link UpdateConfig#getOnUpdate()}.
 *
 * <p>For remote configurations, "Restart server" is omitted because the IDE cannot
 * control the lifecycle of a remote Tomcat process.
 */
public class TomcatUpdateDialog extends DialogWrapper {

    private final String configName;
    private final boolean showRestartOption;

    private final JBRadioButton rbUpdateResources        = new JBRadioButton("Update resources");
    private final JBRadioButton rbUpdateClassesResources = new JBRadioButton("Update classes and resources");
    private final JBRadioButton rbRedeploy               = new JBRadioButton("Redeploy");
    private final JBRadioButton rbRestartServer          = new JBRadioButton("Restart server");

    /** Full constructor — pass {@code showRestartOption=false} for remote configurations. */
    public TomcatUpdateDialog(@Nullable Project project,
                               @NotNull String configName,
                               @NotNull String defaultAction,
                               boolean showRestartOption) {
        super(project, false);
        this.configName = configName;
        this.showRestartOption = showRestartOption;
        setTitle("Update '" + configName + "'");
        setOKButtonText("OK");

        // If the default is "Restart server" but the option isn't available (remote),
        // fall back to the next best default.
        String effectiveDefault = (!showRestartOption && UpdateConfig.RESTART_SERVER.equals(defaultAction))
                ? UpdateConfig.UPDATE_CLASSES_AND_RESOURCES
                : defaultAction;

        switch (effectiveDefault) {
            case UpdateConfig.UPDATE_RESOURCES -> rbUpdateResources.setSelected(true);
            case UpdateConfig.REDEPLOY         -> rbRedeploy.setSelected(true);
            case UpdateConfig.RESTART_SERVER   -> rbRestartServer.setSelected(true);
            default                            -> rbUpdateClassesResources.setSelected(true);
        }

        init();
    }

    /** Convenience constructor for local configurations (all options shown). */
    public TomcatUpdateDialog(@Nullable Project project,
                               @NotNull String configName,
                               @NotNull String defaultAction) {
        this(project, configName, defaultAction, true);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(rbUpdateResources);
        group.add(rbUpdateClassesResources);
        group.add(rbRedeploy);
        group.add(rbRestartServer);

        JBPanel<JBPanel<?>> panel = new JBPanel<>(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 24));

        panel.add(new JBLabel("Run configuration '" + configName + "' is running."),
                BorderLayout.NORTH);

        int rows = showRestartOption ? 4 : 3;
        JBPanel<JBPanel<?>> buttons = new JBPanel<>(new GridLayout(rows, 1, 0, 4));
        buttons.add(rbUpdateResources);
        buttons.add(rbUpdateClassesResources);
        buttons.add(rbRedeploy);
        if (showRestartOption) {
            buttons.add(rbRestartServer);
        }
        panel.add(buttons, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Returns the {@link UpdateConfig} action constant the user selected.
     * Only valid after {@link #showAndGet()} returns {@code true}.
     */
    @NotNull
    public String getSelectedAction() {
        if (rbUpdateResources.isSelected())       return UpdateConfig.UPDATE_RESOURCES;
        if (rbRedeploy.isSelected())              return UpdateConfig.REDEPLOY;
        if (rbRestartServer.isSelected())         return UpdateConfig.RESTART_SERVER;
        return UpdateConfig.UPDATE_CLASSES_AND_RESOURCES;
    }
}
