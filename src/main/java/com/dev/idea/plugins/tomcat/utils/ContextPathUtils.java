package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

public final class ContextPathUtils {

    private ContextPathUtils() {}

    @NotNull
    public static String generateContextPath(@NotNull String artifactName) {
        String context = artifactName
                .replaceAll(":(war|jar)(\\s+exploded)?$", "")
                .replaceAll("\\.(war|jar)$", "")
                .replaceAll("[-_]?exploded$", "");

        if (context.equalsIgnoreCase(ROOT_CONTEXT_NAME) ||
                context.equalsIgnoreCase("root.war")) {
            return DEFAULT_CONTEXT_PATH;
        }

        context = context.replaceAll("-\\d+(\\.\\d+)*(-SNAPSHOT)?$", "");
        context = context
                .replaceAll("[^a-zA-Z0-9\\-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();

        if (context.isEmpty() || context.matches("-+")) {
            return DEFAULT_CONTEXT_PATH;
        }

        return DEFAULT_CONTEXT_PATH + context;
    }

    @NotNull
    public static String normalizeContextPath(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) {
            return DEFAULT_CONTEXT_PATH;
        }

        path = path.trim();

        if (!path.startsWith(DEFAULT_CONTEXT_PATH)) {
            path = DEFAULT_CONTEXT_PATH + path;
        }

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        path = path.replaceAll("/+", "/");

        return path;
    }

    /**
     * Extracts a base module name by stripping common artifact naming suffixes.
     * Used to match deployment names against IntelliJ artifact names across naming conventions.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "webapp-one_war_exploded"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one:war exploded"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one.war"} → {@code "webapp-one"}</li>
     *   <li>{@code "plain-name"} → {@code "plain-name"}</li>
     * </ul>
     */
    @NotNull
    public static String extractBaseModuleName(@Nullable String name) {
        if (name == null || name.isEmpty()) return "";
        String lower = name.toLowerCase();
        String[] suffixes = {"_war_exploded", "_war", ":war exploded", ":war", ".war", " (exploded)"};
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return lower.substring(0, lower.length() - suffix.length());
            }
        }
        return lower;
    }

    public static boolean isValidContextPath(@Nullable String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }

        if (!context.equals(DEFAULT_CONTEXT_PATH) && !context.startsWith(DEFAULT_CONTEXT_PATH)) {
            return false;
        }

        if (context.contains(" ")) {
            return false;
        }

        if (context.contains("//")) {
            return false;
        }

        return context.matches("^/[a-zA-Z0-9\\-_.~!$&'()*+,;=:@/]*$");
    }
}
