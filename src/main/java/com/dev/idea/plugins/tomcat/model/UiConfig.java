package com.dev.idea.plugins.tomcat.model;

        import org.jetbrains.annotations.NotNull;

        import java.io.Serial;
        import java.io.Serializable;
        import java.util.Objects;

        /**
         * UI Configuration for Tool Window Behavior.
         *
         * <p>{@code activateToolWindow} controls whether the Run/Debug tool window
         * is brought to front when the process starts.</p>
         */
        public class UiConfig implements Serializable, Cloneable {

            @Serial
            private static final long serialVersionUID = 1L;

            public static final boolean DEFAULT_ACTIVATE_TOOL_WINDOW = true;
            public static final boolean DEFAULT_ALLOW_MULTIPLE_INSTANCES = false;

            private boolean activateToolWindow;
            private boolean allowMultipleInstances;

            public UiConfig() {
                this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
                this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
            }

            public UiConfig(@NotNull UiConfig other) {
                Objects.requireNonNull(other, "UiConfig cannot be null");
                this.activateToolWindow = other.activateToolWindow;
                this.allowMultipleInstances = other.allowMultipleInstances;
            }

            public boolean isActivateToolWindow() { return activateToolWindow; }
            public void setActivateToolWindow(boolean activate) { this.activateToolWindow = activate; }

            public boolean isAllowMultipleInstances() { return allowMultipleInstances; }
            public void setAllowMultipleInstances(boolean allow) { this.allowMultipleInstances = allow; }

            public void resetToDefaults() {
                this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
                this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
            }

            public boolean shouldShowToolWindow() { return activateToolWindow; }

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
                        allowMultipleInstances == that.allowMultipleInstances;
            }

            @Override
            public int hashCode() {
                return Objects.hash(activateToolWindow, allowMultipleInstances);
            }

            @NotNull
            @Override
            public String toString() {
                return "UiConfig{activate=" + activateToolWindow +
                        ", multiInstance=" + allowMultipleInstances + '}';
            }
        }
