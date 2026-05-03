package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

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
        // Locale.ROOT — see TomcatModuleUtils.extractContextPath for the
        // same Turkish-locale 'I'→'ı' bug. An artifact named "WebApi" must
        // produce "/webapi", not "/webap".
        context = context
                .replaceAll("[^a-zA-Z0-9\\-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);

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

        // Collapse before strip — otherwise '//myapp//' → '//myapp/' → '/myapp/' (trailing /).
        path = path.replaceAll("/+", "/");

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    /**
     * Extracts a base module name by stripping common artifact naming suffixes
     * and version patterns. Used to match deployment names against IntelliJ
     * artifact names across naming conventions.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "webapp-one_war_exploded"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one:war exploded"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one.war"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one##5.18.0.war"} → {@code "webapp-one"}</li>
     *   <li>{@code "webapp-one-5.18.0"} → {@code "webapp-one"}</li>
     *   <li>{@code "plain-name"} → {@code "plain-name"}</li>
     * </ul>
     */
    @NotNull
    public static String extractBaseModuleName(@Nullable String name) {
        if (name == null || name.isEmpty()) return "";
        // Locale.ROOT for stable suffix matching across IDE locales — without
        // it, a name like "MyWebApi:war" lowercased in a Turkish locale becomes
        // "mywebapı:war" and the downstream `endsWith(":war")` check still
        // passes, but the returned base "mywebapı" is then compared against
        // module names that may have been lowercased in a different locale,
        // causing false negatives in artifact-to-module matching.
        String lower = name.toLowerCase(Locale.ROOT);
        String[] suffixes = {"_war_exploded", "_war", ":war exploded", ":war", ".war", " (exploded)"};
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                lower = lower.substring(0, lower.length() - suffix.length());
                break;
            }
        }
        // Strip Tomcat parallel deployment version (##version) and Maven/Gradle version suffixes
        // Note: input is already lowercased, so match -snapshot (not -SNAPSHOT)
        lower = lower.replaceAll("##\\d+(\\.\\d+)*(-snapshot)?$", "");
        lower = lower.replaceAll("-\\d+(\\.\\d+)+(-snapshot)?$", "");
        return lower;
    }

    /**
     * Formats an artifact name for display using IntelliJ Ultimate's colon notation.
     * Converts underscore-based naming (e.g. {@code "app_war_exploded"}) to the cleaner
     * colon format (e.g. {@code "app:war exploded"}) that IntelliJ Ultimate uses.
     *
     * <p>If the name already uses colon notation, it is returned as-is.
     * Version patterns like {@code ##5.18.0} are stripped for cleaner display.
     *
     * @param name the raw artifact name
     * @param type the deployment type ({@code "exploded"}, {@code "war"}, or null)
     * @return formatted display name in colon notation
     */
    @NotNull
    public static String formatArtifactDisplayName(@NotNull String name, @Nullable String type) {
        // Already in colon format — return as-is
        if (name.contains(":war") || name.contains(":ear")) {
            return name;
        }

        String baseName = name;
        String resolvedType = type;

        // Strip known suffixes and detect type from the name. Locale.ROOT
        // for the same reason as extractBaseModuleName above.
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_war_exploded")) {
            baseName = name.substring(0, name.length() - "_war_exploded".length());
            resolvedType = "exploded";
        } else if (lower.endsWith("_ear_exploded")) {
            baseName = name.substring(0, name.length() - "_ear_exploded".length());
            resolvedType = "exploded";
        } else if (lower.endsWith("_war")) {
            baseName = name.substring(0, name.length() - "_war".length());
            resolvedType = "war";
        } else if (lower.endsWith("_ear")) {
            baseName = name.substring(0, name.length() - "_ear".length());
            resolvedType = "ear";
        } else if (lower.endsWith(".war")) {
            baseName = name.substring(0, name.length() - ".war".length());
            resolvedType = "war";
        } else if (lower.endsWith(".ear")) {
            baseName = name.substring(0, name.length() - ".ear".length());
            resolvedType = "ear";
        }

        // Strip version patterns (##5.18.0, -5.18.0, -5.18.0-SNAPSHOT)
        baseName = baseName.replaceAll("(?i)##\\d+(\\.\\d+)*(-SNAPSHOT)?$", "");
        baseName = baseName.replaceAll("(?i)-\\d+(\\.\\d+)+(-SNAPSHOT)?$", "");

        // Format with colon notation
        if ("exploded".equals(resolvedType)) {
            return baseName + ":war exploded";
        } else if ("war".equals(resolvedType)) {
            return baseName + ":war";
        } else if ("ear".equals(resolvedType)) {
            return baseName + ":ear";
        }

        return name;
    }

    /**
     * Resolves a context path (e.g. {@code "/myapp"}) to the Tomcat context name
     * used for deployment descriptors and WAR filenames (e.g. {@code "myapp"}).
     *
     * <p>Handles:
     * <ul>
     *   <li>Null, empty, or {@code "/"} → {@code "ROOT"}</li>
     *   <li>Leading slash stripping</li>
     *   <li>Trailing slash stripping (prevents {@code "app/.xml"} on disk)</li>
     *   <li>Path traversal rejection ({@code ".."}, {@code "\"}, {@code ":"})</li>
     * </ul>
     *
     * @param contextPath the context path from a deployment artifact (may be null)
     * @return the resolved context name, never null or empty
     * @throws IllegalArgumentException if the context path contains traversal components
     */
    @NotNull
    public static String resolveContextName(@Nullable String contextPath) {
        if (contextPath == null || contextPath.isEmpty() || DEFAULT_CONTEXT_PATH.equals(contextPath)) {
            return ROOT_CONTEXT_NAME;
        }

        String contextName = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
        contextName = contextName.replaceAll("/+$", "");

        if (contextName.isEmpty()) {
            return ROOT_CONTEXT_NAME;
        }

        if (contextName.contains("..") || contextName.contains("\\") || contextName.contains(":")) {
            throw new IllegalArgumentException(
                    "Invalid context path '" + contextPath + "': must not contain '..', '\\', or ':'");
        }

        return contextName;
    }

    /**
     * Resolves a context path to a Tomcat context name, falling back to
     * {@link com.dev.idea.plugins.tomcat.TomcatConstants#ROOT_CONTEXT_NAME ROOT} on
     * invalid input instead of throwing.
     *
     * <p>Use this in post-launch paths (process handler, updater) where the
     * deployment strategy has already validated the path at launch time and a
     * hard failure would be disproportionate.
     *
     * @param contextPath the context path to resolve (may be null)
     * @param log         logger for the warn message on invalid input
     * @return the resolved context name, never null or empty
     */
    @NotNull
    public static String resolveContextNameSafe(@Nullable String contextPath,
                                                @NotNull com.intellij.openapi.diagnostic.Logger log) {
        try {
            return resolveContextName(contextPath);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid context path '" + contextPath + "': " + e.getMessage()
                    + " — falling back to ROOT");
            return ROOT_CONTEXT_NAME;
        }
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
