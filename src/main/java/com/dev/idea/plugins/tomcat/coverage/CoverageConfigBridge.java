package com.dev.idea.plugins.tomcat.coverage;

import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.ui.classFilter.ClassFilter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure translation between DevTomcat's {@link CoverageConfig} (user-facing
 * include/exclude string patterns) and IntelliJ's {@link ClassFilter} array
 * shape that {@code JavaCoverageEnabledConfiguration.setCoveragePatterns}
 * expects.
 *
 * <p>All methods are deliberately static and side-effect-free so the bridge
 * logic can be unit-tested without instantiating any IntelliJ runtime types.
 * Callers (agent attacher, tests) do the plumbing; this class decides only
 * <em>what</em> the patterns should look like, not <em>where</em> they flow.
 *
 * <p>Pattern hygiene:
 * <ul>
 *   <li>Null, blank, and duplicate entries are dropped — a stray empty entry
 *       in the include array would match everything, silently inverting the
 *       user's intent.</li>
 *   <li>Every produced filter is marked {@code ENABLED=true}. The platform
 *       also honours the {@code INCLUDE} flag to separate the two lists when
 *       they travel as a single array, so includes and excludes round-trip
 *       correctly through the same {@code ClassFilter[]}.</li>
 * </ul>
 */
public final class CoverageConfigBridge {

    private CoverageConfigBridge() {}

    /**
     * Converts the user-configured include/exclude pattern strings into the
     * single {@link ClassFilter} array shape IntelliJ expects.
     *
     * @return deduplicated, trimmed, enabled filters; never null and safe to
     *         pass to {@code setCoveragePatterns} directly
     */
    @NotNull
    public static ClassFilter[] toClassFilters(@NotNull CoverageConfig config) {
        List<ClassFilter> result = new ArrayList<>();
        for (String raw : dedupeTrimmed(config.getIncludePatterns())) {
            result.add(makeFilter(raw, true));
        }
        for (String raw : dedupeTrimmed(config.getExcludePatterns())) {
            result.add(makeFilter(raw, false));
        }
        return result.toArray(new ClassFilter[0]);
    }

    /**
     * Extracts only the include-pattern strings from a {@link ClassFilter}
     * array — the shape {@code JavaCoverageRunner.appendCoverageArgument}
     * accepts directly. Mirrors the platform's own
     * {@code JavaCoverageEnabledConfiguration.getPatterns()} method so that
     * agent-injection callers don't need a {@code JavaCoverageEnabledConfiguration}
     * instance just to read the strings back out.
     */
    @NotNull
    public static String[] includePatternStrings(@NotNull ClassFilter[] filters) {
        return Arrays.stream(filters)
                .filter(f -> f != null && f.isEnabled() && f.isInclude())
                .map(ClassFilter::getPattern)
                .filter(p -> p != null && !p.isBlank())
                .toArray(String[]::new);
    }

    /**
     * Extracts only the exclude-pattern strings. Companion of
     * {@link #includePatternStrings(ClassFilter[])}.
     */
    @NotNull
    public static String[] excludePatternStrings(@NotNull ClassFilter[] filters) {
        return Arrays.stream(filters)
                .filter(f -> f != null && f.isEnabled() && !f.isInclude())
                .map(ClassFilter::getPattern)
                .filter(p -> p != null && !p.isBlank())
                .toArray(String[]::new);
    }

    @NotNull
    private static ClassFilter makeFilter(@NotNull String pattern, boolean include) {
        ClassFilter filter = new ClassFilter(pattern);
        filter.setEnabled(true);
        filter.setInclude(include);
        return filter;
    }

    @NotNull
    private static List<String> dedupeTrimmed(@NotNull List<String> input) {
        // LinkedHashSet would preserve order with O(1) dedup but needs an
        // extra collection; this stream pipeline is the same complexity and
        // keeps the trim + blank-filter + dedup as one readable step.
        return input.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

}
