package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Graceful-degradation contract for {@link MavenReflection}.
 *
 * <p>The optional Maven plugin ({@code org.jetbrains.idea.maven}) is not on the
 * plugin's test classpath, so the reflective handles resolve to {@code null} at
 * class-init. Under that condition every lookup must return {@code null} rather
 * than propagate {@code ClassNotFoundException} / {@code NoClassDefFoundError} —
 * the same contract callers rely on when running on IntelliJ IDEA Community or a
 * Gradle-only project. Each test guards on {@link MavenReflection#isAvailable()}
 * so it documents that assumption and skips (rather than fails) in the unlikely
 * event the Maven plugin is ever added to the test runtime.
 */
@DisplayName("MavenReflection")
class MavenReflectionTest {

    @Test
    @DisplayName("getArtifactId degrades to null when the Maven plugin is absent")
    void getArtifactIdDegradesToNull() {
        assumeFalse(MavenReflection.isAvailable(),
                "Maven plugin unexpectedly present on the test classpath");

        // Non-null stubs satisfy @NotNull instrumentation; the method short-
        // circuits to null before the arguments are ever dereferenced.
        Module module = Mockito.mock(Module.class);
        Project project = Mockito.mock(Project.class);

        assertNull(MavenReflection.getArtifactId(module, project),
                "Must return null — never throw — when the Maven plugin is absent");
    }

    @Test
    @DisplayName("findMavenProject degrades to null when the Maven plugin is absent")
    void findMavenProjectDegradesToNull() {
        assumeFalse(MavenReflection.isAvailable(),
                "Maven plugin unexpectedly present on the test classpath");

        Module module = Mockito.mock(Module.class);
        Project project = Mockito.mock(Project.class);

        assertNull(MavenReflection.findMavenProject(module, project),
                "Must return null — never throw — when the Maven plugin is absent");
    }
}
