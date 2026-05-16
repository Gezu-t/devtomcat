package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Documents the three path shapes {@link TomcatDeploymentPaths} produces so a
 * future change to (say) the {@code .war} suffix or the relative layout fails
 * loudly here instead of silently elsewhere. The helper itself is a thin
 * wrapper over {@link Path#resolve}; these tests are intentionally minimal.
 */
class TomcatDeploymentPathsTest {

    @Test
    @DisplayName("contextDescriptor returns <dir>/<contextName>.xml")
    void contextDescriptor() {
        Path dir = Paths.get("/tmp/conf/Catalina/localhost");
        assertEquals(dir.resolve("myapp.xml"),
                TomcatDeploymentPaths.contextDescriptor(dir, "myapp"));
    }

    @Test
    @DisplayName("warFile returns <webappsDir>/<contextName>.war")
    void warFile() {
        Path webapps = Paths.get("/tmp/webapps");
        assertEquals(webapps.resolve("myapp.war"),
                TomcatDeploymentPaths.warFile(webapps, "myapp"));
    }

    @Test
    @DisplayName("extractedDirectory returns <webappsDir>/<contextName> (no extension)")
    void extractedDirectory() {
        Path webapps = Paths.get("/tmp/webapps");
        assertEquals(webapps.resolve("myapp"),
                TomcatDeploymentPaths.extractedDirectory(webapps, "myapp"));
    }

    @Test
    @DisplayName("multi-segment context name (e.g. foo#bar) is passed through verbatim")
    void multiSegmentContextName() {
        Path webapps = Paths.get("/tmp/webapps");
        assertEquals(webapps.resolve("foo#bar.war"),
                TomcatDeploymentPaths.warFile(webapps, "foo#bar"));
        assertEquals(webapps.resolve("foo#bar"),
                TomcatDeploymentPaths.extractedDirectory(webapps, "foo#bar"));
    }

    @Test
    @DisplayName("ROOT context name resolves correctly")
    void rootContext() {
        Path webapps = Paths.get("/tmp/webapps");
        assertEquals(webapps.resolve("ROOT.war"),
                TomcatDeploymentPaths.warFile(webapps, "ROOT"));
        assertEquals(webapps.resolve("ROOT"),
                TomcatDeploymentPaths.extractedDirectory(webapps, "ROOT"));
    }
}
