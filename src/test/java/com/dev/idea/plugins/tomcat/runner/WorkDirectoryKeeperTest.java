package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** work/ survives a launch only when everything the compiled JSPs depend on is unchanged. */
@DisplayName("WorkDirectoryKeeper")
class WorkDirectoryKeeperTest {

    @TempDir Path tmp;

    private static Deployment exploded(String ctx, Path root) {
        return new ModuleBackedDeployment(ModuleRef.detached("web-module"), root, ctx, true);
    }

    private Path base() throws IOException {
        Path base = Files.createDirectories(tmp.resolve("base"));
        Files.createDirectories(base.resolve("work/Catalina/localhost/app"));
        return base;
    }

    private Path app() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("app"));
        Files.createDirectories(root.resolve("WEB-INF/lib"));
        Files.createDirectories(root.resolve("WEB-INF/classes"));
        Files.writeString(root.resolve("WEB-INF/lib/lib-alpha-1.0.jar"), "jar");
        return root;
    }

    private String fp(Path root) {
        return WorkDirectoryKeeper.fingerprint(List.of(exploded("/app", root)), "/opt/tomcat", "11.0.0", "/opt/jdk", false);
    }

    @Test
    @DisplayName("first launch clears work/ and records the fingerprint")
    void firstLaunchClears() throws IOException {
        Path base = base(), root = app();
        Path compiled = Files.writeString(base.resolve("work/Catalina/localhost/app/index_jsp.class"), "x");

        assertFalse(WorkDirectoryKeeper.apply(base, fp(root), null));
        assertFalse(Files.exists(compiled));
        assertTrue(Files.isRegularFile(base.resolve(WorkDirectoryKeeper.MARKER)));
    }

    @Test
    @DisplayName("an identical fingerprint keeps work/")
    void sameFingerprintKeeps() throws IOException {
        Path base = base(), root = app();
        WorkDirectoryKeeper.apply(base, fp(root), null);
        Files.createDirectories(base.resolve("work/Catalina/localhost/app"));
        Path compiled = Files.writeString(base.resolve("work/Catalina/localhost/app/index_jsp.class"), "x");

        assertTrue(WorkDirectoryKeeper.apply(base, fp(root), null));
        assertTrue(Files.exists(compiled), "compiled JSP must survive an unchanged launch");
    }

    @Test
    @DisplayName("a changed fingerprint clears work/")
    void changedFingerprintClears() throws IOException {
        Path base = base(), root = app();
        WorkDirectoryKeeper.apply(base, fp(root), null);
        Files.createDirectories(base.resolve("work/Catalina/localhost/app"));
        Path compiled = Files.writeString(base.resolve("work/Catalina/localhost/app/index_jsp.class"), "x");

        String changed = WorkDirectoryKeeper.fingerprint(List.of(exploded("/app", root)), "/opt/tomcat", "11.0.0", "/opt/other-jdk", false);
        assertFalse(WorkDirectoryKeeper.apply(base, changed, null));
        assertFalse(Files.exists(compiled));
    }

    @Test
    @DisplayName("a rebuilt library changes the fingerprint")
    void libraryChangeChangesFingerprint() throws IOException {
        Path root = app();
        String before = fp(root);
        Files.writeString(root.resolve("WEB-INF/lib/lib-alpha-1.0.jar"), "jar rebuilt with more bytes");
        assertNotEquals(before, fp(root));
    }

    @Test
    @DisplayName("the deployment set is part of the fingerprint")
    void deploymentSetChangesFingerprint() throws IOException {
        Path root = app();
        String one = fp(root);
        String two = WorkDirectoryKeeper.fingerprint(
                List.of(exploded("/app", root), exploded("/other", root)), "/opt/tomcat", "11.0.0", "/opt/jdk", false);
        assertNotEquals(one, two);
        assertEquals(one, fp(root), "and it is stable when nothing changed");
    }

    @Test
    @DisplayName("an incomplete class sync never matches — its classes may have changed unobserved")
    void incompleteSyncNeverMatches() throws IOException {
        Path root = app();
        String a = WorkDirectoryKeeper.fingerprint(List.of(exploded("/app", root)), "/opt/tomcat", "11.0.0", "/opt/jdk", true);
        String b = WorkDirectoryKeeper.fingerprint(List.of(exploded("/app", root)), "/opt/tomcat", "11.0.0", "/opt/jdk", true);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("a kept work/ is still swept for symlinks")
    void keptWorkIsSweptForSymlinks() throws IOException {
        Path base = base(), root = app();
        WorkDirectoryKeeper.apply(base, fp(root), null);
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createDirectories(base.resolve("work/Catalina"));
        Path link = base.resolve("work/Catalina/planted");
        Files.createSymbolicLink(link, outside);

        assertTrue(WorkDirectoryKeeper.apply(base, fp(root), null));
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "symlink removed");
        assertTrue(Files.isDirectory(outside), "its target untouched");
    }
}
