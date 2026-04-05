package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBRadioButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * "Update 'ConfigName'" dialog — mirrors IntelliJ Ultimate's Tomcat update dialog.
 *
 * <p>Presents four mutually exclusive options and returns the user's choice as
 * an {@link UpdateConfig} action constant. Defaults to the action configured in
 * the run configuration's {@link UpdateConfig#getOnUpdate()}.
 */
public class TomcatUpdateDialog extends DialogWrapper {

    private final JBRadioButton rbUpdateResources         = new JBRadioButton("Update resources");
    private final JBRadioButton rbUpdateClassesResources  = new JBRadioButton("Update classes and resources");
    private final JBRadioButton rbRedeploy                = new JBRadioButton("Redeploy");
    private final JBRadioButton rbRestartServer           = new JBRadioButton("Restart server");

    public TomcatUpdateDialog(@Nullable Project project,
                               @NotNull String configName,
                               @NotNull String defaultAction) {
        super(project, false);
        setTitle("Update '" + configName + "'");
        setOKButtonText("OK");

        switch (defaultAction) {
            case UpdateConfig.UPDATE_RESOURCES    -> rbUpdateResources.setSelected(true);
            case UpdateConfig.REDEPLOY            -> rbRedeploy.setSelected(true);
            case UpdateConfig.RESTART_SERVER      -> rbRestartServer.setSelected(true);
            default                               -> rbUpdateClassesResources.setSelected(true);
        }

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(rbUpdateResources);
        group.add(rbUpdateClassesResources);
        group.add(rbRedeploy);
        group.add(rbRestartServer);

        JBPanel<JBPanel<?>> panel = new JBPanel<>(new GridLayout(4, 1, 0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 24));
        panel.add(rbUpdateResources);
        panel.add(rbUpdateClassesResources);
        panel.add(rbRedeploy);
        panel.add(rbRestartServer);
        return panel;
    }

    /**
     * Returns the {@link UpdateConfig} action constant the user selected.
     * Only valid after {@link #showAndGet()} returns {@code true}.
     */
    @NotNull
    public String getSelectedAction() {
        if (rbUpdateResources.isSelected())        return UpdateConfig.UPDATE_RESOURCES;
        if (rbRedeploy.isSelected())               return UpdateConfig.REDEPLOY;
        if (rbRestartServer.isSelected())          return UpdateConfig.RESTART_SERVER;
        return UpdateConfig.UPDATE_CLASSES_AND_RESOURCES;
    }
}
