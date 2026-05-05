package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Surfaces the ECJ-version-mismatch warning as an actionable balloon
 * notification with a one-click swap. The deployment-logger warning fires
 * regardless (it's the in-console diagnostic); this prompt is the
 * persistent IDE-notification companion that the user can act on.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #show} posts the notification with three actions: "Swap ECJ
 *       JAR...", "Open Tomcat lib folder", and the platform's standard
 *       "Don't show again" entry through the notification's expire link.</li>
 *   <li>The Swap action presents a modal confirmation dialog naming the
 *       Maven Central download URL, the SHA-1 verification step, and
 *       the destination paths so the user knows exactly what changes.</li>
 *   <li>On confirm, a {@link Task.Backgroundable} runs
 *       {@link EcjJarSwapper#execute} with progress reporting.</li>
 *   <li>Success and failure each post a follow-up balloon describing
 *       what happened and (on success) prompting the user to restart
 *       Tomcat for the swap to take effect.</li>
 * </ol>
 *
 * <p>The class never blocks the launch path. It's invoked from
 * {@link LocalDeploymentStrategy#configureDeployment} after the version
 * check returns a mismatch; the JVM continues to start with the old ECJ.
 * The user takes the swap action when convenient, and the next launch
 * picks up the new JAR.
 */
final class EcjJarSwapPrompt {

    private static final Logger LOG = Logger.getInstance(EcjJarSwapPrompt.class);

    private EcjJarSwapPrompt() {}

    /**
     * Posts the ECJ swap notification for the given mismatch. Safe to call
     * from any thread; the actual notification is posted via
     * {@link Notification#notify} which dispatches to the EDT.
     *
     * <p>{@code project} may be null on rare execution paths that don't have
     * a project handle (e.g. headless tests); in that case the notification
     * is suppressed because IntelliJ's notification API requires a project
     * for the balloon to surface in the right tool window.
     */
    static void show(@org.jetbrains.annotations.Nullable Project project,
                     @NotNull EcjVersionCompat.Mismatch mismatch) {
        if (project == null || project.isDisposed()) return;
        if (mismatch.ecj() == null) return;

        EcjVersionCompat.EcjBundle ecj = mismatch.ecj();
        EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(ecj.jarPath());

        String title = "DevTomcat: ECJ JAR is too old for this webapp";
        int actualJava = EcjVersionCompat.javaVersionFor(mismatch.actualClassFileMajor());
        int ecjMaxJava = EcjVersionCompat.javaVersionFor(ecj.maxClassFileMajor());
        String content = "Tomcat's bundled <code>" + ecj.jarPath().getFileName()
                + "</code> (ECJ " + ecj.version() + ") supports up to Java " + ecjMaxJava
                + ", but the webapp contains Java " + actualJava + " classes. "
                + "JSP compilation will fail at request time. "
                + "DevTomcat can swap in <code>ecj-" + plan.targetVersion()
                + ".jar</code> from Maven Central.";

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content, NotificationType.WARNING);

        notification.addAction(new SwapAction(plan));
        notification.addAction(new OpenLibFolderAction(ecj.jarPath().getParent()));

        notification.notify(project);
    }

    // ------------------------------------------------------------------ //
    // Actions
    // ------------------------------------------------------------------ //

    /** "Swap ECJ JAR..." action. Confirms with the user, then runs the swap on a background task. */
    private static final class SwapAction extends NotificationAction {
        private final EcjJarSwapper.SwapPlan plan;

        SwapAction(@NotNull EcjJarSwapper.SwapPlan plan) {
            super("Swap ECJ JAR...");
            this.plan = plan;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event,
                                    @NotNull Notification notification) {
            Project project = event.getProject();
            if (project == null || project.isDisposed()) return;

            String confirmation = "DevTomcat will:\n\n"
                    + " 1. Download " + plan.targetEcjJar().getFileName()
                    + " from " + plan.downloadUrl() + "\n"
                    + " 2. Verify SHA-1 against " + plan.sha1Url() + "\n"
                    + " 3. Move " + plan.currentEcjJar().getFileName()
                    + " to " + plan.backupPath().getFileName() + " (in place, atomic)\n"
                    + " 4. Move the new JAR into " + plan.currentEcjJar().getParent() + "\n\n"
                    + "This modifies your Tomcat installation. The original JAR is preserved\n"
                    + "as a backup so you can restore it manually if needed. Proceed?";
            int answer = Messages.showOkCancelDialog(project,
                    confirmation,
                    "Swap ECJ JAR in Tomcat install?",
                    "Swap", "Cancel",
                    Messages.getQuestionIcon());
            if (answer != Messages.OK) return;

            // Expire the original notification so the user doesn't click it
            // again while the swap is running. A success/failure balloon
            // takes its place when the task completes.
            notification.expire();

            ProgressManager.getInstance().run(
                    new SwapTask(project, plan, EcjJarSwapper.Downloader.realNetwork()));
        }
    }

    /** "Open Tomcat lib folder" action for users who prefer a manual swap. */
    private static final class OpenLibFolderAction extends NotificationAction {
        private final java.nio.file.Path libDir;

        OpenLibFolderAction(@NotNull java.nio.file.Path libDir) {
            super("Open Tomcat lib folder");
            this.libDir = libDir;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event,
                                    @NotNull Notification notification) {
            try {
                // Reveal the directory in the host OS file manager
                // (Finder on macOS, Explorer on Windows, file manager on
                // Linux). Lets the user perform the swap manually if they
                // prefer; the "Swap" action is for the automated path.
                com.intellij.ide.actions.RevealFileAction.openFile(libDir);
            } catch (Throwable t) {
                LOG.debug("Could not open lib folder " + libDir, t);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Background task
    // ------------------------------------------------------------------ //

    /**
     * Runs the actual download + swap on a background thread with a cancelable
     * progress indicator. Posts a follow-up notification on completion.
     */
    private static final class SwapTask extends Task.Backgroundable {
        private final EcjJarSwapper.SwapPlan plan;
        private final EcjJarSwapper.Downloader downloader;
        private volatile EcjJarSwapper.SwapResult result;

        SwapTask(@NotNull Project project,
                 @NotNull EcjJarSwapper.SwapPlan plan,
                 @NotNull EcjJarSwapper.Downloader downloader) {
            super(project, "Swapping ECJ JAR (" + plan.targetVersion() + ")", true);
            this.plan = plan;
            this.downloader = downloader;
        }

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            result = EcjJarSwapper.execute(plan, downloader, indicator);
        }

        @Override
        public void onFinished() {
            Project project = getProject();
            if (project == null || project.isDisposed() || result == null) return;
            EcjJarSwapper.SwapResult r = result;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (r.isSuccess()) {
                    Notification n = NotificationGroupManager.getInstance()
                            .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                    "DevTomcat: ECJ JAR swapped",
                                    "New JAR at <code>" + r.newJarPath() + "</code>. "
                                            + "Backup at <code>" + r.backupPath() + "</code>. "
                                            + "Restart Tomcat for the swap to take effect.",
                                    NotificationType.INFORMATION);
                    n.notify(project);
                } else {
                    Notification n = NotificationGroupManager.getInstance()
                            .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                    "DevTomcat: ECJ swap failed",
                                    r.errorMessage() != null
                                            ? r.errorMessage()
                                            : "Unknown error during ECJ swap.",
                                    NotificationType.ERROR);
                    n.notify(project);
                }
            });
        }
    }
}
