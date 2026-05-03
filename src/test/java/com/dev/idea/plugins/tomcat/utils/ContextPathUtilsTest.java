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

        @Test
        @DisplayName("artifact name with capital 'I' canonicalizes to lowercase 'i' under any locale")
        void capitalIPreservedAcrossLocales() {
            // Regression-guard for the Turkish-locale 'I' → 'ı' bug. Pre-fix,
            // {@code "WebApi".toLowerCase()} called without an argument used the
            // JVM default locale; under tr_TR it produced "webapı" (dotless ı),
            // and the trailing regex {@code [^a-zA-Z0-9\-_]} replaced ı with '-',
            // giving "/webap" — the user's webapp would 404 because they expected
            // {@code /webapi}.
            //
            // Set Locale.GERMANY (or any non-Turkish) to confirm the dot-less-i
            // behaviour is locale-independent. Then set Locale.forLanguageTag("tr")
            // explicitly to confirm the Turkish path also succeeds.
            java.util.Locale prev = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
                assertEquals("/webapi", ContextPathUtils.generateContextPath("WebApi"),
                        "Turkish locale must NOT mangle the capital I to dotless ı");
                assertEquals("/webapi", ContextPathUtils.generateContextPath("WEBAPI"),
                        "uppercase WEBAPI must round-trip the same way");
            } finally {
                java.util.Locale.setDefault(prev);
            }
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

        @Test
        @DisplayName("collapses leading and trailing duplicate slashes together")
        void collapsesLeadingAndTrailingTogether() {
            // Regression-guard for the slash-strip-vs-collapse order bug.
            // Pre-fix: "//myapp//" → strip-trailing-once → "//myapp/" →
            // collapse → "/myapp/" (trailing slash survived).
            // Post-fix: "//myapp//" → collapse → "/myapp/" → strip-trailing
            // → "/myapp". The order swap is what makes both transformations
            // converge on the canonical form regardless of how mangled the
            // input is.
            assertEquals("/myapp", ContextPathUtils.normalizeContextPath("//myapp//"));
        }

        @Test
        @DisplayName("collapses three or more contiguous slashes")
        void collapsesManySlashes() {
            // Belt-and-braces — defends against pathological hand-edited
            // XML that strung many slashes together.
            assertEquals("/myapp", ContextPathUtils.normalizeContextPath("////myapp////"));
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

        @Test
        @DisplayName("strips ##version from .war name")
        void stripsParallelDeployVersion() {
            assertEquals("wipo-connect-shared-service",
                    ContextPathUtils.extractBaseModuleName("wipo-connect-shared-service##5.18.0.war"));
        }

        @Test
        @DisplayName("strips -version suffix from exploded name")
        void stripsVersionSuffix() {
            assertEquals("wipo-connect-shared-service",
                    ContextPathUtils.extractBaseModuleName("wipo-connect-shared-service-5.18.0"));
        }

        @Test
        @DisplayName("strips -version-SNAPSHOT suffix")
        void stripsSnapshotVersion() {
            assertEquals("my-app",
                    ContextPathUtils.extractBaseModuleName("my-app-1.2.3-SNAPSHOT"));
        }

        @Test
        @DisplayName("does not strip single-segment version (could be module name)")
        void doesNotStripSingleSegmentVersion() {
            // "-5" alone is ambiguous — don't strip it
            assertEquals("my-app-5",
                    ContextPathUtils.extractBaseModuleName("my-app-5"));
        }
    }

    @Nested
    @DisplayName("resolveContextName")
    class ResolveContextName {

        @Test
        @DisplayName("null returns ROOT")
        void nullReturnsRoot() {
            assertEquals("ROOT", ContextPathUtils.resolveContextName(null));
        }

        @Test
        @DisplayName("empty string returns ROOT")
        void emptyReturnsRoot() {
            assertEquals("ROOT", ContextPathUtils.resolveContextName(""));
        }

        @Test
        @DisplayName("/ returns ROOT")
        void slashReturnsRoot() {
            assertEquals("ROOT", ContextPathUtils.resolveContextName("/"));
        }

        @Test
        @DisplayName("/myapp returns myapp")
        void stripsLeadingSlash() {
            assertEquals("myapp", ContextPathUtils.resolveContextName("/myapp"));
        }

        @Test
        @DisplayName("myapp without slash returns myapp")
        void noLeadingSlash() {
            assertEquals("myapp", ContextPathUtils.resolveContextName("myapp"));
        }

        @Test
        @DisplayName("trailing slashes are stripped")
        void stripsTrailingSlashes() {
            assertEquals("myapp", ContextPathUtils.resolveContextName("/myapp///"));
        }

        @Test
        @DisplayName("nested context path preserved")
        void nestedContextPath() {
            assertEquals("api/v2", ContextPathUtils.resolveContextName("/api/v2"));
        }

        @Test
        @DisplayName("path traversal (..) throws IllegalArgumentException")
        void pathTraversalThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ContextPathUtils.resolveContextName("/../evil"));
        }

        @Test
        @DisplayName("backslash throws IllegalArgumentException")
        void backslashThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ContextPathUtils.resolveContextName("/my\\app"));
        }

        @Test
        @DisplayName("colon throws IllegalArgumentException")
        void colonThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ContextPathUtils.resolveContextName("/C:/bad"));
        }

        @Test
        @DisplayName("only trailing slashes returns ROOT")
        void onlyTrailingSlashes() {
            assertEquals("ROOT", ContextPathUtils.resolveContextName("///"));
        }
    }

    @Nested
    @DisplayName("formatArtifactDisplayName")
    class FormatArtifactDisplayName {

        @Test
        @DisplayName("converts _war_exploded to colon notation")
        void convertsUnderscoreWarExploded() {
            assertEquals("webapp-one:war exploded",
                    ContextPathUtils.formatArtifactDisplayName("webapp-one_war_exploded", "exploded"));
        }

        @Test
        @DisplayName("converts _war to colon notation")
        void convertsUnderscoreWar() {
            assertEquals("webapp-one:war",
                    ContextPathUtils.formatArtifactDisplayName("webapp-one_war", "war"));
        }

        @Test
        @DisplayName("preserves existing colon notation")
        void preservesColonNotation() {
            assertEquals("app:war exploded",
                    ContextPathUtils.formatArtifactDisplayName("app:war exploded", "exploded"));
        }

        @Test
        @DisplayName("strips version from WAR file name")
        void stripsVersionFromWar() {
            assertEquals("my-service:war",
                    ContextPathUtils.formatArtifactDisplayName("my-service##5.18.0.war", "war"));
        }

        @Test
        @DisplayName("strips version from exploded name")
        void stripsVersionFromExploded() {
            assertEquals("my-service:war exploded",
                    ContextPathUtils.formatArtifactDisplayName("my-service-5.18.0", "exploded"));
        }

        @Test
        @DisplayName("adds type suffix for plain module name")
        void addsTypeSuffixForPlainName() {
            assertEquals("my-module:war exploded",
                    ContextPathUtils.formatArtifactDisplayName("my-module", "exploded"));
        }

        @Test
        @DisplayName("returns name as-is when type is null and no suffix detected")
        void returnsNameAsIsForUnknownType() {
            assertEquals("some-thing",
                    ContextPathUtils.formatArtifactDisplayName("some-thing", null));
        }
    }
}
