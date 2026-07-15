package com.dev.idea.plugins.tomcat.setting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link ProjectTomcatProfileScanner#parseWarCoordinates} — the pure pom
 * coordinate extraction behind both the Setup action's project scan and the
 * run-configuration producer's per-module detection. The load-bearing case is
 * the standard multi-module child pom, whose {@code <parent>} block declares
 * coordinates BEFORE the module's own: a naive first-match returns the
 * parent's artifactId and misnames the build output.
 */
class ProjectTomcatProfileScannerTest {

    @Test
    @DisplayName("standalone war pom: own coordinates extracted")
    void standalonePom() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>web-module</artifactId>
                    <version>1.0.0</version>
                    <packaging>war</packaging>
                </project>
                """, "fallback-name");
        assertEquals("web-module", coords.artifactId());
        assertEquals("1.0.0", coords.version());
    }

    @Test
    @DisplayName("child pom with <parent> block: module's own artifactId wins, not the parent's")
    void childPomParentBlockStripped() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-aggregator</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>web-module</artifactId>
                    <version>1.0.0</version>
                    <packaging>war</packaging>
                </project>
                """, "fallback-name");
        assertEquals("web-module", coords.artifactId());
        assertEquals("1.0.0", coords.version());
    }

    @Test
    @DisplayName("child pom with inherited version: parent's version used (Maven inheritance)")
    void childPomInheritsVersion() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-aggregator</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>web-module</artifactId>
                    <packaging>war</packaging>
                </project>
                """, "fallback-name");
        assertEquals("web-module", coords.artifactId());
        assertEquals("2.0.0", coords.version());
    }

    @Test
    @DisplayName("inherited version is not confused with a dependency's version")
    void dependencyVersionNotMistakenForOwn() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-aggregator</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>web-module</artifactId>
                    <packaging>war</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>org.thirdparty</groupId>
                            <artifactId>some-library</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """, "fallback-name");
        assertEquals("web-module", coords.artifactId());
        assertEquals("2.0.0", coords.version());
    }

    @Test
    @DisplayName("missing own artifactId falls back to the module name, not a dependency's artifactId")
    void missingArtifactIdUsesFallback() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <packaging>war</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>org.thirdparty</groupId>
                            <artifactId>some-library</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """, "module-name");
        assertEquals("module-name", coords.artifactId());
        assertEquals("1.0-SNAPSHOT", coords.version());
    }

    @Test
    @DisplayName("non-war packaging yields null")
    void nonWarPackagingIsNull() {
        assertNull(ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <artifactId>library-module</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """, "fallback-name"));
    }

    @Test
    @DisplayName("no packaging element yields null (Maven defaults to jar)")
    void absentPackagingIsNull() {
        assertNull(ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <artifactId>library-module</artifactId>
                    <version>1.0.0</version>
                </project>
                """, "fallback-name"));
    }
}
