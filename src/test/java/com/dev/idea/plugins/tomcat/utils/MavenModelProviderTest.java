package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Degrade-to-null / never-throw contract for {@link MavenModelProvider}'s static
 * accessors. A mock {@link Module} cannot resolve to a real Maven project, so
 * every accessor must return {@code null} without throwing — whether the Maven
 * provider is registered in the runtime (the impl can't resolve the mock and the
 * facade swallows the failure) or absent entirely (Community / Gradle-only, where
 * {@link MavenModelProvider#getInstance()} is {@code null}). This is the same
 * graceful-degradation guarantee the former string-reflection had, now typed.
 *
 * <p>{@code isAvailable()} is intentionally NOT asserted: the test runtime bundles
 * the Maven plugin, so the provider's presence is environment-dependent and not a
 * stable invariant to pin here.
 */
@DisplayName("MavenModelProvider")
class MavenModelProviderTest {

    @Test
    @DisplayName("accessors never throw and return null when the module can't resolve to a Maven project")
    void accessorsAreNullSafe() {
        Module module = Mockito.mock(Module.class);
        assertNull(MavenModelProvider.artifactId(module));
        assertNull(MavenModelProvider.packaging(module));
        assertNull(MavenModelProvider.warPluginConfiguration(module));
    }
}
