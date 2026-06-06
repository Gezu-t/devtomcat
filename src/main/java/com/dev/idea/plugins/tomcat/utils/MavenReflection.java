package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * One-shot reflection cache for the optional Maven plugin
 * ({@code org.jetbrains.idea.maven.project.MavenProjectsManager}). The class
 * lookup and {@code getInstance} / {@code findProject} method handles are
 * resolved once at class-init; callers do a single {@code Method.invoke}
 * instead of walking the class hierarchy each time.
 *
 * <p>Both manager-level and project-level reflection succeed in any IDE that
 * ships the Maven plugin. When the Maven plugin is absent or the project is
 * Gradle-only, the calls degrade to {@code null} return values — same contract
 * as the pre-cache code, just without the per-call lookup cost.
 */
public final class MavenReflection {

    private static final Class<?> MANAGER_CLASS;
    private static final Method GET_INSTANCE;
    private static final Method FIND_PROJECT;
    private static final boolean AVAILABLE;

    static {
        Class<?> mc = null;
        Method gi = null, fp = null;
        try {
            mc = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager");
            gi = mc.getMethod("getInstance", Project.class);
            fp = mc.getMethod("findProject", Module.class);
        } catch (Throwable ignored) {
            // Maven plugin absent (Community / Gradle-only) — leave fields null.
        }
        MANAGER_CLASS = mc;
        GET_INSTANCE = gi;
        FIND_PROJECT = fp;
        AVAILABLE = mc != null && gi != null && fp != null;
    }

    private MavenReflection() {}

    /** True when the Maven plugin is on the classpath at runtime. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Returns the Maven model object for {@code module}, or {@code null} when
     * the Maven plugin is absent, the module isn't a Maven project, or the
     * reflective call fails. Callers do their own per-instance reflection on
     * the returned object.
     */
    @Nullable
    public static Object findMavenProject(@NotNull Module module, @NotNull Project project) {
        if (!AVAILABLE) return null;
        try {
            Object manager = GET_INSTANCE.invoke(null, project);
            if (manager == null) return null;
            return FIND_PROJECT.invoke(manager, module);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the Maven {@code artifactId} for {@code module}, or {@code null}
     * when the Maven plugin is absent, the module isn't a Maven project, or any
     * reflective step fails. The artifactId is the stable identity a build uses
     * for the library JAR it produces ({@code <artifactId>-<version>.jar}), so
     * callers can match a dependency module against the JARs packaged into a
     * deployed {@code WEB-INF/lib/}.
     *
     * <p>Degrades to {@code null} on Community Edition / Gradle-only projects —
     * same contract as {@link #findMavenProject}.
     */
    @Nullable
    public static String getArtifactId(@NotNull Module module, @NotNull Project project) {
        Object mavenProject = findMavenProject(module, project);
        if (mavenProject == null) return null;
        try {
            Object mavenId = mavenProject.getClass().getMethod("getMavenId").invoke(mavenProject);
            if (mavenId == null) return null;
            Object artifactId = mavenId.getClass().getMethod("getArtifactId").invoke(mavenId);
            return artifactId instanceof String s ? s : null;
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }
}
