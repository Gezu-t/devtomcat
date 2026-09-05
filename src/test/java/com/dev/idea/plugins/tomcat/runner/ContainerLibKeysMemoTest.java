package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The container-key listing is memoised per install and invalidated when lib/ or bin/ changes. */
@DisplayName("resolveContainerLibKeys memo")
class ContainerLibKeysMemoTest {

    @TempDir Path home;

    private TomcatInfo info() {
        TomcatInfo info = mock(TomcatInfo.class);
        when(info.getPath()).thenReturn(home.toString());
        return info;
    }

    @Test
    @DisplayName("repeat calls return the memoised set; adding a jar invalidates it")
    void memoisedUntilTheInstallChanges() throws IOException {
        LocalDeploymentStrategy.forgetContainerLibKeys();
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(lib.resolve("servlet-api.jar"), "x");

        Set<String> first = LocalDeploymentStrategy.resolveContainerLibKeys(info());
        assertTrue(first.contains("servlet-api"));
        assertSame(first, LocalDeploymentStrategy.resolveContainerLibKeys(info()), "unchanged install: same memo");

        Files.writeString(lib.resolve("extra-lib.jar"), "y");
        Files.setLastModifiedTime(lib, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        Set<String> after = LocalDeploymentStrategy.resolveContainerLibKeys(info());
        assertTrue(after.contains("extra-lib"), "a changed lib/ is re-read");
        assertFalse(first.contains("extra-lib"), "the old memo was not mutated");
    }
}
