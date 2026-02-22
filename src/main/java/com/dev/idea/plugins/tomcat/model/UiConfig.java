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
         *
         * <p>{@code showLogsPage} controls whether the Logs tab is auto-selected
         * when the tool window is activated (IntelliJ's "Show this page" semantic).</p>
         */
        public class UiConfig implements Serializable, Cloneable {

            @Serial
            private static final long serialVersionUID = 1L;

            public static final boolean DEFAULT_ACTIVATE_TOOL_WINDOW = true;
            public static final boolean DEFAULT_SHOW_LOGS_PAGE = false;
            public static final boolean DEFAULT_ALLOW_MULTIPLE_INSTANCES = false;

            private boolean activateToolWindow;
            private boolean showLogsPage;
            private boolean allowMultipleInstances;

            public UiConfig() {
                this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
                this.showLogsPage = DEFAULT_SHOW_LOGS_PAGE;
                this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
            }

            public UiConfig(@NotNull UiConfig other) {
                Objects.requireNonNull(other, "UiConfig cannot be null");
                this.activateToolWindow = other.activateToolWindow;
                this.showLogsPage = other.showLogsPage;
                this.allowMultipleInstances = other.allowMultipleInstances;
            }

            public boolean isActivateToolWindow() { return activateToolWindow; }

            /**
             * Invariant: showLogsPage requires activateToolWindow.
             * Disabling the tool window clears showLogsPage as a model-level safety net.
             * The UI layer (LogsConfigurationTab) also enforces this visually.
             */
            public void setActivateToolWindow(boolean activate) {
                this.activateToolWindow = activate;
                if (!activate) {
                    this.showLogsPage = false;
                }
            }

            public boolean isShowLogsPage() { return showLogsPage; }

            /**
             * Invariant: showLogsPage requires activateToolWindow.
             * Enabling showLogsPage implies activateToolWindow.
             * The UI layer (LogsConfigurationTab) also enforces this visually.
             */
            public void setShowLogsPage(boolean show) {
                this.showLogsPage = show;
                if (show) {
                    this.activateToolWindow = true;
                }
            }

            public boolean isAllowMultipleInstances() { return allowMultipleInstances; }
            public void setAllowMultipleInstances(boolean allow) { this.allowMultipleInstances = allow; }

            public void resetToDefaults() {
                this.activateToolWindow = DEFAULT_ACTIVATE_TOOL_WINDOW;
                this.showLogsPage = DEFAULT_SHOW_LOGS_PAGE;
                this.allowMultipleInstances = DEFAULT_ALLOW_MULTIPLE_INSTANCES;
            }

            public boolean shouldShowToolWindow() { return activateToolWindow; }
            public boolean shouldShowLogsPage() { return activateToolWindow && showLogsPage; }

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
                        showLogsPage == that.showLogsPage &&
                        allowMultipleInstances == that.allowMultipleInstances;
            }

            @Override
            public int hashCode() {
                return Objects.hash(activateToolWindow, showLogsPage, allowMultipleInstances);
            }

            @NotNull
            @Override
            public String toString() {
                return "UiConfig{activate=" + activateToolWindow +
                        ", showLogsPage=" + showLogsPage +
                        ", multiInstance=" + allowMultipleInstances + '}';
            }
        }
