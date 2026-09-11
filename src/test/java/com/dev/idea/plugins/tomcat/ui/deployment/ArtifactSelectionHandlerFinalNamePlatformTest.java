package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.setting.ProjectTomcatProfileScanner;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Set;

/** A war named by {@code <finalName>} is live, and the module entry points at its real exploded directory. */
public class ArtifactSelectionHandlerFinalNamePlatformTest extends BasePlatformTestCase {

    private static final String POM = """
            <project>
                <artifactId>web-storefront</artifactId>
                <version>1.0-SNAPSHOT</version>
                <packaging>war</packaging>
                <build>
                    <finalName>storefront</finalName>
                </build>
            </project>
            """;

    public void testWarNamedByFinalNameIsNotStale() {
        myFixture.addFileToProject("pom.xml", POM);
        Set<String> active = ReadAction.compute(() -> ArtifactSelectionHandler.activeNameSpellings(getModule()));

        assertTrue("storefront.war belongs to web-storefront",
                ArtifactSelectionHandler.hasActiveSourceModule("storefront.war", active));
        assertFalse("a truly orphaned war is still filtered",
                ArtifactSelectionHandler.hasActiveSourceModule("deleted-module.war", active));
    }

    public void testModuleEntryPointsAtFinalNameDirectory() {
        myFixture.addFileToProject("pom.xml", POM);
        var detected = ReadAction.compute(() -> ProjectTomcatProfileScanner.scanModule(getModule()));

        assertNotNull(detected);
        assertTrue(detected.explodedPath(), detected.explodedPath().endsWith("/target/storefront"));
    }
}
