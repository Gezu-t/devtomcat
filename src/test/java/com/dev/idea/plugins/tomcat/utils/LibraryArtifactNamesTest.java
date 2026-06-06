package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("LibraryArtifactNames.libraryArtifactKey — version-independent identity")
class LibraryArtifactNamesTest {

    @Test
    @DisplayName("strips the trailing version, keeping a multi-segment artifactId")
    void stripsTrailingVersion() {
        assertAll(
                () -> assertEquals("commons-lang3",
                        LibraryArtifactNames.libraryArtifactKey("commons-lang3-3.12.0.jar")),
                () -> assertEquals("log4j-api",
                        LibraryArtifactNames.libraryArtifactKey("log4j-api-2.20.0.jar")),
                () -> assertEquals("spring-webmvc",
                        LibraryArtifactNames.libraryArtifactKey("spring-webmvc-6.2.3.jar"))
        );
    }

    @Test
    @DisplayName("a digit inside an artifactId segment is not a version boundary")
    void digitInsideSegmentIsNotVersion() {
        // 'lang3' and 'c3p0' contain digits but do not *start* with one as a
        // standalone segment, so they remain part of the artifactId stem.
        assertAll(
                () -> assertEquals("c3p0",
                        LibraryArtifactNames.libraryArtifactKey("c3p0-0.9.5.jar")),
                () -> assertEquals("commons-lang3",
                        LibraryArtifactNames.libraryArtifactKey("commons-lang3-3.14.0.jar"))
        );
    }

    @Test
    @DisplayName("drops a classifier that trails the version")
    void dropsClassifierAfterVersion() {
        // Maven layout is <artifactId>-<version>-<classifier>.jar; parsing stops
        // at the version, so the classifier is removed along with it.
        assertEquals("netty-transport-native-epoll",
                LibraryArtifactNames.libraryArtifactKey(
                        "netty-transport-native-epoll-4.1.100-linux-x86_64.jar"));
    }

    @Test
    @DisplayName("handles a SNAPSHOT version qualifier")
    void handlesSnapshotQualifier() {
        assertAll(
                () -> assertEquals("app",
                        LibraryArtifactNames.libraryArtifactKey("app-1.0-SNAPSHOT.jar")),
                () -> assertEquals("web-module",
                        LibraryArtifactNames.libraryArtifactKey("web-module-2.3.4-SNAPSHOT.jar"))
        );
    }

    @Test
    @DisplayName("returns the whole base name when there is no version segment")
    void noVersionReturnsWholeName() {
        assertAll(
                () -> assertEquals("foo",
                        LibraryArtifactNames.libraryArtifactKey("foo.jar")),
                () -> assertEquals("spring-boot-starter",
                        LibraryArtifactNames.libraryArtifactKey("spring-boot-starter.jar"))
        );
    }

    @Test
    @DisplayName("a version-leading first segment is never treated as a version")
    void firstSegmentIsNeverVersion() {
        // The first segment is always kept, so an artifactId that itself begins
        // with a digit still yields a non-empty key.
        assertAll(
                () -> assertEquals("2d-graphics",
                        LibraryArtifactNames.libraryArtifactKey("2d-graphics-1.5.jar")),
                () -> assertEquals("9-lib",
                        LibraryArtifactNames.libraryArtifactKey("9-lib.jar"))
        );
    }

    @Test
    @DisplayName("matches the .jar extension case-insensitively")
    void matchesExtensionCaseInsensitively() {
        assertEquals("Foo",
                LibraryArtifactNames.libraryArtifactKey("Foo-1.0.JAR"));
    }

    @Test
    @DisplayName("two versions of the same library collapse to one key")
    void twoVersionsShareAKey() {
        // This is the dedup invariant: WEB-INF/lib is authoritative for a
        // library regardless of which version is on the runtime classpath.
        assertEquals(
                LibraryArtifactNames.libraryArtifactKey("dep-1.0.0.jar"),
                LibraryArtifactNames.libraryArtifactKey("dep-2.5.1.jar"));
    }
}
