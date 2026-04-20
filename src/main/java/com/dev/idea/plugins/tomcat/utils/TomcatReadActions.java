package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.annotations.NotNull;

/**
 * Portable read-action entry points used across the plugin.
 *
 * <p>{@code ReadAction.compute(ThrowableComputable)} is deprecated
 * (scheduled for removal) on IntelliJ 2026.1+. The replacement
 * {@code ReadAction.computeBlocking(...)} does not exist on our minimum
 * supported platform (2024.1), so we delegate to
 * {@link com.intellij.openapi.application.Application#runReadAction} —
 * non-deprecated on every version from 2024.1 through 2026.1+.
 *
 * <p>Centralising the call here keeps the migration a single-file change the
 * next time the platform churns this API.
 */
public final class TomcatReadActions {

    private TomcatReadActions() {}

    /** Runs {@code computable} under a read action and returns its result. */
    public static <T> T compute(@NotNull Computable<T> computable) {
        return ApplicationManager.getApplication().runReadAction(computable);
    }

    /**
     * Runs {@code computable} under a read action, allowing it to throw a
     * checked exception of type {@code E} which is propagated unchanged.
     */
    public static <T, E extends Throwable> T computeThrowing(@NotNull ThrowableComputable<T, E> computable) throws E {
        return ApplicationManager.getApplication().runReadAction(computable);
    }
}
