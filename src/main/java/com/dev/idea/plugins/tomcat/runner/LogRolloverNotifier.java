package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Fires a balloon at each local-midnight crossing while Tomcat is running so
 * the user is warned that Tomcat's dated log files (e.g. {@code catalina.YYYY-MM-DD.log})
 * have rotated to a new day.
 *
 * <p><b>Why this exists.</b> {@link com.intellij.diagnostic.logging.LogConsoleImpl}
 * captures the resolved log path at descriptor build time and never refreshes
 * it. {@link TomcatRunConfiguration#getAllLogFiles()} can return the new
 * day's path when called, but the platform does not call it again after the
 * Run tool window is built. So at midnight Tomcat starts writing
 * {@code catalina.<tomorrow>.log} while the existing Log tab keeps tailing
 * {@code catalina.<yesterday>.log}, which silently goes idle. From the user's
 * perspective the logs "stopped".
 *
 * <p>This notifier turns that silent failure into a one-line balloon at
 * 00:00 with a concrete action (close + reopen the affected Log tab, or
 * restart the launch). The alarm reschedules itself, so a multi-day run
 * gets one balloon per midnight crossed.
 *
 * <p>Lifecycle: instances are tied to a single Tomcat launch.
 * {@link com.intellij.openapi.util.Disposer#dispose} cancels the pending
 * alarm and the next firing is skipped. {@link TomcatProcessHandler}
 * disposes its notifier from {@code processTerminated} so a stopped run
 * never produces a stale balloon.
 *
 * <p>The alarm uses {@link Alarm.ThreadToUse#SWING_THREAD} because the
 * balloon ultimately notifies via {@link TomcatNotifier} which posts to the
 * IntelliJ Notification bus — the bus expects EDT delivery on most
 * platform versions.
 */
public final class LogRolloverNotifier implements Disposable {

    private final Project project;
    private final Alarm alarm;
    private volatile boolean disposed;

    public LogRolloverNotifier(@NotNull Project project) {
        this.project = project;
        // Alarm is parented on `this`; Disposer.dispose(notifier) cancels
        // pending requests automatically.
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        scheduleNext();
    }

    private void scheduleNext() {
        if (disposed) return;
        long delay = millisUntilNextMidnight();
        // Clamp to a positive value — Alarm.addRequest with a non-positive
        // delay can fire immediately on some platform versions, which would
        // spam the balloon at startup on the edge case where the system
        // clock is exactly 00:00:00.000.
        if (delay < 1_000L) delay = 1_000L;
        alarm.addRequest(this::onMidnight, delay);
    }

    private void onMidnight() {
        if (disposed) return;
        if (!project.isDisposed()) {
            // Short balloon — full explanation lived here for one user-
            // education moment, but a one-line cue is enough.
            TomcatNotifier.warning(project,
                    "Log files rotated",
                    "Reopen Log tabs to tail today's file.");
        }
        scheduleNext();
    }

    /**
     * Returns the number of milliseconds from {@link LocalDateTime#now()} to
     * the next local midnight (start of tomorrow). Always positive.
     * Package-private for unit testability.
     */
    static long millisUntilNextMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, nextMidnight).toMillis();
    }

    @Override
    public void dispose() {
        disposed = true;
        // alarm.dispose() is invoked transitively because we passed `this`
        // as its parent disposable. No explicit cancel needed.
    }
}
