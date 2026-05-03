package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.Executor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single source for the stop-clean-relaunch pattern used when switching
 * executor modes or restarting a running Tomcat server.
 *
 * <p>Three call sites (TomcatRunnerDelegate, DebugTomcatAction,
 * TomcatApplicationUpdater) all follow the same sequence:
 * <ol>
 *   <li>Capture the descriptor and executor <em>before</em> destroying the process
 *       (querying after is a race — the entry may already be deregistered).</li>
 *   <li>Register a {@link ProcessListener} on the handler.</li>
 *   <li>Call {@link ProcessHandler#destroyProcess()}.</li>
 *   <li>When {@code processTerminated} fires, clean up the old descriptor on the EDT
 *       then run the caller's callback.</li>
 * </ol>
 *
 * <p>This class owns steps 2-4. The caller captures the descriptor (step 1) via
 * {@link #findDescriptor} and provides the post-cleanup callback.
 */
public final class ProcessStopSupport {

    private static final Logger LOG = Logger.getInstance(ProcessStopSupport.class);

    private ProcessStopSupport() {}

    /**
     * Finds the {@link RunContentDescriptor} whose process handler is {@code handler}.
     *
     * <p>Must be called <em>before</em> {@link ProcessHandler#destroyProcess()} —
     * the descriptor may already be deregistered by the time
     * {@code processTerminated} fires.
     *
     * @return the matching descriptor, or null if none is found
     */
    @Nullable
    public static RunContentDescriptor findDescriptor(@NotNull Project project,
                                                       @NotNull ProcessHandler handler) {
        for (RunContentDescriptor d : RunContentManager.getInstance(project).getAllDescriptors()) {
            if (d.getProcessHandler() == handler) return d;
        }
        return null;
    }

    /**
     * Stops a process, removes its descriptor from the Run/Services UI, then
     * runs {@code onCleanedUp} on the EDT.
     *
     * <p>The caller is responsible for capturing {@code descriptor} and
     * {@code executor} <em>before</em> this call — both are needed to remove
     * the stale entry from {@link RunContentManager}. If either is null the
     * cleanup step is skipped (the IDE will GC the entry eventually).
     *
     * @param project      the current project (disposal check is performed)
     * @param handler      the process to stop
     * @param descriptor   the descriptor to remove (may be null)
     * @param executor     the executor the descriptor was registered under (may be null)
     * @param onCleanedUp  callback invoked on the EDT after cleanup; the caller typically
     *                     re-launches the configuration here
     */
    public static void stopCleanAndThen(@NotNull Project project,
                                         @NotNull ProcessHandler handler,
                                         @Nullable RunContentDescriptor descriptor,
                                         @Nullable Executor executor,
                                         @NotNull Runnable onCleanedUp) {
        // Caller must have verified !isProcessTerminated() — a listener attached
        // after processTerminated has already fired will never be invoked and the
        // callback would be lost.
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                handler.removeProcessListener(this);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    try {
                        if (descriptor != null && executor != null) {
                            RunContentManager.getInstance(project)
                                    .removeRunContent(executor, descriptor);
                        }
                    } catch (Exception e) {
                        // Descriptor cleanup is best-effort — the IDE will GC the entry
                        // eventually. Never let a cleanup failure block the relaunch.
                        LOG.debug("Failed to remove old run content: " + e.getMessage());
                    }
                    onCleanedUp.run();
                });
            }
        });
        // Skip if already terminating — the listener above still fires when the in-flight
        // termination completes, so the relaunch sequencing works either way.
        // Dispatch off-EDT: destroyProcess fires processWillTerminate synchronously, and
        // the platform's debugger listener calls runProcessWithProgressSynchronously
        // which IntelliJ 2025.1 forbids on EDT (IllegalStateException: ... w/o IW lock).
        if (!handler.isProcessTerminating()) {
            ApplicationManager.getApplication().executeOnPooledThread(handler::destroyProcess);
        }
    }
}
