package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ContextPathUtils {

    private ContextPathUtils() {}

    @NotNull
    public static String generateContextPath(@NotNull String artifactName) {
        String context = artifactName
                .replaceAll(":(war|jar)(\\s+exploded)?$", "")
                .replaceAll("\\.(war|jar)$", "")
                .replaceAll("[-_]?exploded$", "");

        if (context.equalsIgnoreCase("ROOT") ||
                context.equalsIgnoreCase("root.war")) {
            return "/";
        }

        context = context.replaceAll("-\\d+(\\.\\d+)*(-SNAPSHOT)?$", "");
        context = context
                .replaceAll("[^a-zA-Z0-9\\-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();

        if (context.isEmpty() || context.matches("-+")) {
            return "/";
        }

        return "/" + context;
    }

    @NotNull
    public static String normalizeContextPath(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/";
        }

        path = path.trim();

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        path = path.replaceAll("/+", "/");

        return path;
    }

    public static boolean isValidContextPath(@Nullable String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }

        if (!context.equals("/") && !context.startsWith("/")) {
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
