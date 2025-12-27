/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/2/25
 *
 * Update Action Configuration
 * Handles hot deployment and frame deactivation behaviors
 */
package com.dev.idea.plugins.tomcat.conf;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Objects;

public class UpdateConfig implements Serializable, Cloneable {

    // === UPDATE ACTION CONSTANTS ===
    public static final String UPDATE_RESOURCES = "update_resources";
    public static final String UPDATE_CLASSES_AND_RESOURCES = "update_classes_and_resources";
    public static final String REDEPLOY = "redeploy";
    public static final String RESTART_SERVER = "restart_server";
    public static final String DO_NOTHING = "do_nothing";

    // === DEFAULT VALUES ===
    public static final String DEFAULT_ON_UPDATE = UPDATE_CLASSES_AND_RESOURCES;
    public static final String DEFAULT_ON_FRAME_DEACTIVATION = DO_NOTHING;

    private String onUpdate;
    private String onFrameDeactivation;
    private boolean showUpdateDialog;
    private boolean showFrameDeactivationDialog;

    public UpdateConfig() {
        this.onUpdate = DEFAULT_ON_UPDATE;
        this.onFrameDeactivation = DEFAULT_ON_FRAME_DEACTIVATION;
        this.showUpdateDialog = true;
        this.showFrameDeactivationDialog = true;
    }

    public UpdateConfig(@NotNull UpdateConfig other) {
        this.onUpdate = other.onUpdate;
        this.onFrameDeactivation = other.onFrameDeactivation;
        this.showUpdateDialog = other.showUpdateDialog;
        this.showFrameDeactivationDialog = other.showFrameDeactivationDialog;
    }

    // === GETTERS/SETTERS ===

    @NotNull
    public String getOnUpdate() {
        return StringUtil.notNullize(onUpdate, DEFAULT_ON_UPDATE);
    }

    public void setOnUpdate(@NotNull String action) {
        this.onUpdate = validateUpdateAction(action);
    }

    @NotNull
    public String getOnFrameDeactivation() {
        return StringUtil.notNullize(onFrameDeactivation, DEFAULT_ON_FRAME_DEACTIVATION);
    }

    public void setOnFrameDeactivation(@NotNull String action) {
        this.onFrameDeactivation = validateUpdateAction(action);
    }

    public boolean isShowUpdateDialog() {
        return showUpdateDialog;
    }

    public void setShowUpdateDialog(boolean show) {
        this.showUpdateDialog = show;
    }

    public boolean isShowFrameDeactivationDialog() {
        return showFrameDeactivationDialog;
    }

    public void setShowFrameDeactivationDialog(boolean show) {
        this.showFrameDeactivationDialog = show;
    }

    // === VALIDATION ===

    private String validateUpdateAction(@NotNull String action) {
        switch (action) {
            case UPDATE_RESOURCES:
            case UPDATE_CLASSES_AND_RESOURCES:
            case REDEPLOY:
            case RESTART_SERVER:
            case DO_NOTHING:
                return action;
            default:
                return DEFAULT_ON_UPDATE;
        }
    }

    // === UTILITY METHODS ===

    public boolean isHotDeploymentEnabled() {
        return UPDATE_CLASSES_AND_RESOURCES.equals(onUpdate) || UPDATE_RESOURCES.equals(onUpdate);
    }

    public boolean isUpdateClassesEnabled() {
        return UPDATE_CLASSES_AND_RESOURCES.equals(onUpdate);
    }

    public boolean isRedeployOnUpdate() {
        return REDEPLOY.equals(onUpdate);
    }

    public boolean isRestartOnUpdate() {
        return RESTART_SERVER.equals(onUpdate);
    }

    public boolean isFrameDeactivationActive() {
        return !DO_NOTHING.equals(onFrameDeactivation);
    }

    // === CLONING ===

    @Override
    public UpdateConfig clone() {
        try {
            return (UpdateConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone UpdateConfig", e);
        }
    }

    // === OBJECT METHODS ===

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

    @Override
    public String toString() {
        return "UpdateConfig{" +
                "onUpdate='" + onUpdate + '\'' +
                ", onFrameDeactivation='" + onFrameDeactivation + '\'' +
                '}';
    }
}