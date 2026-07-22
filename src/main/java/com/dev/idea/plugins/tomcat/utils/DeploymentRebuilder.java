package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the build tool's package step for a deployment's module through the
 * IDE's build-tool integration, behind an extension point so the plugin never
 * hard-links an optional build-tool plugin's API — the same contract as
 * {@link MavenModelProvider}.
 *
 * <p>The Maven implementation ({@code IdeaMavenDeploymentRebuilder}) is
 * registered only in {@code devtomcat-maven.xml}, which the platform loads only
 * when the Maven plugin is present. On installs without Maven the extension is
 * absent and {@link #getInstance()} returns {@code null} — callers degrade to
 * today's behavior (no rebuild step). A Gradle implementation can be registered
 * against this same point later without touching any caller.
 *
 * <p>This interface's signatures use only platform types so it is safe to
 * classload everywhere; only the impl touches build-tool APIs.
 */
public interface DeploymentRebuilder {

    ExtensionPointName<DeploymentRebuilder> EP =
            ExtensionPointName.create("com.dev.idea.plugins.tomcat.deploymentRebuilder");

    /** Whether this rebuilder can package {@code module} (e.g. it is a resolved Maven module). */
    boolean canRebuild(@NotNull Project project, @NotNull Module module);

    /**
     * Packages {@code module} through the build-tool integration, asynchronously.
     * Never blocks the calling thread waiting for the build.
     *
     * <p>Exactly one of {@code onSuccess} / {@code onFailure} is invoked, on the
     * EDT, after the build process finishes (or fails to start). {@code onFailure}
     * receives a human-readable reason.
     */
    void rebuild(@NotNull Project project, @NotNull Module module,
                 @NotNull Runnable onSuccess, @NotNull Consumer<String> onFailure);

    /**
     * The registered rebuilder, or {@code null} when no build-tool integration is
     * present (or the platform isn't initialized, e.g. a headless unit test).
     * Never throws.
     */
    @Nullable
    static DeploymentRebuilder getInstance() {
        try {
            List<DeploymentRebuilder> extensions = EP.getExtensionList();
            return extensions.isEmpty() ? null : extensions.get(0);
        } catch (Throwable t) {
            return null;
        }
    }
}
