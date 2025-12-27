package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Update Action Configuration for Tomcat hot deployment and frame deactivation behaviors.
 */
public class UpdateConfig implements Serializable, Cloneable {

    private static final Logger LOG = Logger.getInstance(UpdateConfig.class);

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String UPDATE_RESOURCES = "update_resources";
    public static final String UPDATE_CLASSES_AND_RESOURCES = "update_classes_and_resources";
    public static final String REDEPLOY = "redeploy";
    public static final String RESTART_SERVER = "restart_server";
    public static final String DO_NOTHING = "do_nothing";

    public static final String DEFAULT_ON_UPDATE = UPDATE_CLASSES_AND_RESOURCES;
    public static final String DEFAULT_ON_FRAME_DEACTIVATION = DO_NOTHING;
    public static final boolean DEFAULT_SHOW_UPDATE_DIALOG = true;
    public static final boolean DEFAULT_SHOW_FRAME_DEACTIVATION_DIALOG = true;

    @NotNull private String onUpdate;
    @NotNull private String onFrameDeactivation;
    private boolean showUpdateDialog;
    private boolean showFrameDeactivationDialog;

    public UpdateConfig() {
        this.onUpdate = DEFAULT_ON_UPDATE;
        this.onFrameDeactivation = DEFAULT_ON_FRAME_DEACTIVATION;
        this.showUpdateDialog = DEFAULT_SHOW_UPDATE_DIALOG;
        this.showFrameDeactivationDialog = DEFAULT_SHOW_FRAME_DEACTIVATION_DIALOG;
    }

    public UpdateConfig(@NotNull UpdateConfig other) {
        Objects.requireNonNull(other, "UpdateConfig cannot be null");
        this.onUpdate = other.onUpdate;
        this.onFrameDeactivation = other.onFrameDeactivation;
        this.showUpdateDialog = other.showUpdateDialog;
        this.showFrameDeactivationDialog = other.showFrameDeactivationDialog;
    }

    @NotNull
    public String getOnUpdate() { return StringUtil.notNullize(onUpdate, DEFAULT_ON_UPDATE); }

    public void setOnUpdate(@NotNull String action) {
        Objects.requireNonNull(action, "Update action cannot be null");
        this.onUpdate = validateUpdateAction(action);
    }

    @NotNull
    public String getOnFrameDeactivation() { return StringUtil.notNullize(onFrameDeactivation, DEFAULT_ON_FRAME_DEACTIVATION); }

    public void setOnFrameDeactivation(@NotNull String action) {
        Objects.requireNonNull(action, "Frame deactivation action cannot be null");
        this.onFrameDeactivation = validateUpdateAction(action);
    }

    public boolean isShowUpdateDialog() { return showUpdateDialog; }
    public void setShowUpdateDialog(boolean show) { this.showUpdateDialog = show; }

    public boolean isShowFrameDeactivationDialog() { return showFrameDeactivationDialog; }
    public void setShowFrameDeactivationDialog(boolean show) { this.showFrameDeactivationDialog = show; }

    @NotNull
    private String validateUpdateAction(@NotNull String action) {
        return switch (action) {
            case UPDATE_RESOURCES, UPDATE_CLASSES_AND_RESOURCES, REDEPLOY, RESTART_SERVER, DO_NOTHING -> action;
            default -> {
                LOG.warn("Invalid update action: " + action + ", using default");
                yield DEFAULT_ON_UPDATE;
            }
        };
    }

    public boolean isHotDeploymentEnabled() {
        return UPDATE_CLASSES_AND_RESOURCES.equals(onUpdate) || UPDATE_RESOURCES.equals(onUpdate);
    }

    public boolean isUpdateClassesEnabled() { return UPDATE_CLASSES_AND_RESOURCES.equals(onUpdate); }
    public boolean isRedeployOnUpdate() { return REDEPLOY.equals(onUpdate); }
    public boolean isRestartOnUpdate() { return RESTART_SERVER.equals(onUpdate); }
    public boolean isFrameDeactivationActive() { return !DO_NOTHING.equals(onFrameDeactivation); }

    public void resetToDefaults() {
        this.onUpdate = DEFAULT_ON_UPDATE;
        this.onFrameDeactivation = DEFAULT_ON_FRAME_DEACTIVATION;
        this.showUpdateDialog = DEFAULT_SHOW_UPDATE_DIALOG;
        this.showFrameDeactivationDialog = DEFAULT_SHOW_FRAME_DEACTIVATION_DIALOG;
    }

    @NotNull
    @Override
    public UpdateConfig clone() {
        try {
            return (UpdateConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            return new UpdateConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateConfig that = (UpdateConfig) o;
        return showUpdateDialog == that.showUpdateDialog &&
                showFrameDeactivationDialog == that.showFrameDeactivationDialog &&
                Objects.equals(onUpdate, that.onUpdate) &&
                Objects.equals(onFrameDeactivation, that.onFrameDeactivation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(onUpdate, onFrameDeactivation, showUpdateDialog, showFrameDeactivationDialog);
    }

    @NotNull
    @Override
    public String toString() {
        return "UpdateConfig{onUpdate='" + onUpdate + "', onFrameDeactivation='" + onFrameDeactivation +
                "', showUpdateDialog=" + showUpdateDialog + ", showFrameDeactivationDialog=" + showFrameDeactivationDialog + '}';
    }
}