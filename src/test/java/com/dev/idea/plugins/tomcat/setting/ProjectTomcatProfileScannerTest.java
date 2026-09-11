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

    @Test
    @DisplayName("literal <build><finalName> names the build output")
    void literalFinalName() {
        var coords = ProjectTomcatProfileScanner.parseWarCoordinates("""
                <project>
                    <artifactId>web-storefront</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <packaging>war</packaging>
                    <build>
                        <finalName>storefront</finalName>
                    </build>
                </project>
                """, "fallback-name");
        assertEquals("storefront", coords.finalName());
    }

    @Test
    @DisplayName("no finalName: Maven's default artifactId-version")
    void defaultFinalName() {
        assertEquals("app-2.0", ProjectTomcatProfileScanner.finalNameFrom("<project/>", "app", "2.0"));
    }

    @Test
    @DisplayName("a plugin's finalName configuration is not the build's")
    void pluginFinalNameIgnored() {
        assertEquals("app-2.0", ProjectTomcatProfileScanner.finalNameFrom("""
                <project><build><plugins><plugin>
                    <artifactId>maven-assembly-plugin</artifactId>
                    <configuration><finalName>bundle</finalName></configuration>
                </plugin></plugins></build></project>
                """, "app", "2.0"));
    }

    @Test
    @DisplayName("a profile's finalName does not apply to the default build")
    void profileFinalNameIgnored() {
        assertEquals("app-2.0", ProjectTomcatProfileScanner.finalNameFrom(
                "<project><profiles><profile><build><finalName>prof</finalName></build></profile></profiles></project>",
                "app", "2.0"));
    }

    @Test
    @DisplayName("${project.artifactId} resolves; an unknown property falls back to the default")
    void finalNameProperties() {
        assertEquals("app", ProjectTomcatProfileScanner.finalNameFrom(
                "<project><build><finalName>${project.artifactId}</finalName></build></project>", "app", "2.0"));
        assertEquals("app-2.0", ProjectTomcatProfileScanner.finalNameFrom(
                "<project><build><finalName>${custom.name}</finalName></build></project>", "app", "2.0"));
    }

    @Test
    @DisplayName("a commented-out finalName is ignored")
    void commentedFinalNameIgnored() {
        assertEquals("app-2.0", ProjectTomcatProfileScanner.finalNameFrom(
                "<project><build><!-- <finalName>old</finalName> --></build></project>", "app", "2.0"));
    }

    @Test
    @DisplayName("the resolved build directory and finalName win over the pom text")
    void resolvedModelWins() {
        var coords = new ProjectTomcatProfileScanner.PomCoordinates("web-storefront", "1.0", "storefront");
        assertEquals("/p/out/site", ProjectTomcatProfileScanner.explodedOutputPath("/p", "/p/out", "site", coords));
        assertEquals("/p/target/storefront", ProjectTomcatProfileScanner.explodedOutputPath("/p", null, null, coords));
    }
}
