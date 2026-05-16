package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
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
        // Posting to a disposed project produces an AssertionError on some 2025.x
        // builds — not actionable, just noise on shutdown paths that race the close.
        if (project.isDisposed()) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type)
                    .notify(project);
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.debug("Could not show notification '" + title + "': " + e.getMessage());
        }
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
        if (project.isDisposed()) return;
        try {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type);
            notification.addAction(new NotificationAction(actionLabel) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    try {
                        action.run();
                    } finally {
                        n.expire();
                    }
                }
            });
            notification.notify(project);
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.debug("Could not show notification with action '" + title + "': " + e.getMessage());
        }
    }
}
