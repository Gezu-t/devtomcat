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
 * Also pins the manifest-based stale-file reconcile the sync pairs with
 * the mirror (see the reconcile section below).
 */
@DisplayName("WebResourcesSync")
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
    @DisplayName("refuses to mirror when src and dst nest inside each other — and marks the walk failed")
    void refusesNestedPaths(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("a"));
        // dst is INSIDE src — would loop / overwrite source.
        Path dst = Files.createDirectories(tmp.resolve("a/inner"));
        writeFile(src.resolve("index.jsp"), "x");

        WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);
        assertEquals(0, r.copied(), "nested src/dst must bail without copying");
        assertTrue(r.walkFailed(),
                "a refused walk must be marked failed so the caller defers stale cleanup");
    }

    @Test
    @DisplayName("FAILED result when src is not a directory — the caller must defer stale cleanup")
    void srcMustBeDirectory(@TempDir Path tmp) throws IOException {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // Pass a file instead of a directory. A vanished source root must not
        // make every file it previously synced look stale — mark walk failed.
        Path fakeSrc = tmp.resolve("not-a-dir.txt");
        Files.writeString(fakeSrc, "x");
        assertEquals(WebResourcesSync.MirrorResult.FAILED,
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

    @Test
    @DisplayName("an unvisitable entry (no-execute source subdir) marks the walk failed")
    void unvisitableEntryMarksWalkFailed(@TempDir Path tmp) throws IOException {
        // A source subtree the walker cannot stat is silently absent from
        // contributedPaths even though its files still exist in source. If the
        // walk were reported clean, the reconcile would delete their deployed
        // copies. Note Files.isDirectory is USELESS inside visitFileFailed here
        // — the stat itself failed, so it reports false for a real directory;
        // ANY unvisitable entry must mark the walk failed.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions required to simulate an unreadable subtree");
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));
        writeFile(src.resolve("index.jsp"), "page");
        Path sub = src.resolve("locked");
        writeFile(sub.resolve("page.jsp"), "unreachable");
        // r-- without x: the walker can LIST the directory but cannot stat its
        // children — visitFileFailed fires with the type undeterminable.
        java.util.Set<java.nio.file.attribute.PosixFilePermission> readOnlyNoExec =
                java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> restore =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(sub, readOnlyNoExec);
        try {
            WebResourcesSync.MirrorResult r = WebResourcesSync.mirrorTree(src, dst);

            assertTrue(r.walkFailed(),
                    "an unvisitable source entry must defer the caller's stale cleanup");
            assertTrue(r.contributedPaths().contains("index.jsp"),
                    "the mirror still covers the readable part of the tree");
        } finally {
            Files.setPosixFilePermissions(sub, restore); // let @TempDir clean up
        }
    }

    // -----------------------------------------------------------------
    // Stale-file reconcile (manifest-based, via SyncManifest). The deployed
    // webapp tree has TWO writers: the build (mvn package / gradle war —
    // filtered <webResources>, WAR overlays, frontend build output,
    // generated descriptors) and this sync. Only files THIS sync previously
    // wrote may be cleaned when they disappear from source; build-produced
    // files are never in the manifest and must survive every reconcile —
    // the old "delete everything the source walk didn't visit" pass wiped
    // them on every launch/Update (silent 404s, missing assets).
    // Each test drives the exact production sequence:
    //   mirrorTree(...) then SyncManifest.reconcile(dst, manifestFor(dst), contributed).
    // -----------------------------------------------------------------

    private static int reconcile(Path dst, java.util.Set<String> contributed) {
        return SyncManifest.reconcile(
                dst, WebResourcesSync.webResourcesManifestFor(dst), contributed);
    }

    @Test
    @DisplayName("Stale reconcile — JSP synced before and deleted from source is removed across runs")
    void reconcile01_deletedJspRemovedAcrossRuns(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        // Run 1: two pages exist in src; mirror + reconcile records them.
        writeFile(src.resolve("keep.jsp"), "<%-- v1 --%>");
        writeFile(src.resolve("gone.jsp"), "<%-- delete me --%>");
        WebResourcesSync.MirrorResult first = WebResourcesSync.mirrorTree(src, dst);
        assertEquals(0, reconcile(dst, first.contributedPaths()),
                "first run establishes the baseline and deletes nothing");

        // User deletes gone.jsp from source. Run 2: reconcile cleans it.
        Files.delete(src.resolve("gone.jsp"));
        WebResourcesSync.MirrorResult second = WebResourcesSync.mirrorTree(src, dst);
        int removed = reconcile(dst, second.contributedPaths());

        assertEquals(1, removed);
        assertTrue(Files.exists(dst.resolve("keep.jsp")));
        assertFalse(Files.exists(dst.resolve("gone.jsp")),
                "a JSP we synced before, now removed from source, must be cleaned");
    }

    @Test
    @DisplayName("Stale reconcile — build-produced files the sync never wrote are PRESERVED across runs")
    void reconcile02_buildProducedFilesPreserved(@TempDir Path tmp) throws IOException {
        // The data-loss bug this design fixes: the build places files in the
        // exploded webapp that no webapp source enumerates — filtered
        // <webResources>, frontend build output, WAR-overlay content,
        // generated descriptors. They are never in the manifest, so
        // reconcile must never delete them, run after run.
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        writeFile(dst.resolve("static/bundle.js"), "frontend-build-output");
        writeFile(dst.resolve("WEB-INF/settings.properties"), "filtered-by-build");
        writeFile(dst.resolve("META-INF/MANIFEST.MF"), "generated");

        for (int run = 1; run <= 2; run++) {
            WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
            assertEquals(0, reconcile(dst, mr.contributedPaths()),
                    "run " + run + ": a file the sync never wrote must never be deleted");
        }
        assertTrue(Files.exists(dst.resolve("static/bundle.js")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/settings.properties")));
        assertTrue(Files.exists(dst.resolve("META-INF/MANIFEST.MF")));
        assertTrue(Files.exists(dst.resolve("index.jsp")));
    }

    @Test
    @DisplayName("Stale reconcile — the class-sync's turf and manifest survive the web reconcile")
    void reconcile03_otherPipelinesUntouched(@TempDir Path tmp) throws IOException {
        // WEB-INF/classes (class-sync), WEB-INF/lib (build tool), and BOTH
        // pipelines' manifests live in the deployed tree but are never in the
        // web-resources manifest — so the web reconcile can't touch them.
        // The old blunt pass deleted the class-sync manifest on every run,
        // permanently disarming the class-sync's own stale cleanup.
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        writeFile(dst.resolve("WEB-INF/classes/com/foo/App.class"), "compiled");
        writeFile(dst.resolve("WEB-INF/lib/some-dep-1.0.jar"), "fake-jar-bytes");
        Path classSyncManifest = DeployedClassesSync.classSyncManifestFor(
                dst.resolve("WEB-INF/classes"));
        SyncManifest.write(classSyncManifest, java.util.Set.of("com/foo/App.class"));

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = reconcile(dst, mr.contributedPaths());

        assertEquals(0, removed);
        assertTrue(Files.exists(dst.resolve("WEB-INF/classes/com/foo/App.class")));
        assertTrue(Files.exists(dst.resolve("WEB-INF/lib/some-dep-1.0.jar")));
        assertTrue(Files.isRegularFile(classSyncManifest),
                "the class-sync manifest must survive the web reconcile — deleting it"
                        + " would disarm the class-sync's stale cleanup every run");
        assertEquals(java.util.Set.of("com/foo/App.class"), SyncManifest.read(classSyncManifest),
                "class-sync manifest content is untouched");
    }

    @Test
    @DisplayName("Stale reconcile — first run over a build-populated tree deletes nothing, records the baseline")
    void reconcile04_firstRunNoManifest(@TempDir Path tmp) throws IOException {
        // Fresh deployment (or post-upgrade / post-clean-rebuild): no manifest
        // exists, so nothing is a proven orphan — even files that look stale.
        Path src = Files.createDirectories(tmp.resolve("src/main/webapp"));
        Path dst = Files.createDirectories(tmp.resolve("target/app"));

        writeFile(src.resolve("index.jsp"), "page");
        writeFile(dst.resolve("legacy.html"), "from-an-old-build");

        WebResourcesSync.MirrorResult mr = WebResourcesSync.mirrorTree(src, dst);
        int removed = reconcile(dst, mr.contributedPaths());

        assertEquals(0, removed, "no prior manifest -> nothing is a proven orphan");
        assertTrue(Files.exists(dst.resolve("legacy.html")));
        Path manifest = WebResourcesSync.webResourcesManifestFor(dst);
        assertEquals(java.util.Set.of("index.jsp"), SyncManifest.read(manifest),
                "the synced set is recorded for the next run");
    }

    @Test
    @DisplayName("Stale reconcile — manifest lives in WEB-INF/ (protected from HTTP), distinct from the class-sync's")
    void reconcile05_manifestLocation(@TempDir Path tmp) {
        Path dst = tmp.resolve("target/app");
        Path manifest = WebResourcesSync.webResourcesManifestFor(dst);

        assertEquals("WEB-INF", manifest.getParent().getFileName().toString(),
                "manifest lives directly in WEB-INF/ — protected from HTTP, off the classpath");
        assertNotEquals(
                DeployedClassesSync.classSyncManifestFor(dst.resolve("WEB-INF/classes")),
                manifest,
                "each pipeline reconciles exclusively against its own manifest");
    }
}
