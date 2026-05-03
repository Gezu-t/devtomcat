package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
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
}
