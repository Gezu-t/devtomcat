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
 * <p>Both manager-level and project-level reflection succeed on Ultimate and
 * other distributions that ship the Maven plugin. Community-edition or
 * Gradle-only projects degrade to {@code null} return values — same contract
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
}
