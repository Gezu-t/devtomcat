package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

/**
 * Cooperative progress/cancellation entry points for the deployment pipeline.
 *
 * <p>The pipeline's two heavy surfaces both run under a live, cancelable
 * progress indicator: launch preparation executes under the modal progress the
 * platform runner shows while computing JavaParameters, and the update loop
 * executes under a backgroundable task. Cancellation on both is cooperative —
 * nothing stops until the running code polls for it. The file-tree mirrors
 * used to ignore the indicator entirely, so pressing Cancel appeared to do
 * nothing until a full sync pass finished. These helpers close that gap.
 *
 * <p>Every method is safe in every environment the pipeline runs in:
 * {@link ProgressManager#checkCanceled()} no-ops when no ProgressManager is
 * installed (plain unit tests) or no indicator is attached to the current
 * thread, and the detail setter null-checks the indicator. Single-seam
 * pattern mirrors {@link TomcatReadActions}.
 */
public final class TomcatProgress {

    private TomcatProgress() {}

    /**
     * Throws {@code ProcessCanceledException} if the user canceled the
     * progress this thread runs under; no-op otherwise (including in plain
     * unit tests with no platform present).
     */
    public static void checkCanceled() {
        ProgressManager.checkCanceled();
    }

    /** Call first in a broad catch: rethrows what the platform treats as control flow (cancellation). */
    public static void rethrowIfControlFlow(@NotNull Throwable t) {
        if (!Logger.shouldRethrow(t)) return;
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new IllegalStateException(t);
    }

    /**
     * Shows {@code detail} as the secondary text of the current progress
     * indicator, if one exists.
     */
    public static void setDetail(@NotNull String detail) {
        ProgressIndicator indicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
        if (indicator != null) {
            indicator.setText2(detail);
        }
    }
}
