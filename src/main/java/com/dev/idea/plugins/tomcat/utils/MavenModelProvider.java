package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.module.Module;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Typed access to the IntelliJ Maven model, behind an extension point so the
 * plugin never hard-links the optional Maven plugin's API.
 *
 * <p>Replaces the former string-reflection ({@code Method.invoke}) approach. The
 * implementation ({@code IdeaMavenModelProvider}) is compiled against the real
 * {@code org.jetbrains.idea.maven} API and is registered <em>only</em> in
 * {@code devtomcat-maven.xml}, which the platform loads only when the Maven
 * plugin is present (see the {@code <depends optional config-file=...>} in
 * plugin.xml). So on IntelliJ IDEA without Maven (Community trimmed, Gradle-only,
 * or Maven disabled) the extension is simply absent and every accessor here
 * returns {@code null} — the same graceful-degradation contract the old
 * reflection had, but compile-checked and refactor-safe.
 *
 * <p>This interface's method signatures intentionally use only platform / JDOM
 * types ({@link Module}, {@link Element}) so the interface itself is safe to
 * classload everywhere; only the impl touches Maven types.
 */
public interface MavenModelProvider {

    ExtensionPointName<MavenModelProvider> EP =
            ExtensionPointName.create("com.dev.idea.plugins.tomcat.mavenModelProvider");

    /** The Maven {@code artifactId} for {@code module}, or {@code null} if not a Maven module. */
    @Nullable
    String getArtifactId(@NotNull Module module);

    /** The resolved effective Maven {@code <packaging>} (e.g. {@code "war"}), or {@code null}. */
    @Nullable
    String getPackaging(@NotNull Module module);

    /**
     * The {@code <configuration>} element of the module's {@code maven-war-plugin},
     * or {@code null} when the module isn't Maven or the plugin isn't declared.
     */
    @Nullable
    Element getWarPluginConfiguration(@NotNull Module module);

    /**
     * The registered provider, or {@code null} when the Maven plugin isn't present
     * (or the platform isn't initialized, e.g. a headless unit test). Never throws.
     */
    @Nullable
    static MavenModelProvider getInstance() {
        try {
            List<MavenModelProvider> extensions = EP.getExtensionList();
            return extensions.isEmpty() ? null : extensions.get(0);
        } catch (Throwable t) {
            TomcatProgress.rethrowIfControlFlow(t);
            return null;
        }
    }

    /** @return whether the Maven model is available (the Maven plugin is loaded). */
    static boolean isAvailable() {
        return getInstance() != null;
    }

    // --- Null-safe static convenience accessors (degrade to null when absent) ---

    @Nullable
    static String artifactId(@NotNull Module module) {
        MavenModelProvider provider = getInstance();
        if (provider == null) return null;
        try {
            return provider.getArtifactId(module);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    static String packaging(@NotNull Module module) {
        MavenModelProvider provider = getInstance();
        if (provider == null) return null;
        try {
            return provider.getPackaging(module);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    static Element warPluginConfiguration(@NotNull Module module) {
        MavenModelProvider provider = getInstance();
        if (provider == null) return null;
        try {
            return provider.getWarPluginConfiguration(module);
        } catch (Throwable t) {
            return null;
        }
    }
}
