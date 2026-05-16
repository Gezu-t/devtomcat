package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Centralises Tomcat-deployment filesystem path conventions so the layout
 * is documented in one place and call sites read like prose.
 *
 * <p>Three shapes are recognised:
 * <ul>
 *   <li>Per-context XML descriptor — {@code conf/Catalina/localhost/<context>.xml}.
 *       Written by {@link com.dev.idea.plugins.tomcat.runner.LocalDeploymentStrategy}
 *       for TYPE_EXPLODED artifacts.</li>
 *   <li>Packaged WAR copy — {@code webapps/<context>.war}. Written for TYPE_WAR
 *       artifacts.</li>
 *   <li>Extracted webapp directory — {@code webapps/<context>/}. What Tomcat
 *       creates when it deploys a WAR. We check this path during stale-cleanup
 *       to remove a leftover extract that would otherwise collide with a new
 *       deploy at the same context name.</li>
 * </ul>
 *
 * <p>The {@code contextName} parameter is always Tomcat's filename-encoded
 * stem (e.g. {@code "ROOT"}, {@code "myapp"}, {@code "foo#bar"}) as produced
 * by {@link ContextPathUtils#resolveContextName}.
 *
 * <p>Caller supplies the parent directory so the helper works for both the
 * runtime layout ({@code $CATALINA_BASE/conf/Catalina/localhost/},
 * {@code $CATALINA_BASE/webapps/}) and test fixtures.
 */
public final class TomcatDeploymentPaths {

    private TomcatDeploymentPaths() {}

    /**
     * Returns the path to a per-context XML descriptor:
     * {@code <catalinaLocalhostDir>/<contextName>.xml}.
     */
    @NotNull
    public static Path contextDescriptor(@NotNull Path catalinaLocalhostDir,
                                         @NotNull String contextName) {
        return catalinaLocalhostDir.resolve(contextName + ".xml");
    }

    /**
     * Returns the path to a deployed WAR file: {@code <webappsDir>/<contextName>.war}.
     */
    @NotNull
    public static Path warFile(@NotNull Path webappsDir, @NotNull String contextName) {
        return webappsDir.resolve(contextName + ".war");
    }

    /**
     * Returns the path to an extracted webapp directory:
     * {@code <webappsDir>/<contextName>/} (no extension).
     */
    @NotNull
    public static Path extractedDirectory(@NotNull Path webappsDir,
                                          @NotNull String contextName) {
        return webappsDir.resolve(contextName);
    }
}
