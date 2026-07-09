package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.diagnostics.ArtifactStructureValidator;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import java.nio.file.Path;
import com.intellij.execution.BeforeRunTaskProvider;
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
 * Provides the "Verify N artifact(s)" entry in the Before Launch panel for
 * DevTomcat run configurations.
 *
 * <p>When the project has platform-registered IntelliJ Artifacts, the platform's
 * built-in {@code BuildArtifactsBeforeRunTask} handles building them and is added by
 * {@link TomcatRunConfiguration#syncBeforeLaunchWithDeployments()}. This provider
 * complements it (when no Artifacts are configured) or coexists alongside it
 * (when some are):
 *
 * <ul>
 *   <li>Displays the configured artifact names so the user can see at a glance what
 *       will be deployed before the launch begins.</li>
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
        // Reads "Verify" not "Build" because executeTask validates path existence
        // and structure; the actual class/WAR build comes from the "Build" (Make)
        // step or a user-added Maven/Gradle goal earlier in the Before Launch chain.
        return "Verify DevTomcat artifacts";
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
            return "Verify artifacts";
        }
        if (names.size() == 1) {
            return "Verify '" + names.get(0) + "'";
        }
        return "Verify " + names.size() + " artifacts";
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

        List<Deployment> deployments = tomcatConfig.getDeployments();

        // Honor the per-artifact selection persisted on the task by the
        // "Select Artifacts" dialog (configureTask). An empty selection means
        // "verify all", matching the dialog's pre-check-all default — an artifact
        // the user explicitly unchecked must not block the launch.
        Set<String> selected = new HashSet<>(task.getArtifactNames());
        if (!selected.isEmpty()) {
            deployments = deployments.stream()
                    .filter(d -> selected.contains(d.getDisplayName()))
                    .toList();
        }

        boolean allValid = true;
        StringBuilder missing = new StringBuilder();
        for (Deployment d : deployments) {
            if (!d.isValid()) {
                Path p = d.getResolvedPath();
                String message = "Artifact not ready: '" + d.getDisplayName()
                        + "' at " + (p != null ? p : "(unresolved)");
                LOG.warn("DevTomcat: " + message);
                if (missing.length() > 0) missing.append("\n");
                missing.append("• ").append(d.getDisplayName());
                allValid = false;
            }
        }
        if (!allValid) {
            // Name the artifacts that are not ready so the balloon is actionable
            // (the accumulated bullet list), then say what to do next.
            TomcatNotifier.error(tomcatConfig.getProject(),
                    "Artifacts not ready",
                    missing + "\nBuild the project, then launch again.");
            return false;
        }

        // Structure validation — the path exists but is it actually a deployable
        // webapp? Catches the most common missing-JAR/incomplete-build cases:
        //   • exploded artifact with no WEB-INF/      (build never completed)
        //   • exploded artifact with empty classes/   (Make step broken)
        //   • WAR artifact path that is a directory   (type / path mismatch)
        // Tomcat would fail on any of these but with a confusing log message
        // 10 seconds into startup. Blocking errors keep their balloon (one
        // short line + console for the file list). Soft warnings ("partially
        // populated dir") used to balloon too but were the worst offenders
        // for repeat-noise on every launch — those now stay in the console.
        ArtifactStructureValidator.Result structure = ArtifactStructureValidator.validate(deployments);
        if (structure.hasBlockingErrors()) {
            TomcatNotifier.error(tomcatConfig.getProject(),
                    "Artifact structure invalid",
                    "See run console for details.");
            // Full per-artifact details in the console so the user has the
            // signal without the balloon body bloat.
            for (String err : structure.blockingErrors()) {
                LOG.warn("DevTomcat artifact structure: " + err);
            }
            return false;
        }
        if (structure.hasWarnings()) {
            // Console-only — non-blocking and repeats every launch otherwise.
            for (String warn : structure.warnings()) {
                LOG.info("DevTomcat artifact may be incomplete: " + warn);
            }
        }

        // Staleness check removed in 1.1.0 — DeployedClassesSync now mirrors
        // fresh module output into WEB-INF/classes/ on every launch (initial
        // Run, Stop+Run, cross-executor switch, Restart relaunch). The "your
        // artifact is stale, rebuild it" balloon used to fire on every launch
        // in a development workflow where Maven hadn't repackaged recently —
        // pure noise once the sync makes the deployed artifact fresh by the
        // time Tomcat reads it. Keeping the validation gates above (missing
        // path, broken structure) because those are still real blockers.
        return true;
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

        List<Deployment> allDeployments = tomcatConfig.getDeployments();
        if (allDeployments.isEmpty()) {
            promise.setResult(false);
            return promise;
        }

        Project project = configuration.getProject();
        Set<String> selected = new HashSet<>(task.getArtifactNames());

        SelectArtifactsDialog dialog = new SelectArtifactsDialog(project, allDeployments, selected);
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
                              @NotNull List<Deployment> deployments,
                              @NotNull Set<String> preSelected) {
            super(project, false);
            setTitle("Select Artifacts");

            checkBoxList = new CheckBoxList<>();
            for (Deployment d : deployments) {
                String name = d.getDisplayName();
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
