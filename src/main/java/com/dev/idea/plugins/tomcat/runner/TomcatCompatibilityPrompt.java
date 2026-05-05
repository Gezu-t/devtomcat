package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.impl.EditConfigurationsDialog;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surfaces actionable notifications for two compatibility classes that
 * {@link TomcatCompatibilityChecker} already detects:
 *
 * <ol>
 *   <li><b>Tomcat EOL warning</b> — when the configured Tomcat install
 *       belongs to a branch the Apache Tomcat project no longer supports
 *       (7.x, 8.0.x, 8.5.x, 10.0.x). The user keeps using it without any
 *       active push; the notification recommends upgrading to a supported
 *       branch and links to the Tomcat downloads page.</li>
 *   <li><b>JDK / Tomcat version mismatch</b> — when the configured JRE is
 *       older than the Tomcat version requires. The existing
 *       {@code checkCompatibility} flow already blocks the launch with a
 *       run-console error; this prompt adds a balloon with a one-click
 *       jump to the run-config editor's Server tab where the user picks
 *       a registered JRE.</li>
 * </ol>
 *
 * <p>EOL warnings are deduplicated per IDE session (in-memory keyed by
 * Tomcat install path) so the user does not see the same warning every
 * launch. The notification persists in IntelliJ's notification panel
 * until the user dismisses or acts on it. On IDE restart the warning may
 * appear again on the first launch — by design, so users who delay an
 * upgrade are reminded periodically. Users who want to silence it
 * permanently can mute the {@code DevTomcatNotifications} group via
 * IntelliJ's Settings > Notifications.
 *
 * <p>Both prompts are non-blocking; the launch continues regardless of
 * whether the user clicks the action. The pair completes the user-facing
 * surface for compatibility issues — detection (already in place) plus
 * actionable navigation.
 */
final class TomcatCompatibilityPrompt {

    private static final Logger LOG = Logger.getInstance(TomcatCompatibilityPrompt.class);

    /** URL of the Apache Tomcat "Which Version" landing page. */
    static final String TOMCAT_WHICH_VERSION_URL = "https://tomcat.apache.org/whichversion.html";

    /**
     * In-memory dedup of EOL warnings per IDE session. Key is the Tomcat
     * install path so multiple registered Tomcats each get one warning.
     * Resets on IDE restart, intentionally.
     */
    private static final Set<String> EOL_WARNED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private TomcatCompatibilityPrompt() {}

    /**
     * Shows an EOL-warning balloon for the given Tomcat install if it
     * belongs to an end-of-life branch and we haven't already warned about
     * this install in this IDE session. No-op for supported branches.
     */
    static void showEolWarningOnce(@Nullable Project project,
                                   @Nullable TomcatInfo tomcatInfo) {
        if (project == null || project.isDisposed()) return;
        if (!TomcatCompatibilityChecker.isEndOfLifeTomcat(tomcatInfo)) return;

        String key = tomcatInfo.getPath();
        if (key == null || key.isEmpty()) return;
        if (!EOL_WARNED_THIS_SESSION.add(key)) {
            return; // already warned this session
        }

        String eolDate = TomcatCompatibilityChecker.endOfLifeDateOrNull(tomcatInfo);
        String displayName = !tomcatInfo.getName().isEmpty()
                ? tomcatInfo.getName() + " (" + tomcatInfo.getVersion() + ")"
                : "Tomcat " + tomcatInfo.getVersion();

        String content = displayName + " reached end-of-life "
                + (eolDate != null ? "in " + eolDate : "")
                + " and no longer receives security updates from Apache. "
                + recommendationFor(tomcatInfo)
                + " The current launch continues; this notification will appear once per IDE session.";

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                        "DevTomcat: Tomcat install is end-of-life",
                        content,
                        NotificationType.WARNING);
        notification.addAction(new OpenWhichVersionPageAction());
        notification.notify(project);
    }

    /**
     * Returns a one-line upgrade recommendation tailored to the user's
     * webapp servlet namespace expectations. Tomcat 9 keeps the
     * {@code javax.servlet} namespace and is the natural sweet spot for
     * legacy webapps; Tomcat 10+ moves to {@code jakarta.servlet} and
     * requires a webapp migration.
     */
    @NotNull
    private static String recommendationFor(@NotNull TomcatInfo tomcatInfo) {
        int major = tomcatInfo.getMajorVersion();
        if (major == 7 || major == 8) {
            return "For a javax.servlet webapp, the natural upgrade is Tomcat 9.0.x"
                    + " (still maintained, same namespace, modern bundled ECJ). For a"
                    + " jakarta.servlet webapp, use Tomcat 10.1.x or 11.0.x.";
        }
        if (major == 10) {
            return "Upgrade to Tomcat 10.1.x or 11.0.x. The webapp servlet namespace"
                    + " (jakarta.servlet) does not change.";
        }
        return "Upgrade to a supported branch.";
    }

    /**
     * Shows a JDK-mismatch quick-fix balloon. Pairs with the existing
     * launch-blocking error in {@code TomcatCommandLineState.checkCompatibility}:
     * the user sees the precise error in the run console and a balloon
     * with a one-click jump to the run-config editor where they fix it.
     *
     * <p>Unlike the EOL warning, this is NOT deduplicated per session
     * because it gates an active launch attempt: every blocked launch
     * deserves its own notification so the user has a fresh action to
     * click after fixing the JDK.
     */
    static void showJdkMismatchPrompt(@Nullable Project project,
                                      @NotNull TomcatRunConfiguration configuration,
                                      @NotNull String issueMessage) {
        if (project == null || project.isDisposed()) return;

        String content = issueMessage
                + " Click <b>Open Run Configuration</b> to pick a registered JRE on the Server tab,"
                + " or open <b>File &rarr; Project Structure &rarr; SDKs</b> to register one.";

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                        "DevTomcat: JDK does not match Tomcat requirement",
                        content,
                        NotificationType.ERROR);
        notification.addAction(new OpenRunConfigurationAction(configuration));
        notification.addAction(new OpenSdksSettingsAction());
        notification.notify(project);
    }

    // ------------------------------------------------------------------ //
    // Actions
    // ------------------------------------------------------------------ //

    /** Opens the Apache Tomcat "Which Version" page in the user's browser. */
    private static final class OpenWhichVersionPageAction extends NotificationAction {
        OpenWhichVersionPageAction() { super("Open Tomcat 'Which Version' page"); }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event,
                                    @NotNull Notification notification) {
            try {
                BrowserUtil.browse(TOMCAT_WHICH_VERSION_URL);
            } catch (Throwable t) {
                LOG.debug("Could not open Tomcat downloads page", t);
            }
        }
    }

    /** Opens IntelliJ's "Edit Run Configurations" dialog focused on the given run config. */
    private static final class OpenRunConfigurationAction extends NotificationAction {
        private final TomcatRunConfiguration configuration;

        OpenRunConfigurationAction(@NotNull TomcatRunConfiguration configuration) {
            super("Open Run Configuration");
            this.configuration = configuration;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event,
                                    @NotNull Notification notification) {
            Project project = event.getProject();
            if (project == null || project.isDisposed()) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    // Select the configuration first so the editor opens
                    // focused on it, then show the standard Edit dialog.
                    RunnerAndConfigurationSettings settings =
                            RunManager.getInstance(project).findSettings(configuration);
                    if (settings != null) {
                        RunManager.getInstance(project).setSelectedConfiguration(settings);
                    }
                    new EditConfigurationsDialog(project).show();
                } catch (Throwable t) {
                    LOG.debug("Could not open run-config editor", t);
                }
            });
        }
    }

    /** Opens IntelliJ's Project Structure dialog at the SDKs page. */
    private static final class OpenSdksSettingsAction extends NotificationAction {
        OpenSdksSettingsAction() { super("Open Project Structure (SDKs)"); }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event,
                                    @NotNull Notification notification) {
            Project project = event.getProject();
            if (project == null || project.isDisposed()) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    // showSettingsDialog with no specific configurable opens
                    // the default Project Structure entry point; the user
                    // navigates to SDKs from there. The narrower "directly
                    // open SDKs page" API differs across IntelliJ builds, so
                    // the safe default is the dialog's root.
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "SDKs");
                } catch (Throwable t) {
                    LOG.debug("Could not open SDKs settings", t);
                }
            });
        }
    }

    // ------------------------------------------------------------------ //
    // Test seam
    // ------------------------------------------------------------------ //

    /**
     * Test-only: clears the per-session EOL-dedup cache so a fresh test
     * does not get a stale "already warned" hit from a sibling test.
     */
    static void clearEolDedupForTesting() {
        EOL_WARNED_THIS_SESSION.clear();
    }
}
