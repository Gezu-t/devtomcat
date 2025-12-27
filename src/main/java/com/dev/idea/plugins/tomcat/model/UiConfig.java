/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/2/25
 *
 * UI Configuration Manager
 * Handles tool window and instance management settings
 */
package com.dev.idea.plugins.tomcat.conf;

import java.io.Serializable;
import java.util.Objects;

public class UiConfig implements Serializable, Cloneable {

    // === DEFAULT VALUES ===
    public static final boolean DEFAULT_ACTIVATE_TOOL_WINDOW = true;
    public static final boolean DEFAULT_FOCUS_TOOL_WINDOW = false;
    public static final boolean DEFAULT_ALLOW_MULTIPLE_INSTANCES = false;

    private boolean activateToolWindow;
    private boolean focusToolWindow;
    private boolean allowMultipleInstances;

    public UiConfig() {
        this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
        this.focusToolWindow = DEFAULT_FOCUS_TOOL_WINDOW;
        this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
    }

    public UiConfig(UiConfig other) {
        this.activateToolWindow = other.activateToolWindow;
        this.focusToolWindow = other.focusToolWindow;
        this.allowMultipleInstances = other.allowMultipleInstances;
    }

    // === GETTERS/SETTERS ===

    public boolean isActivateToolWindow() {
        return activateToolWindow;
    }

    public void setActivateToolWindow(boolean activate) {
        this.activateToolWindow = activate;
    }

    public boolean isFocusToolWindow() {
        return focusToolWindow;
    }

    public void setFocusToolWindow(boolean focus) {
        this.focusToolWindow = focus;
        // If focus is enabled, activation must also be enabled
        if (focus) {
            this.activateToolWindow = true;
        }
    }

    public boolean isAllowMultipleInstances() {
        return allowMultipleInstances;
    }

    public void setAllowMultipleInstances(boolean allow) {
        this.allowMultipleInstances = allow;
    }

    // === UTILITY METHODS ===

    /**
     * Reset to default values
     */
    public void resetToDefaults() {
        this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
        this.focusToolWindow = DEFAULT_FOCUS_TOOL_WINDOW;
        this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
    }

    /**
     * Check if tool window should be shown
     */
    public boolean shouldShowToolWindow() {
        return activateToolWindow;
    }

    /**
     * Check if tool window should receive focus
     */
    public boolean shouldFocusToolWindow() {
        return focusToolWindow && activateToolWindow;
    }

    // === CLONING ===

    @Override
    public UiConfig clone() {
        try {
            return (UiConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone UiConfig", e);
        }
    }

    // === OBJECT METHODS ===

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UiConfig uiConfig = (UiConfig) o;
        return activateToolWindow == uiConfig.activateToolWindow &&
                focusToolWindow == uiConfig.focusToolWindow &&
                allowMultipleInstances == uiConfig.allowMultipleInstances;
    }

    @Override
    public int hashCode() {
        return Objects.hash(activateToolWindow, focusToolWindow, allowMultipleInstances);
    }

    @Override
    public String toString() {
        return "UiConfig{" +
                "activateToolWindow=" + activateToolWindow +
                ", focusToolWindow=" + focusToolWindow +
                ", allowMultipleInstances=" + allowMultipleInstances +
                '}';
    }
}