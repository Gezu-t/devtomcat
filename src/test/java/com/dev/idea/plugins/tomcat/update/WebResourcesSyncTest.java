package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link WebResourcesSync#mirrorTree} contract that ships the
 * fix for "Update Classes and Resources doesn't refresh JSPs". The
 * mirror MUST copy every file under {@code src/main/webapp/} into the
 * exploded artifact root EXCEPT {@code WEB-INF/classes/} (owned by the
 * class-sync) and {@code WEB-INF/lib/} (owned by the build tool).
 * mtime + size gate keeps the second run a no-op when nothing changed.
 */
@DisplayName("WebResourcesSync.mirrorTree")
class WebResourcesSyncTest {

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("copies missing JSP from src/main/webapp/ into the artifact root")
    void copiesMissingJsp(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/webapp-portal"));
        writeFile(src.resolve("index.jsp"), "<%-- v1 --%>");

        WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied());
        assertEquals("<%-- v1 --%>", Files.readString(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("preserves directory hierarchy (static/css, WEB-INF/views, etc.)")
    void preservesHierarchy(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(src.resolve("static/style.css"), "body{}");
        writeFile(src.resolve("static/js/app.js"), "$(function(){});");
        writeFile(src.resolve("WEB-INF/views/login.jsp"), "<form/>");

        WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);

        assertEquals(3, r.copied());
        assertTrue(Files.exists(dst.resolve("static/style.css")));
        assertTrue(Files.exists(dst.resolve("static/js/app.js")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/views/login.jsp")));
    }

    @Test
    @DisplayName("does NOT touch WEB-INF/classes — that's the class-sync's job")
    void skipsWebInfClasses(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        // Unusual but legal: src/main/webapp/WEB-INF/classes/ exists.
        writeFile(src.resolve("WEB-INF/classes/marker.txt"), "would-collide");

        WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied(), "WEB-INF/classes/ must be skipped");
        assertFalse(Files.exists(dst.resolve("WEB-INF/classes/marker.txt")));
    }

    @Test
    @DisplayName("does NOT touch WEB-INF/lib — owned by the build tool's dependency resolver")
    void skipsWebInfLib(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(src.resolve("WEB-INF/lib/whatever.jar"), "fake-jar");

        WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied(), "WEB-INF/lib/ must be skipped");
        assertFalse(Files.exists(dst.resolve("WEB-INF/lib/whatever.jar")));
    }

    @Test
    @DisplayName("second run is a no-op when nothing changed (mtime + size gate)")
    void secondRunIsNoOp(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(src.resolve("index.jsp"), "<%-- unchanged --%>");

        assertEquals(1, WebResourcesSync.mirrorTree(src, dst).copied());
        assertEquals(0, WebResourcesSync.mirrorTree(src, dst).copied(),
                "second sync with no edits must copy nothing");
    }

    @Test
    @DisplayName("re-copies when source mtime is newer than destination")
    void rewritesNewerSource(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        Path srcFile = src.resolve("index.jsp");
        writeFile(srcFile, "<%-- v1 --%>");
        WebResourcesSync.mirrorTree(src, dst);

        // Edit source: new content + bumped mtime
        Files.writeString(srcFile, "<%-- v2 - longer --%>");
        Files.setLastModifiedTime(srcFile, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        assertEquals(1, WebResourcesSync.mirrorTree(src, dst).copied());
        assertEquals("<%-- v2 - longer --%>", Files.readString(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("size tie-breaker: same mtime but different size still copies")
    void sizeTieBreaker(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        Path srcFile = src.resolve("index.jsp");
        writeFile(srcFile, "short");
        WebResourcesSync.mirrorTree(src, dst);

        // Edit source to be longer but pin mtime to the deployed copy's mtime
        // (simulates two edits landing inside one filesystem mtime tick).
        FileTime t = Files.getLastModifiedTime(dst.resolve("index.jsp"));
        Files.writeString(srcFile, "much longer content here");
        Files.setLastModifiedTime(srcFile, t);

        assertEquals(1, WebResourcesSync.mirrorTree(src, dst).copied(),
                "equal mtime + different size must still copy");
        assertEquals("much longer content here", Files.readString(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("creates missing intermediate directories at destination")
    void createsIntermediateDirs(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        // Destination intentionally empty — no subdirs exist yet.
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(src.resolve("a/deeply/nested/page.jsp"), "x");

        assertEquals(1, WebResourcesSync.mirrorTree(src, dst).copied());
        assertTrue(Files.exists(dst.resolve("a/deeply/nested/page.jsp")));
    }

    @Test
    @DisplayName("refuses to mirror when src and dst nest inside each other")
    void refusesNestedPaths(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("a"));
        // dst is INSIDE src — would loop / overwrite source.
        Path dst = Files.createDirectories(tmp.resolve("a/inner"));
        writeFile(src.resolve("index.jsp"), "x");

        assertEquals(0, WebResourcesSync.mirrorTree(src, dst).copied(),
                "nested src/dst must bail without copying");
    }

    @Test
    @DisplayName("empty result when src is not a directory")
    void srcMustBeDirectory(@TempDir Path tmp) throws IOException {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // Pass a file instead of a directory.
        Path fakeSrc = tmp.resolve("not-a-dir.txt");
        Files.writeString(fakeSrc, "x");
        assertEquals(WebResourcesSync.MirrorResult.EMPTY,
                WebResourcesSync.mirrorTree(fakeSrc, dst));
    }

    @Test
    @DisplayName("multi-source overlay: second source wins on shared paths, additive for new files")
    void multiSourceOverlay(@TempDir Path tmp) throws Exception {
        // Models Maven's webResources semantics: convention src/main/webapp is mirrored
        // first, then each <webResources>/<resource>/<directory> overlays on top. Last
        // writer wins per relative path; new files are additive.
        Path conv = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path extra = Files.createDirectories(tmp.resolve("extra-web"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(conv.resolve("index.jsp"), "convention");
        writeFile(conv.resolve("static/keep.css"), "keep me");
        // Bump the source's mtime so the size-tie-breaker isn't the deciding factor
        // — overlay must work on mtime, content, OR size, not just one.
        writeFile(extra.resolve("index.jsp"), "OVERLAID by extra");
        java.nio.file.Files.setLastModifiedTime(extra.resolve("index.jsp"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        writeFile(extra.resolve("static/added.js"), "$();");

        // First mirror = convention.
        WebResourcesSync.mirrorTree(conv, dst);
        // Second mirror = extras.
        WebResourcesSync.mirrorTree(extra, dst);

        // Overlay won for shared path; convention's unique file preserved; extra's
        // unique file got copied.
        assertEquals("OVERLAID by extra", Files.readString(dst.resolve("index.jsp")));
        assertEquals("keep me", Files.readString(dst.resolve("static/keep.css")));
        assertEquals("$();", Files.readString(dst.resolve("static/added.js")));
    }

    // -----------------------------------------------------------------
    // Orphan reconciliation: with PreResources gone, the deployed webapp
    // tree is the sole source Tomcat serves from. Stale orphans (files
    // the user deleted from src/main/webapp but the previous mvn-package
    // left in the exploded dir) must be removed, EXCEPT inside the
    // SKIP_SUBTREES regions which are owned by other pipelines.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Orphan reconcile — deleted JSP is removed from dst")
    void orphan01_deletedJspRemoved(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        // Initial state: two pages exist in src; mirror them into dst.
        writeFile(src.resolve("keep.jsp"), "<%-- v1 --%>");
        writeFile(src.resolve("gone.jsp"), "<%-- delete me --%>");
        WebResourcesSync.mirrorTree(src, dst);

        // User deletes gone.jsp from source.
        Files.delete(src.resolve("gone.jsp"));

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = WebResourcesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(1, removed);
        assertTrue(Files.exists(dst.resolve("keep.jsp")));
        assertFalse(Files.exists(dst.resolve("gone.jsp")), "deleted JSP must be removed from dst");
    }

    @Test
    @DisplayName("Orphan reconcile — WEB-INF/classes is NEVER touched (owned by class-sync)")
    void orphan02_webInfClassesPreserved(@TempDir Path tmp) throws IOException {
        // The webapp source tree doesn't claim authority over WEB-INF/classes/;
        // DeployedClassesSync does. If the orphan pass swept that subtree we
        // would delete the class-sync's output every time Update Classes and
        // Resources ran — silent and devastating.
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        // The class-sync has populated WEB-INF/classes/ — that's its turf.
        writeFile(dst.resolve("WEB-INF/classes/com/foo/App.class"), "compiled");
        writeFile(dst.resolve("WEB-INF/classes/messages.properties"), "k=v");

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = WebResourcesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(0, removed,
                "the orphan pass must NEVER touch files under WEB-INF/classes/");
        assertTrue(Files.exists(dst.resolve("WEB-INF/classes/com/foo/App.class")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/classes/messages.properties")));
        assertTrue(Files.exists(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("Orphan reconcile — WEB-INF/lib is NEVER touched (owned by the build tool)")
    void orphan03_webInfLibPreserved(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        // The build (maven-war-plugin / Gradle war) put dependency JARs here.
        writeFile(dst.resolve("WEB-INF/lib/some-dep-1.0.jar"), "fake-jar-bytes");
        writeFile(dst.resolve("WEB-INF/lib/another-dep-2.0.jar"), "fake-jar-bytes");

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = WebResourcesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(0, removed,
                "the orphan pass must NEVER touch files under WEB-INF/lib/");
        assertTrue(Files.exists(dst.resolve("WEB-INF/lib/some-dep-1.0.jar")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/lib/another-dep-2.0.jar")));
    }

    @Test
    @DisplayName("Orphan reconcile — files outside WEB-INF/ but never in any webapp source are removed")
    void orphan04_topLevelOrphan(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        // Orphan from a previous build that produced this file but the current
        // source tree doesn't have it.
        writeFile(dst.resolve("legacy.html"), "<html/>");
        writeFile(dst.resolve("static/old.css"), "obsolete");

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = WebResourcesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(2, removed);
        assertFalse(Files.exists(dst.resolve("legacy.html")));
        assertFalse(Files.exists(dst.resolve("static/old.css")));
        assertTrue(Files.exists(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("Orphan reconcile — empty retain set deletes everything outside SKIP_SUBTREES")
    void orphan05_emptyRetainOutsideSkipSubtrees(@TempDir Path tmp) throws IOException {
        // Pins the contract for the caller: removeOrphans with an empty
        // retain set wipes everything OUTSIDE the protected subtrees.
        // syncDeployments guards against this with the
        // `if (!contributedPaths.isEmpty())` gate — this test makes sure
        // that protection over WEB-INF/classes and WEB-INF/lib is
        // structural (lives in removeOrphans), not just behavioural.
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(dst.resolve("index.jsp"), "page");
        writeFile(dst.resolve("WEB-INF/classes/Foo.class"), "compiled");
        writeFile(dst.resolve("WEB-INF/lib/dep.jar"), "jar");

        int removed = WebResourcesSync.removeOrphans(dst, java.util.Collections.emptySet());

        assertEquals(1, removed, "only index.jsp must be removed");
        assertFalse(Files.exists(dst.resolve("index.jsp")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/classes/Foo.class")),
                "WEB-INF/classes survives even with an empty retain set");
        assertTrue(Files.exists(dst.resolve("WEB-INF/lib/dep.jar")),
                "WEB-INF/lib survives even with an empty retain set");
    }
}
