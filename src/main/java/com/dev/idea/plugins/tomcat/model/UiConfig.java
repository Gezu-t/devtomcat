package com.dev.idea.plugins.tomcat.model;

        import org.jetbrains.annotations.NotNull;

        import java.io.Serial;
        import java.io.Serializable;
        import java.util.Objects;

        /**
         * UI Configuration for Tool Window Behavior.
         */
        public class UiConfig implements Serializable, Cloneable {

            @Serial
            private static final long serialVersionUID = 1L;

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

            public UiConfig(@NotNull UiConfig other) {
                Objects.requireNonNull(other, "UiConfig cannot be null");
                this.activateToolWindow = other.activateToolWindow;
                this.focusToolWindow = other.focusToolWindow;
                this.allowMultipleInstances = other.allowMultipleInstances;
            }

            public boolean isActivateToolWindow() { return activateToolWindow; }

            public void setActivateToolWindow(boolean activate) {
                this.activateToolWindow = activate;
                if (!activate && this.focusToolWindow) {
                    this.focusToolWindow = false;
                }
            }

            public boolean isFocusToolWindow() { return focusToolWindow; }

            public void setFocusToolWindow(boolean focus) {
                this.focusToolWindow = focus;
                if (focus && !this.activateToolWindow) {
                    this.activateToolWindow = true;
                }
            }

            public boolean isAllowMultipleInstances() { return allowMultipleInstances; }
            public void setAllowMultipleInstances(boolean allow) { this.allowMultipleInstances = allow; }

            public void resetToDefaults() {
                this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
                this.focusToolWindow = DEFAULT_FOCUS_TOOL_WINDOW;
                this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
            }

            public boolean shouldShowToolWindow() { return activateToolWindow; }
            public boolean shouldFocusToolWindow() { return activateToolWindow && focusToolWindow; }

            @NotNull
            @Override
            public UiConfig clone() {
                try {
                    return (UiConfig) super.clone();
                } catch (CloneNotSupportedException e) {
                    return new UiConfig(this);
                }
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                UiConfig that = (UiConfig) o;
                return activateToolWindow == that.activateToolWindow &&
                        focusToolWindow == that.focusToolWindow &&
                        allowMultipleInstances == that.allowMultipleInstances;
            }

            @Override
            public int hashCode() {
                return Objects.hash(activateToolWindow, focusToolWindow, allowMultipleInstances);
            }

            @NotNull
            @Override
            public String toString() {
                return "UiConfig{activate=" + activateToolWindow +
                        ", focus=" + focusToolWindow +
                        ", multiInstance=" + allowMultipleInstances + '}';
            }
        }