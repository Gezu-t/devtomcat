package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for Tomcat run configuration lifecycle events (removal, rename)
 * and keeps associated data stores in sync.
 *
 * <ul>
 *   <li><b>Removal:</b> cleans up live status, deployment history, and startup
 *       time data so deleted configurations don't leave stale entries.</li>
 *   <li><b>Rename:</b> migrates keyed data (status, history, trends) from
 *       the old name to the new name so nothing is orphaned.</li>
 * </ul>
 */
public final class TomcatConfigurationCleanupListener implements RunManagerListener {

    private static final Logger LOG = Logger.getInstance(TomcatConfigurationCleanupListener.class);

    private final Project project;

    /**
     * Tracks the last-known name for each Tomcat run configuration, keyed by
     * object identity rather than {@code getUniqueID()}.
     *
     * <p>{@code RunnerAndConfigurationSettings.getUniqueID()} is recomputed from the
     * configuration name on rename, so it is not stable enough to detect a rename.
     */
    private final ConcurrentHashMap<IdentityKey<TomcatRunConfiguration>, String> configIdToName =
            new ConcurrentHashMap<>();
    private final AtomicBoolean populated = new AtomicBoolean(false);

    public TomcatConfigurationCleanupListener(@NotNull Project project) {
        this.project = project;
        // Do NOT call RunManager.getInstance() here — this listener is instantiated
        // during RunManager's own initialization, which would cause a cycle.
        // The name map is populated lazily on the first RunManagerListener event.
    }

    private void ensurePopulated() {
        if (populated.compareAndSet(false, true)) {
            try {
                for (RunnerAndConfigurationSettings settings :
                        RunManager.getInstance(project).getAllSettings()) {
                    if (settings.getConfiguration() instanceof TomcatRunConfiguration tomcatConfig) {
                        configIdToName.put(identityKey(tomcatConfig), settings.getName());
                    }
                }
            } catch (Exception e) {
                LOG.debug("Could not populate config name map: " + e.getMessage());
                populated.set(false);
            }
        }
    }

    @Override
    public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
        ensurePopulated();
        if (!(settings.getConfiguration() instanceof TomcatRunConfiguration tomcatConfig)) {
            return;
        }
        configIdToName.put(identityKey(tomcatConfig), settings.getName());
    }

    @Override
    public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
        ensurePopulated();
        if (!(settings.getConfiguration() instanceof TomcatRunConfiguration tomcatConfig)) {
            return;
        }

        String newName = settings.getName();
        String oldName = configIdToName.put(identityKey(tomcatConfig), newName);

        if (oldName != null && !oldName.equals(newName)) {
            LOG.info("DevTomcat: Detected rename '" + oldName + "' → '" + newName + "', migrating data");

            TomcatDeploymentStatusService.getInstance(project)
                    .renameConfiguration(oldName, newName);
            TomcatDeploymentHistory.getInstance(project)
                    .renameConfiguration(oldName, newName);
            StartupTimeTracker.getInstance(project)
                    .renameConfiguration(oldName, newName);
            // App-level registry — without migration, ports claimed under the old
            // name are released by no one and leak until the IDE restarts.
            com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.getInstance()
                    .renameConfiguration(oldName, newName);

            if (!project.isDisposed()) {
                RunDashboardManager.getInstance(project).updateDashboard(true);
            }
        }
    }

    @Override
    public void runConfigurationRemoved(@NotNull RunnerAndConfigurationSettings settings) {
        ensurePopulated();
        if (!(settings.getConfiguration() instanceof TomcatRunConfiguration tomcatConfig)) {
            return;
        }

        String configName = settings.getName();
        LOG.info("DevTomcat: Cleaning up status for removed configuration: " + configName);

        // Remove the tracked name entry
        configIdToName.remove(identityKey(tomcatConfig));

        // Clear live status data
        TomcatDeploymentStatusService statusService =
                TomcatDeploymentStatusService.getInstance(project);
        statusService.remove(configName);

        // Remove deployment history for this configuration so deleted configs
        // don't leave stale entries in the history UI indefinitely.
        TomcatDeploymentHistory.getInstance(project).removeEntriesFor(configName);

        // Clear startup time tracking data for the removed configuration
        StartupTimeTracker.getInstance(project).clearHistory(configName);

        // Force the Services tool window to refresh so the stale node disappears
        if (!project.isDisposed()) {
            RunDashboardManager.getInstance(project).updateDashboard(true);
        }
    }

    static <T> @NotNull IdentityKey<T> identityKey(@NotNull T value) {
        return new IdentityKey<>(value);
    }

    static final class IdentityKey<T> {
        private final T value;

        IdentityKey(@NotNull T value) {
            this.value = Objects.requireNonNull(value);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof IdentityKey<?> other && other.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
