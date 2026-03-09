package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalDeploymentStrategy")
class LocalDeploymentStrategyTest {

    @Nested
    @DisplayName("escapeXmlAttribute")
    class EscapeXmlAttributeTests {

        @Test
        @DisplayName("escapes ampersand")
        void escapesAmpersand() {
            assertEquals("a&amp;b", LocalDeploymentStrategy.escapeXmlAttribute("a&b"));
        }

        @Test
        @DisplayName("escapes less than")
        void escapesLessThan() {
            assertEquals("a&lt;b", LocalDeploymentStrategy.escapeXmlAttribute("a<b"));
        }

        @Test
        @DisplayName("escapes greater than")
        void escapesGreaterThan() {
            assertEquals("a&gt;b", LocalDeploymentStrategy.escapeXmlAttribute("a>b"));
        }

        @Test
        @DisplayName("escapes double quotes")
        void escapesDoubleQuotes() {
            assertEquals("a&quot;b", LocalDeploymentStrategy.escapeXmlAttribute("a\"b"));
        }

        @Test
        @DisplayName("escapes single quotes")
        void escapesSingleQuotes() {
            assertEquals("a&apos;b", LocalDeploymentStrategy.escapeXmlAttribute("a'b"));
        }

        @Test
        @DisplayName("handles multiple special characters")
        void handlesMultiple() {
            assertEquals("&amp;&lt;&gt;&quot;&apos;",
                    LocalDeploymentStrategy.escapeXmlAttribute("&<>\"'"));
        }

        @Test
        @DisplayName("returns plain string unchanged")
        void plainStringUnchanged() {
            assertEquals("/home/user/project", LocalDeploymentStrategy.escapeXmlAttribute("/home/user/project"));
        }

        @Test
        @DisplayName("handles empty string")
        void handlesEmpty() {
            assertEquals("", LocalDeploymentStrategy.escapeXmlAttribute(""));
        }

        @Test
        @DisplayName("handles Windows paths with backslashes")
        void handlesWindowsPaths() {
            assertEquals("C:\\Users\\test\\webapp",
                    LocalDeploymentStrategy.escapeXmlAttribute("C:\\Users\\test\\webapp"));
        }

        @Test
        @DisplayName("escapes ampersand before other entities to avoid double-escaping")
        void ampersandFirst() {
            // Verify & is escaped first — if < were escaped first, the &amp; would become &amp;amp;
            String result = LocalDeploymentStrategy.escapeXmlAttribute("&<");
            assertEquals("&amp;&lt;", result);
        }
    }

    @Nested
    @DisplayName("isContainerProvidedJar")
    class IsContainerProvidedJarTests {

        @Test
        @DisplayName("detects Tomcat Jasper jars as container-provided")
        void detectsTomcatJasperJars() {
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("tomcat-jasper-10.1.44.jar"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("tomcat-embed-jasper-10.1.50.jar"));
        }

        @Test
        @DisplayName("detects servlet and jsp api jars as container-provided")
        void detectsServletApis() {
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("jakarta.servlet-api-6.1.0.jar"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("jsp-api-2.3.3.jar"));
        }

        @Test
        @DisplayName("does not flag regular application libraries")
        void allowsApplicationLibraries() {
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar("spring-core-6.2.3.jar"));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar("my-company-shared.jar"));
        }
    }
}
