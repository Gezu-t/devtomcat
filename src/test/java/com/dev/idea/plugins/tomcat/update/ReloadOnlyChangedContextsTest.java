package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * An update reloads only the contexts whose deployed tree changed; a no-op update
 * reloads nothing. {@code Deployment} is sealed, so real external deployments over
 * temp directories stand in: display name = directory name, valid = exists.
 */
@DisplayName("Update reloads only changed contexts")
class ReloadOnlyChangedContextsTest {

    @TempDir Path tmp;

    private Deployment exploded(String name, String contextPath) throws IOException {
        return new ExternalFileDeployment(Files.createDirectories(tmp.resolve("apps").resolve(name)), contextPath, true);
    }

    private Path descriptor(String context) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("ctx"));
        Path f = dir.resolve(context + ".xml");
        Files.writeString(f, "<Context/>");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000L));
        return f;
    }

    @Test
    @DisplayName("only the changed deployment's descriptor is touched")
    void onlyChangedTouched() throws IOException {
        Path a = descriptor("a"), b = descriptor("b");
        TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        List<String> touched = TomcatApplicationUpdater.touchExplodedContextXml(
                tmp.resolve("ctx"), List.of(exploded("app-a", "/a"), exploded("app-b", "/b")), Set.of("app-a"), logger);

        assertEquals(List.of("app-a"), touched);
        assertTrue(Files.getLastModifiedTime(a).toMillis() > 1_000_000L, "a reloaded");
        assertEquals(1_000_000L, Files.getLastModifiedTime(b).toMillis(), "b left alone");
        verify(logger).logServerInfo(contains("skipped for unchanged: app-b"));
    }

    @Test
    @DisplayName("a no-op update reloads nothing and says so")
    void noOpReloadsNothing() throws IOException {
        Path a = descriptor("a"), b = descriptor("b");
        TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        List<String> touched = TomcatApplicationUpdater.touchExplodedContextXml(
                tmp.resolve("ctx"), List.of(exploded("app-a", "/a"), exploded("app-b", "/b")), Set.of(), logger);

        assertTrue(touched.isEmpty());
        assertEquals(1_000_000L, Files.getLastModifiedTime(a).toMillis());
        assertEquals(1_000_000L, Files.getLastModifiedTime(b).toMillis());
        verify(logger).logServerInfo(contains("No deployment changed — no context reloaded"));
    }

    @Test
    @DisplayName("a WAR deployment is never touched here, changed or not")
    void warNeverTouched() throws IOException {
        Path a = descriptor("a");
        Deployment war = new ExternalFileDeployment(
                Files.createDirectories(tmp.resolve("apps").resolve("app-a")), "/a", false);

        List<String> touched = TomcatApplicationUpdater.touchExplodedContextXml(
                tmp.resolve("ctx"), List.of(war), Set.of("app-a"), mock(TomcatDeploymentLogger.class));
        assertTrue(touched.isEmpty());
        assertEquals(1_000_000L, Files.getLastModifiedTime(a).toMillis());
    }
}
