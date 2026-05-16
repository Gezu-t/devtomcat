package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.diagnostics.ArtifactStalenessDetector;
import com.dev.idea.plugins.tomcat.diagnostics.ArtifactStructureValidator;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Key;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        List<DeploymentArtifact> artifacts = tomcatConfig.getDeployedArtifacts();

        boolean allValid = true;
        StringBuilder missing = new StringBuilder();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            if (!artifact.isValid()) {
                String message = "Artifact not ready: '" + artifact.getDisplayName() +
                        "' at " + artifact.getPath();
                LOG.warn("DevTomcat: " + message);
                if (missing.length() > 0) missing.append("\n");
                missing.append("• ").append(artifact.getDisplayName());
                allValid = false;
            }
        }
        if (!allValid) {
            TomcatNotifier.error(tomcatConfig.getProject(),
                    "DevTomcat: Artifacts Not Ready",
                    "Cannot start Tomcat. The following artifacts are missing:\n" + missing +
                            "\n\nBuild the project first (Build → Build Artifacts).");
            return false;
        }

        // Structure validation — the path exists but is it actually a deployable
        // webapp? Catches the most common missing-JAR/incomplete-build cases:
        //   • exploded artifact with no WEB-INF/      (build never completed)
        //   • exploded artifact with empty classes/   (Make step broken)
        //   • WAR artifact path that is a directory   (type / path mismatch)
        // Tomcat would fail on any of these but with a confusing log message
        // 10 seconds into startup. Catching it here gives the user a clear,
        // actionable balloon and skips the futile launch.
        ArtifactStructureValidator.Result structure = ArtifactStructureValidator.validate(artifacts);
        if (structure.hasBlockingErrors()) {
            TomcatNotifier.error(tomcatConfig.getProject(),
                    "DevTomcat: Artifact structure invalid",
                    String.join("\n\n", structure.blockingErrors()));
            return false;
        }
        if (structure.hasWarnings()) {
            TomcatNotifier.warning(tomcatConfig.getProject(),
                    "DevTomcat: Artifact may be incomplete",
                    String.join("\n\n", structure.warnings()));
            // Soft warning — launch continues
        }

        // Staleness check — only runs when the launch is NOT covered by the
        // platform's own BuildArtifactsBeforeRunTask. That task rebuilds
        // matching IntelliJ Artifacts itself, so on the Ultimate path the
        // deployed file is guaranteed fresh by the time we get here. For
        // external paths (Maven target/, Gradle build/, hand-rolled WARs)
        // nothing rebuilds them — the "Make" task only updates
        // out/production/classes/, and Tomcat happily serves the stale file.
        // Surface a balloon naming the offending source so the user can fix
        // the build setup (add `mvn package` to Before Launch, point the
        // artifact at the right output, etc.). Non-blocking: returns true so
        // the launch proceeds.
        if (!hasPlatformArtifactBuildTask(tomcatConfig)) {
            List<ArtifactStalenessDetector.StaleReport> stale =
                    ArtifactStalenessDetector.findStaleArtifacts(
                            tomcatConfig.getProject(), artifacts);
            if (!stale.isEmpty()) {
                warnStaleArtifacts(tomcatConfig, stale);
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the platform's own
     * {@code BuildArtifactsBeforeRunTask} is enabled on the configuration.
     * In that case the platform rebuilds the matching IntelliJ Artifact and
     * the deployed file is fresh — skip the staleness check to avoid
     * surfacing a false positive on Ultimate / configured-artifact setups.
     */
    private static boolean hasPlatformArtifactBuildTask(@NotNull TomcatRunConfiguration config) {
        try {
            for (BeforeRunTask<?> task : RunManagerEx.getInstanceEx(config.getProject())
                    .getBeforeRunTasks(config)) {
                if (task == null || !task.isEnabled()) continue;
                if (task instanceof com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // If we cannot read BeforeRun tasks for any reason, fall back to
            // running the staleness check — a false positive (extra warning)
            // is preferable to a false negative (silent stale code).
            return false;
        }
    }

    private static void warnStaleArtifacts(@NotNull TomcatRunConfiguration config,
                                           @NotNull List<ArtifactStalenessDetector.StaleReport> stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source files have been edited since the deployed artifact was last built. ")
                .append("The change may not be visible after launch.\n\n");
        int shown = Math.min(stale.size(), 3);
        for (int i = 0; i < shown; i++) {
            ArtifactStalenessDetector.StaleReport r = stale.get(i);
            sb.append("• '").append(r.artifactDisplayName()).append("' — ")
                    .append(r.exampleSourceFile()).append(" is newer than ")
                    .append(r.artifactPath()).append('\n');
        }
        if (stale.size() > shown) {
            sb.append("• … and ").append(stale.size() - shown).append(" more\n");
        }
        sb.append("\nRebuild the artifact, or add a build step to Before Launch ")
                .append("(e.g., 'Run Maven Goal: package' for Maven projects).");
        TomcatNotifier.warning(config.getProject(),
                "DevTomcat: Deployed artifact may be stale",
                sb.toString());
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    /**
     * Shows a dialog listing all deployed artifacts with checkboxes.
     * Already-selected artifacts are pre-checked. The user can toggle
     * which artifacts to include in the pre-launch build validation.
     */
    @Override
    public @NotNull Promise<Boolean> configureTask(@NotNull DataContext context,
                                                    @NotNull RunConfiguration configuration,
                                                    @NotNull TomcatBuildArtifactsTask task) {
        AsyncPromise<Boolean> promise = new AsyncPromise<>();

        if (!(configuration instanceof TomcatRunConfiguration tomcatConfig)) {
            promise.setResult(false);
            return promise;
        }

        List<DeploymentArtifact> allArtifacts = tomcatConfig.getDeployedArtifacts();
        if (allArtifacts.isEmpty()) {
            promise.setResult(false);
            return promise;
        }

        Project project = configuration.getProject();
        Set<String> selected = new HashSet<>(task.getArtifactNames());

        SelectArtifactsDialog dialog = new SelectArtifactsDialog(project, allArtifacts, selected);
        if (dialog.showAndGet()) {
            task.setArtifactNames(dialog.getSelectedNames());
            promise.setResult(true);
        } else {
            promise.setResult(false);
        }
        return promise;
    }

    @Override
    public boolean canExecuteTask(@NotNull RunConfiguration configuration,
                                  @NotNull TomcatBuildArtifactsTask task) {
        return configuration instanceof TomcatRunConfiguration;
    }

    /**
     * Dialog that shows all deployment artifacts with checkboxes.
     * Deployed artifacts are pre-checked; the user can toggle selection.
     */
    private static class SelectArtifactsDialog extends DialogWrapper {

        private final CheckBoxList<String> checkBoxList;

        SelectArtifactsDialog(@NotNull Project project,
                              @NotNull List<DeploymentArtifact> artifacts,
                              @NotNull Set<String> preSelected) {
            super(project, false);
            setTitle("Select Artifacts");

            checkBoxList = new CheckBoxList<>();
            for (DeploymentArtifact artifact : artifacts) {
                String name = artifact.getDisplayName();
                checkBoxList.addItem(name, name, preSelected.isEmpty() || preSelected.contains(name));
            }

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(JBUI.size(350, 200));

            JBScrollPane scrollPane = new JBScrollPane(checkBoxList);
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        @NotNull
        List<String> getSelectedNames() {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < checkBoxList.getItemsCount(); i++) {
                if (checkBoxList.isItemSelected(i)) {
                    String item = checkBoxList.getItemAt(i);
                    if (item != null) {
                        result.add(item);
                    }
                }
            }
            return result;
        }
    }
}
