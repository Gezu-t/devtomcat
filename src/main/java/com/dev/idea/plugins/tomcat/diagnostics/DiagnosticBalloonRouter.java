package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.impl.RunDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes actionable {@link TomcatErrorDiagnostics.Diagnostic}s to a balloon
 * notification with a clickable "Open Run Configuration" action.
 *
 * <p>Scoped to a single launch (one router instance per {@code TomcatProcessHandler}).
 * Maintains an internal dedup set keyed by {@code category|message} so cascading
 * log lines that re-emit the same root failure do not pop the balloon multiple
 * times — the user gets exactly one prompt per distinct error, no matter how
 * many "Caused by:" frames Tomcat logs.
 *
 * <p>Only invoked for diagnostics that carry a non-null {@code quickFixId} —
 * the four classes of failure where the user can act immediately by editing
 * one specific section of the run configuration: Port Conflict (FIX_PORT),
 * Missing Class (FIX_CLASSPATH), Out of Memory (FIX_MEMORY), and Java Version
 * Mismatch (FIX_JRE). Diagnostics without a quickFixId stay as console-only
 * text — they are informational, not actionable.
 *
 * <p>The action button opens the Run Configuration editor for the launching
 * configuration. The user navigates from there; we do not deep-link to a
 * specific tab because tab indices are not part of the platform's stable API
 * and would break silently across IDE versions.
 */
public final class DiagnosticBalloonRouter {

    private static final Logger LOG = Logger.getInstance(DiagnosticBalloonRouter.class);

    /**
     * Strategy for emitting the balloon. Production wires this to
     * {@link TomcatNotifier#errorWithAction}; tests inject a capturing impl so
     * dedup behaviour can be verified without standing up the platform's
     * NotificationGroupManager.
     */
    @FunctionalInterface
    public interface Notifier {
        void show(@NotNull Project project,
                  @NotNull String title,
                  @NotNull String content,
                  @NotNull String actionLabel,
                  @NotNull Runnable action);
    }

    private final Project project;
    private final TomcatRunConfiguration configuration;
    private final Notifier notifier;
    private final Set<String> shown = ConcurrentHashMap.newKeySet();

    /** Production constructor — routes through {@link TomcatNotifier#errorWithAction}. */
    public DiagnosticBalloonRouter(@NotNull Project project, @NotNull TomcatRunConfiguration configuration) {
        this(project, configuration, TomcatNotifier::errorWithAction);
    }

    /** Visible for testing — accepts an injected notifier. */
    DiagnosticBalloonRouter(@NotNull Project project,
                            @NotNull TomcatRunConfiguration configuration,
                            @NotNull Notifier notifier) {
        this.project = project;
        this.configuration = configuration;
        this.notifier = notifier;
    }

    /**
     * Routes a single diagnostic. Idempotent per {@code (category, message)}:
     * the second and subsequent invocations with the same key are no-ops.
     */
    public void route(@NotNull TomcatErrorDiagnostics.Diagnostic diagnostic) {
        if (project.isDisposed()) return;
        if (!shown.add(diagnostic.identityKey())) return;

        notifier.show(
                project,
                diagnostic.getCategory() + ": " + diagnostic.getMessage(),
                diagnostic.getSuggestion(),
                "Open Run Configuration",
                this::openConfigEditor);
    }

    /** Visible for testing — how many distinct diagnostics have been shown so far. */
    public int shownCount() {
        return shown.size();
    }

    /**
     * Opens the Run Configuration editor on the launching configuration. The
     * platform's {@code RunDialog.editConfiguration} requires the EDT, so we
     * dispatch there explicitly — the notification action callback can run on
     * a background thread depending on the IDE version.
     */
    private void openConfigEditor() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                RunnerAndConfigurationSettings settings =
                        RunManager.getInstance(project).findSettings(configuration);
                if (settings != null) {
                    RunDialog.editConfiguration(project, settings, "Edit Run Configuration");
                }
            } catch (Throwable t) {
                TomcatProgress.rethrowIfControlFlow(t);
                LOG.debug("Could not open run configuration editor: " + t.getMessage());
            }
        });
    }
}
