package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.model.DeploymentKind;
import com.dev.idea.plugins.tomcat.model.DeploymentRow;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.ui.components.JBTextField;

import javax.swing.*;
import java.awt.*;
import java.util.function.Predicate;

public class ArtifactDeploymentEditDialog extends DialogWrapper {

    /** Packaging strings shown in the combo — same vocabulary the renderer / persistence use. */
    private static final String PACKAGING_EXPLODED = "exploded";
    private static final String PACKAGING_WAR = "war";

    private final DeploymentRow row;
    private final Predicate<String> isDuplicateContext;
    private JBTextField contextField;
    private ComboBox<String> typeCombo;

    /**
     * @param parent              parent component used to anchor the dialog window.
     * @param row                 deployment row whose application context / packaging is being edited.
     * @param isDuplicateContext  predicate that returns {@code true} when the supplied
     *                            (already-normalized) context path collides with another
     *                            row's context. Closes the UX asymmetry where the
     *                            inline context field rejected duplicates but this
     *                            dialog accepted them silently — only to fail later in
     *                            {@code DeploymentConfigurationPanel#isConfigurationValid}.
     */
    public ArtifactDeploymentEditDialog(JComponent parent,
                                        @NotNull DeploymentRow row,
                                        @NotNull Predicate<String> isDuplicateContext) {
        super(SwingUtilities.getWindowAncestor(parent), true);
        this.row = row;
        this.isDuplicateContext = isDuplicateContext;
        setTitle("Edit Deployment");
        setModal(true);
        setResizable(false);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(190)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        // Escape the display name — it can be an artifact/module name or a
        // filename derived from an arbitrary path, so '<', '>' or '&' would
        // otherwise garble or truncate this HTML label.
        JBLabel infoLabel = new JBLabel("<html>Edit <b>"
                + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(row.getDisplayName())
                + "</b>:</html>");
        panel.add(infoLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        JBLabel contextLabel = new JBLabel("Application context:");
        contextField = new JBTextField();
        contextField.setText(row.getContextPath());
        contextField.setPreferredSize(new Dimension(JBUI.scale(250), JBUI.scale(25)));
        contextField.selectAll();
        // Associate the label with its field for screen readers / mnemonic focus.
        contextLabel.setLabelFor(contextField);
        panel.add(contextLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(contextField, gbc);

        // Packaging type dropdown. Editable only for EXTERNAL deployments — for
        // artifact/module-backed deployments the packaging is dictated
        // by the underlying artifact (LocalDeploymentStrategy branches on it),
        // so flipping WAR↔EXPLODED here would misdirect the deployment code path.
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        JBLabel packagingLabel = new JBLabel("Packaging:");
        typeCombo = new ComboBox<>(new String[]{
                PACKAGING_EXPLODED,
                PACKAGING_WAR
        });
        typeCombo.setSelectedItem(row.isExploded() ? PACKAGING_EXPLODED : PACKAGING_WAR);
        boolean isExternal = row.getKind() == DeploymentKind.EXTERNAL;
        typeCombo.setEnabled(isExternal);
        if (!isExternal) {
            typeCombo.setToolTipText(
                    "Packaging is dictated by the source artifact and cannot be changed here. "
                            + "Remove and re-add to switch packaging, or add the artifact via "
                            + "'+' → 'External Source...' to get an editable packaging field.");
        }
        // Associate the label with its combo for screen readers / mnemonic focus.
        packagingLabel.setLabelFor(typeCombo);
        panel.add(packagingLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(typeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 0;
        JBLabel helpLabel = new JBLabel(
                "<html><small>The context path where the application will be accessible.<br>"
                        + "For example: /myapp will be served at http://localhost:8080/myapp</small></html>");
        helpLabel.setForeground(NamedColorUtil.getInactiveTextColor());
        panel.add(helpLabel, gbc);

        gbc.gridy = 4; gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String context = contextField.getText().trim();
        if (context.isEmpty()) {
            return new ValidationInfo("Application context cannot be empty", contextField);
        }
        String normalized = ContextPathUtils.normalizeContextPath(context);
        if (!ContextPathUtils.isValidContextPath(normalized)) {
            return new ValidationInfo(
                    "Invalid context path. Must start with '/' and contain only valid URL characters.",
                    contextField);
        }
        // Same duplicate-check the inline context-path field uses. Without this,
        // the dialog silently accepts a colliding context, the editor's
        // isConfigurationValid() rejects the whole save, and the user is left
        // hunting for which two artifacts conflict — an unhelpful failure mode
        // when the dialog already knew the context was invalid.
        if (isDuplicateContext.test(normalized)) {
            return new ValidationInfo(
                    "Context path already in use by another artifact",
                    contextField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String context = ContextPathUtils.normalizeContextPath(contextField.getText().trim());
        row.setContextPath(context);
        // Only apply the packaging change for EXTERNAL deployments; for others the
        // combo is disabled and the selected value is just the current packaging.
        if (row.getKind() == DeploymentKind.EXTERNAL) {
            Object selected = typeCombo.getSelectedItem();
            if (selected instanceof String typeValue) {
                row.setExploded(PACKAGING_EXPLODED.equals(typeValue));
            }
        }
        super.doOKAction();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return contextField;
    }
}
