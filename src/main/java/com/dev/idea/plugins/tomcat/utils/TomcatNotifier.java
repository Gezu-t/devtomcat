package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single source for DevTomcat balloon notifications.
 *
 * <p>All callers that previously embedded a {@code NotificationGroupManager} chain
 * inside a try-catch now delegate here. Failures are swallowed silently because a
 * missing notification must never crash a running operation.
 *
 * <p><b>Threading.</b> The IntelliJ Platform's {@code Notification.notify(project)}
 * is documented as safe to call from any thread, but on Windows we observed the
 * IDE main window briefly losing focus and re-appearing whenever a notification
 * was emitted from a background thread (process output reader, BeforeRunTask
 * executor, launch-initiation thread). The interaction is between the
 * notification's owner-window placement and Windows focus-stealing prevention.
 * The platform-side fix is to always dispatch the {@code notify()} call onto
 * the EDT. Every entry point in this class wraps its body in
 * {@code ApplicationManager.getApplication().invokeLater(Runnable)} — the
 * same shape used elsewhere in this plugin (TomcatConfigurationEditor,
 * ServerConfigurationTab, JreConfigurationSection). No explicit
 * {@code ModalityState}: the platform's {@code NotificationGroupManager}
 * already handles modal-dialog interactions for balloons, so we let the
 * default capture-current-modality apply rather than over-specifying with
 * {@code ModalityState.any()}, which the platform docs say to use sparingly.
 *
 * <p>The disposed-project check is performed twice — once before scheduling
 * and once inside the runnable — because a project can transition to disposed
 * in the window between {@code invokeLater} and EDT pickup.
 */
public final class TomcatNotifier {

    private static final Logger LOG = Logger.getInstance(TomcatNotifier.class);

    private TomcatNotifier() {}

    public static void error(@NotNull Project project,
                             @NotNull String title,
                             @NotNull String content) {
        notify(project, title, content, NotificationType.ERROR);
    }

    public static void warning(@NotNull Project project,
                               @NotNull String title,
                               @NotNull String content) {
        notify(project, title, content, NotificationType.WARNING);
    }

    public static void info(@NotNull Project project,
                            @NotNull String title,
                            @NotNull String content) {
        notify(project, title, content, NotificationType.INFORMATION);
    }

    public static void notify(@NotNull Project project,
                               @NotNull String title,
                               @NotNull String content,
                               @NotNull NotificationType type) {
        postOnEdt(project, title, () -> NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project));
    }

    /**
     * Shared posting scaffold: skip when the project is disposed (posting to a
     * disposed project produces an AssertionError on some 2025.x builds — not
     * actionable, just noise on shutdown paths that race the close), hop to the
     * EDT, re-check disposal there (the window between {@code invokeLater} and
     * EDT pickup), rethrow PCE unchanged, and debug-log any other failure —
     * a balloon that could not be shown must never break its caller.
     */
    private static void postOnEdt(@NotNull Project project,
                                  @NotNull String title,
                                  @NotNull Runnable post) {
        if (project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                post.run();
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                throw pce;
            } catch (Exception e) {
                LOG.debug("Could not show notification '" + title + "': " + e.getMessage());
            }
        });
    }

    /**
     * Convenience wrapper around {@link #notifyWithAction} for ERROR-level
     * balloons that direct the user to a specific fix-up action.
     */
    public static void errorWithAction(@NotNull Project project,
                                       @NotNull String title,
                                       @NotNull String content,
                                       @NotNull String actionLabel,
                                       @NotNull Runnable action) {
        notifyWithAction(project, title, content, NotificationType.ERROR, actionLabel, action);
    }

    /**
     * Pops a balloon with a single clickable action button.
     *
     * <p>Used by the diagnostic balloon router to surface actionable Tomcat
     * failures (port-in-use, missing class, OOM, JRE mismatch) with a one-click
     * path back to the Run Configuration editor. The notification auto-expires
     * after the action runs so it does not linger as a stale "Open …" prompt.
     */
    public static void notifyWithAction(@NotNull Project project,
                                        @NotNull String title,
                                        @NotNull String content,
                                        @NotNull NotificationType type,
                                        @NotNull String actionLabel,
                                        @NotNull Runnable action) {
        notifyWithActions(project, title, content, type, actionLabel, action, null, null);
    }

    /**
     * Pops a balloon with one or two clickable action buttons, in declaration
     * order. The second pair may be {@code null} to show a single action.
     * Clicking either button expires the notification — the two actions are
     * alternative resolutions of the same condition, so once one runs the
     * other's prompt is stale.
     */
    public static void notifyWithActions(@NotNull Project project,
                                         @NotNull String title,
                                         @NotNull String content,
                                         @NotNull NotificationType type,
                                         @NotNull String actionLabel,
                                         @NotNull Runnable action,
                                         @Nullable String secondActionLabel,
                                         @Nullable Runnable secondAction) {
        postOnEdt(project, title, () -> {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type);
            notification.addAction(expiringAction(actionLabel, action));
            if (secondActionLabel != null && secondAction != null) {
                notification.addAction(expiringAction(secondActionLabel, secondAction));
            }
            notification.notify(project);
        });
    }

    @NotNull
    private static NotificationAction expiringAction(@NotNull String label, @NotNull Runnable action) {
        return new NotificationAction(label) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                try {
                    action.run();
                } finally {
                    n.expire();
                }
            }
        };
    }
}
