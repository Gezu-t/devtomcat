package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * String helpers for the DevTomcat plugin.
 *
 * <h2>Why this class exists</h2>
 * IntelliJ's {@link com.intellij.openapi.util.text.StringUtil#notNullize(String, String)}
 * is a frequent footgun: it returns the supplied default <i>only when the input
 * is {@code null}</i>. An <i>empty</i> input slips through unchanged.
 *
 * <pre>{@code
 * StringUtil.notNullize(null, "/");  // returns "/"
 * StringUtil.notNullize("",   "/");  // returns ""   ← SURPRISE
 * StringUtil.notNullize("  ", "/");  // returns "  " ← SURPRISE
 * }</pre>
 *
 * <p>Across this codebase the pattern <code>notNullize(value, DEFAULT)</code>
 * had been used in 11 places to express "give me {@code value} or, when it is
 * effectively absent, {@code DEFAULT}". In every one of those places, an
 * empty or whitespace-only stored value silently violated the invariant the
 * default was meant to enforce — for example a serialized empty
 * {@code jreSelection} survived round-tripping as {@code ""}, which then
 * failed the downstream {@code !jreSelection.isEmpty()} guard with no clear
 * signal of where the bad value entered the system.
 *
 * <p>{@link #defaultIfBlank} replaces those usages with a single,
 * unambiguous semantics: <b>null, empty, or whitespace-only ⇒ default;
 * anything else ⇒ value.</b>
 */
public final class TomcatStrings {

    private TomcatStrings() {
        // static utility only
    }

    /**
     * Return {@code value} when it is non-null and not blank; otherwise
     * return {@code defaultValue}. "Blank" follows {@link String#isBlank()} —
     * empty strings and strings that contain only whitespace both fall
     * through to the default.
     *
     * <p>Use this in preference to {@link com.intellij.openapi.util.text.StringUtil#notNullize(String, String)}
     * whenever an empty/whitespace value is semantically equivalent to "no
     * value supplied" and the caller wants the configured default. See class
     * Javadoc for the trap this method exists to close.
     *
     * @param value        the candidate value, possibly null/empty/whitespace.
     * @param defaultValue the value to return when {@code value} is blank;
     *                     must itself be non-null and non-blank — supplying
     *                     a blank default defeats the purpose.
     * @return {@code value} if it has at least one non-whitespace character,
     *         else {@code defaultValue}.
     */
    @NotNull
    public static String defaultIfBlank(@Nullable String value, @NotNull String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
