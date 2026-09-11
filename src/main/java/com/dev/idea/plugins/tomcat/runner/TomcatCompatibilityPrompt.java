package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
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
        // Use the inline "<name> <version>" form when the install carries the
        // default "Tomcat" / "Apache Tomcat" label (or has no label at all).
        // The parenthesised form only reads naturally when the user has
        // assigned a custom install name like "Production Tomcat", where
        // the version is genuinely an aside. With a generic label the
        // parens make the version look auxiliary instead of the subject —
        // "Tomcat (7.0.30.0) reached end-of-life" reads worse than
        // "Tomcat 7.0.30.0 reached end-of-life".
        String name = tomcatInfo.getName();
        String version = tomcatInfo.getVersion();
        String displayName;
        if (name.isEmpty()
                || name.equalsIgnoreCase("Tomcat")
                || name.equalsIgnoreCase("Apache Tomcat")) {
            displayName = "Tomcat " + version;
        } else {
            displayName = name + " (" + version + ")";
        }

        // Short balloon — the "click here for upgrade options" action covers
        // the recommendation, so the body just states the fact.
        String content = displayName + " is end-of-life"
                + (eolDate != null ? " (" + eolDate + ")" : "")
                + ". No more security updates.";

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                        "Tomcat end-of-life",
                        content,
                        NotificationType.WARNING);
        notification.addAction(new OpenWhichVersionPageAction());
        notification.notify(project);
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

        // Short balloon — the two actions are the call-to-action, the
        // <b>click here…</b> prose used to bloat the body for no benefit.
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                        "JDK does not match Tomcat",
                        issueMessage,
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
                    TomcatProgress.rethrowIfControlFlow(t);
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
                    TomcatProgress.rethrowIfControlFlow(t);
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
