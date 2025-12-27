package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Base Utility Class for DevTomcat Plugin
 *
 * Provides common functionality for all utility classes:
 * - Consistent logging patterns
 * - Null-safety validation
 * - Registry access patterns
 * - Error handling patterns
 *
 * <p>Usage: Extend this class in your utility classes to inherit common patterns
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public abstract class BasePluginUtil {

    /**
     * Get logger for the utility class.
     *
     * <p>Subclasses should override to provide their own logger:
     * ```
     * @Override
     * protected Logger getLogger() {
     *     return Logger.getInstance(MyUtilClass.class);
     * }
     * ```
     *
     * @return the logger instance (never null)
     */
    @NotNull
    protected abstract Logger getLogger();

    /**
     * Validate that object is not null, log and throw if violated.
     *
     * @param obj the object to check (can be null)
     * @param message the error message (cannot be null)
     * @throws NullPointerException if obj is null
     */
    protected void requireNotNull(@Nullable Object obj, @NotNull String message) {
        Objects.requireNonNull(message, "Error message cannot be null");

        if (obj == null) {
            getLogger().error(message);
            throw new NullPointerException(message);
        }
    }

    /**
     * Log info message with consistent formatting.
     *
     * @param message the message (cannot be null)
     */
    protected void logInfo(@NotNull String message) {
        Objects.requireNonNull(message, "Message cannot be null");
        getLogger().info(message);
    }

    /**
     * Log debug message with consistent formatting.
     *
     * @param message the message (cannot be null)
     */
    protected void logDebug(@NotNull String message) {
        Objects.requireNonNull(message, "Message cannot be null");
        getLogger().debug(message);
    }

    /**
     * Log warning message with consistent formatting.
     *
     * @param message the message (cannot be null)
     */
    protected void logWarning(@NotNull String message) {
        Objects.requireNonNull(message, "Message cannot be null");
        getLogger().warn(message);
    }

    /**
     * Log warning with exception.
     *
     * @param message the message (cannot be null)
     * @param throwable the exception (cannot be null)
     */
    protected void logWarning(@NotNull String message, @NotNull Throwable throwable) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(throwable, "Throwable cannot be null");
        getLogger().warn(message, throwable);
    }

    /**
     * Log error message.
     *
     * @param message the message (cannot be null)
     */
    protected void logError(@NotNull String message) {
        Objects.requireNonNull(message, "Message cannot be null");
        getLogger().error(message);
    }

    /**
     * Log error with exception.
     *
     * @param message the message (cannot be null)
     * @param throwable the exception (cannot be null)
     */
    protected void logError(@NotNull String message, @NotNull Throwable throwable) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(throwable, "Throwable cannot be null");
        getLogger().error(message, throwable);
    }

    /**
     * Safe string conversion that never returns null.
     *
     * @param value the value to convert (can be null)
     * @return the string value or "null"
     */
    @NotNull
    protected String safeString(@Nullable Object value) {
        return value != null ? value.toString() : "null";
    }

    /**
     * Check if string is empty or null.
     *
     * @param str the string to check (can be null)
     * @return true if empty or null
     */
    protected boolean isEmpty(@Nullable String str) {
        return StringUtil.isEmpty(str);
    }

    /**
     * Check if string is not empty.
     *
     * @param str the string to check (can be null)
     * @return true if not empty
     */
    protected boolean isNotEmpty(@Nullable String str) {
        return !StringUtil.isEmpty(str);
    }
}