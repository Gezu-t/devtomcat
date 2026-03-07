package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContextPathUtils")
class ContextPathUtilsTest {

    @Nested
    @DisplayName("generateContextPath")
    class GenerateContextPath {

        @Test
        @DisplayName("strips version suffix from artifact name")
        void stripsVersionSuffix() {
            assertEquals("/myapp", ContextPathUtils.generateContextPath("myapp-1.0.0-SNAPSHOT"));
        }

        @Test
        @DisplayName("ROOT maps to /")
        void rootMapsToSlash() {
            assertEquals("/", ContextPathUtils.generateContextPath("ROOT"));
        }

        @Test
        @DisplayName("case-insensitive ROOT detection")
        void rootCaseInsensitive() {
            assertEquals("/", ContextPathUtils.generateContextPath("root"));
        }

        @Test
        @DisplayName("strips :war exploded suffix")
        void stripsWarExplodedSuffix() {
            assertEquals("/app", ContextPathUtils.generateContextPath("app:war exploded"));
        }

        @Test
        @DisplayName("strips .war extension")
        void stripsWarExtension() {
            assertEquals("/app", ContextPathUtils.generateContextPath("app.war"));
        }

        @Test
        @DisplayName("empty artifact name maps to /")
        void emptyMapsToSlash() {
            assertEquals("/", ContextPathUtils.generateContextPath(""));
        }

        @Test
        @DisplayName("strips .jar extension")
        void stripsJarExtension() {
            assertEquals("/mylib", ContextPathUtils.generateContextPath("mylib.jar"));
        }

        @Test
        @DisplayName("strips -exploded suffix")
        void stripsExplodedSuffix() {
            assertEquals("/app", ContextPathUtils.generateContextPath("app-exploded"));
        }

        @Test
        @DisplayName("result is lowercase")
        void resultIsLowercase() {
            assertEquals("/myapp", ContextPathUtils.generateContextPath("MyApp"));
        }

        @Test
        @DisplayName("version with SNAPSHOT stripped")
        void versionWithSnapshot() {
            assertEquals("/webapp", ContextPathUtils.generateContextPath("webapp-2.3.1-SNAPSHOT"));
        }

        @Test
        @DisplayName("simple version stripped")
        void simpleVersion() {
            assertEquals("/api", ContextPathUtils.generateContextPath("api-1.0"));
        }
    }

    @Nested
    @DisplayName("normalizeContextPath")
    class NormalizeContextPath {

        @Test
        @DisplayName("null normalizes to /")
        void nullNormalizesToSlash() {
            assertEquals("/", ContextPathUtils.normalizeContextPath(null));
        }

        @Test
        @DisplayName("empty normalizes to /")
        void emptyNormalizesToSlash() {
            assertEquals("/", ContextPathUtils.normalizeContextPath(""));
        }

        @Test
        @DisplayName("whitespace normalizes to /")
        void whitespaceNormalizesToSlash() {
            assertEquals("/", ContextPathUtils.normalizeContextPath("   "));
        }

        @Test
        @DisplayName("adds leading slash")
        void addsLeadingSlash() {
            assertEquals("/path", ContextPathUtils.normalizeContextPath("path"));
        }

        @Test
        @DisplayName("strips trailing slash")
        void stripsTrailingSlash() {
            assertEquals("/path", ContextPathUtils.normalizeContextPath("/path/"));
        }

        @Test
        @DisplayName("collapses double slashes")
        void collapsesDoubleSlashes() {
            assertEquals("/path/x", ContextPathUtils.normalizeContextPath("/path//x"));
        }

        @Test
        @DisplayName("root slash preserved")
        void rootSlashPreserved() {
            assertEquals("/", ContextPathUtils.normalizeContextPath("/"));
        }

        @Test
        @DisplayName("already normal path unchanged")
        void alreadyNormalUnchanged() {
            assertEquals("/myapp", ContextPathUtils.normalizeContextPath("/myapp"));
        }
    }

    @Nested
    @DisplayName("isValidContextPath")
    class IsValidContextPath {

        @Test
        @DisplayName("null is invalid")
        void nullIsInvalid() {
            assertFalse(ContextPathUtils.isValidContextPath(null));
        }

        @Test
        @DisplayName("empty is invalid")
        void emptyIsInvalid() {
            assertFalse(ContextPathUtils.isValidContextPath(""));
        }

        @Test
        @DisplayName("root slash is valid")
        void rootSlashIsValid() {
            assertTrue(ContextPathUtils.isValidContextPath("/"));
        }

        @Test
        @DisplayName("valid path is valid")
        void validPathIsValid() {
            assertTrue(ContextPathUtils.isValidContextPath("/valid"));
        }

        @Test
        @DisplayName("path without leading slash is invalid")
        void noLeadingSlashIsInvalid() {
            assertFalse(ContextPathUtils.isValidContextPath("noslash"));
        }

        @Test
        @DisplayName("path with space is invalid")
        void spaceIsInvalid() {
            assertFalse(ContextPathUtils.isValidContextPath("/has space"));
        }

        @Test
        @DisplayName("path with double slash is invalid")
        void doubleSlashIsInvalid() {
            assertFalse(ContextPathUtils.isValidContextPath("/has//double"));
        }

        @Test
        @DisplayName("nested valid path is valid")
        void nestedPathIsValid() {
            assertTrue(ContextPathUtils.isValidContextPath("/app/api/v1"));
        }

        @Test
        @DisplayName("path with hyphen and underscore is valid")
        void hyphenUnderscoreValid() {
            assertTrue(ContextPathUtils.isValidContextPath("/my-app_v2"));
        }
    }

    @Nested
    @DisplayName("extractBaseModuleName")
    class ExtractBaseModuleName {

        @Test
        @DisplayName("null returns empty")
        void nullReturnsEmpty() {
            assertEquals("", ContextPathUtils.extractBaseModuleName(null));
        }

        @Test
        @DisplayName("empty returns empty")
        void emptyReturnsEmpty() {
            assertEquals("", ContextPathUtils.extractBaseModuleName(""));
        }

        @Test
        @DisplayName("strips _war_exploded suffix")
        void stripsWarExploded() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one_war_exploded"));
        }

        @Test
        @DisplayName("strips :war exploded suffix")
        void stripsColonWarExploded() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one:war exploded"));
        }

        @Test
        @DisplayName("strips .war suffix")
        void stripsDotWar() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one.war"));
        }

        @Test
        @DisplayName("strips _war suffix")
        void stripsUnderscoreWar() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one_war"));
        }

        @Test
        @DisplayName("strips :war suffix")
        void stripsColonWar() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one:war"));
        }

        @Test
        @DisplayName("strips (exploded) suffix")
        void stripsExplodedParen() {
            assertEquals("webapp-one", ContextPathUtils.extractBaseModuleName("webapp-one (exploded)"));
        }

        @Test
        @DisplayName("plain name returns lowercase")
        void plainNameLowercase() {
            assertEquals("plain-name", ContextPathUtils.extractBaseModuleName("plain-name"));
        }

        @Test
        @DisplayName("result is always lowercase")
        void alwaysLowercase() {
            assertEquals("myapp", ContextPathUtils.extractBaseModuleName("MyApp.war"));
        }
    }
}
