package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides the "Build N artifact(s)" entry in the Before Launch panel for
 * DevTomcat run configurations on both Community and Ultimate editions.
 *
 * <p>On Ultimate, IntelliJ's built-in {@code BuildArtifactsBeforeRunTask} already handles
 * artifact building and is added by {@link TomcatRunConfiguration#syncBeforeLaunchWithDeployments()}.
 * This provider complements it (Community) or coexists alongside it (Ultimate):
 *
 * <ul>
 *   <li>Displays the configured artifact names so the user can see at a glance what will
 *       be deployed — matching the Before Launch visibility of IntelliJ Ultimate.</li>
 *   <li>Validates that every configured artifact path exists on disk before launch,
 *       giving a clear failure instead of a cryptic "artifact not found" mid-launch.</li>
 * </ul>
 *
 * <p>This task intentionally does <em>not</em> re-compile; the "Build" (Make) task in
 * Before Launch covers compilation.
 */
public class TomcatBuildArtifactsTaskProvider extends BeforeRunTaskProvider<TomcatBuildArtifactsTask> {

    public static final Key<TomcatBuildArtifactsTask> ID =
            Key.create("DevTomcat.BuildArtifacts");

    private static final Logger LOG = Logger.getInstance(TomcatBuildArtifactsTaskProvider.class);

    @Override
    public Key<TomcatBuildArtifactsTask> getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Build DevTomcat artifacts";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.Nodes.Artifact;
    }

    @Override
    public Icon getTaskIcon(TomcatBuildArtifactsTask task) {
        return AllIcons.Nodes.Artifact;
    }

    /**
     * Returns the description shown in the Before Launch panel.
     * Reads artifact names stored in the task (kept in sync by
     * {@link TomcatRunConfiguration#syncBeforeLaunchWithDeployments()}).
     */
    @Override
    @NotNull
    public String getDescription(@NotNull TomcatBuildArtifactsTask task) {
        List<String> names = task.getArtifactNames();
        if (names.isEmpty()) {
            return "Build artifacts";
        }
        if (names.size() == 1) {
            return "Build '" + names.get(0) + "'";
        }
        return "Build " + names.size() + " artifacts";
    }

    @Override
    @Nullable
    public TomcatBuildArtifactsTask createTask(@NotNull RunConfiguration runConfiguration) {
        if (!(runConfiguration instanceof TomcatRunConfiguration)) return null;
        TomcatBuildArtifactsTask task = new TomcatBuildArtifactsTask(ID);
        task.setEnabled(true);
        return task;
    }

    /**
     * Validates that every configured artifact path exists before Tomcat starts.
     * Returns {@code false} (cancels launch) if any artifact is missing,
     * so the user sees a clear failure rather than a confusing mid-launch error.
     */
    @Override
    public boolean executeTask(@NotNull DataContext context,
                               @NotNull RunConfiguration configuration,
                               @NotNull ExecutionEnvironment environment,
                               @NotNull TomcatBuildArtifactsTask task) {
        if (!(configuration instanceof TomcatRunConfiguration tomcatConfig)) return true;

        List<DeploymentArtifact> artifacts = tomcatConfig.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();

        boolean allExist = true;
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;
            Path path = Path.of(artifact.getPath());
            if (!Files.exists(path)) {
                LOG.error("DevTomcat: artifact not found before launch — " + artifact.getPath()
                        + " ('" + artifact.getDisplayName() + "'). Run the build first.");
                allExist = false;
            }
        }
        return allExist;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public boolean canExecuteTask(@NotNull RunConfiguration configuration,
                                  @NotNull TomcatBuildArtifactsTask task) {
        return configuration instanceof TomcatRunConfiguration;
    }
}
