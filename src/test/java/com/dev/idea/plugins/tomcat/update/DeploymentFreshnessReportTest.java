package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Delivery;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Freshness;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.ModuleRow;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Report;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the platform-free core of the deployment freshness view:
 * delivery classification (all five kinds), served-copy freshness (never a
 * false STALE), and the display wording the dialog renders.
 */
@DisplayName("DeploymentFreshnessReport")
class DeploymentFreshnessReportTest {

    private static Path write(Path file, long mtimeMillis) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
        return file;
    }

    private static Map<String, String> scanJars(Path artifactRoot) {
        // Same seam production uses — classification and sync cannot drift.
        return DeployedClassesSync.scanDeployedLibraryJars(artifactRoot);
    }

    /**
     * Thin call-through so these tests read the classification arguments
     * without repeating the artifact-name map. Modules whose build artifact
     * name differs from the IDE module name are covered explicitly in
     * {@link Classification#qualifiedModuleNameStillMatchesItsJar}.
     */
    private static final class FR {
        static Report forExploded(String name, Path root,
                                  Map<String, ? extends List<Path>> roots,
                                  Set<String> covered, Map<String, String> jars,
                                  Set<String> overlayRecords) {
            return DeploymentFreshnessReport.forExploded(name, root, roots,
                    Map.of(), covered, jars, overlayRecords);
        }

        /** A packed WAR's served state is the webapps copy; tests pass it explicitly. */
        static Report forWar(String name, Path builtWar,
                             Map<String, ? extends List<Path>> roots) {
            return DeploymentFreshnessReport.forWar(name, builtWar, builtWar, roots);
        }
    }

    @Nested
    @DisplayName("delivery classification")
    class Classification {

        @Test
        @DisplayName("deployed jar + mirror-covered module → JAR_PLUS_OVERLAY")
        void jarPlusOverlay(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out/web-lib");
            Files.createDirectories(out);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of("web-lib"), scanJars(app), Set.of());

            assertEquals(Shape.EXPLODED, r.shape());
            assertEquals(1, r.rows().size());
            assertEquals(Delivery.JAR_PLUS_OVERLAY, r.rows().get(0).delivery());
        }

        @Test
        @DisplayName("a module whose build artifact name differs from its IDE name still matches its jar")
        void qualifiedModuleNameStillMatchesItsJar(@TempDir Path tmp) throws Exception {
            // Gradle-qualified / renamed module: IDE name "com.acme.web-lib",
            // packaged jar "web-lib-1.0.0.jar". Matching on the raw module name
            // finds no jar and misreports the module as loose classes.
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out/web-lib");
            Files.createDirectories(out);

            Report r = DeploymentFreshnessReport.forExploded("web-module", app,
                    Map.of("com.acme.web-lib", List.of(out)),
                    Map.of("com.acme.web-lib", "web-lib"),   // the sync's identity for it
                    Set.of(), scanJars(app), Set.of());

            assertEquals(Delivery.JAR_ONLY, r.rows().get(0).delivery(),
                    "the jar must be found via the sync's artifact identity, not the module name");
        }

        @Test
        @DisplayName("deployed jar + manifest overlay records (module not in covered set) → JAR_PLUS_OVERLAY")
        void overlayRecordsAloneQualify(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out/web-lib");
            Files.createDirectories(out);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of(), scanJars(app),
                    Set.of("WEB-INF/lib/web-lib-1.0.0.jar"));

            assertEquals(Delivery.JAR_PLUS_OVERLAY, r.rows().get(0).delivery());
        }

        @Test
        @DisplayName("deployed jar, module NOT covered → JAR_ONLY (the blind spot)")
        void jarOnly(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out/web-lib");
            Files.createDirectories(out);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of(), scanJars(app), Set.of());

            assertEquals(Delivery.JAR_ONLY, r.rows().get(0).delivery());
        }

        @Test
        @DisplayName("no deployed jar → LOOSE_CLASSES")
        void looseClasses(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            Files.createDirectories(app.resolve("WEB-INF/classes"));
            Path out = tmp.resolve("out/web-module");
            Files.createDirectories(out);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-module", List.of(out)),
                    Set.of("web-module"), scanJars(app), Set.of());

            assertEquals(Delivery.LOOSE_CLASSES, r.rows().get(0).delivery());
        }

        @Test
        @DisplayName("war deployment → PACKED_WAR for every module")
        void packedWar(@TempDir Path tmp) throws Exception {
            Path war = write(tmp.resolve("app.war"), 100_000L);
            Path out = tmp.resolve("out");
            Files.createDirectories(out);

            Report r = FR.forWar("web-module", war,
                    Map.of("web-module", List.of(out), "web-lib", List.of(out)));

            assertEquals(Shape.PACKED_WAR, r.shape());
            assertEquals(2, r.rows().size());
            assertTrue(r.rows().stream().allMatch(x -> x.delivery() == Delivery.PACKED_WAR));
        }

        @Test
        @DisplayName("no resolvable module → single UNKNOWN row, never STALE")
        void unresolved(@TempDir Path tmp) {
            Report exploded = FR.forExploded("web-module",
                    tmp, Map.of(), Set.of(), Map.of(), Set.of());
            Report war = FR.forWar("web-module", tmp.resolve("a.war"), Map.of());
            Report external = DeploymentFreshnessReport.unresolved("external-app", Shape.EXTERNAL);

            for (Report r : List.of(exploded, war, external)) {
                assertEquals(1, r.rows().size());
                assertEquals(Delivery.UNKNOWN, r.rows().get(0).delivery());
                assertFalse(r.rows().get(0).freshness().stale());
                assertFalse(r.rows().get(0).freshness().verified());
            }
            assertEquals(Shape.EXTERNAL, external.shape());
        }
    }

    @Nested
    @DisplayName("served-copy freshness (LOOSE / OVERLAY)")
    class ServedCopyFreshness {

        @Test
        @DisplayName("output newer than the deployed copy → STALE since the output's mtime")
        void newerOutputIsStale(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/classes/a/App.class"), 100_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("a/App.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-module", List.of(out)),
                    Set.of("web-module"), Map.of(), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertTrue(f.stale());
            assertEquals(300_000L, f.staleSinceMillis());
        }

        @Test
        @DisplayName("deployed copy at or after the output mtime → CURRENT (equal is not newer)")
        void equalOrNewerServedIsCurrent(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/classes/App.class"), 300_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("App.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-module", List.of(out)),
                    Set.of("web-module"), Map.of(), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale());
            assertTrue(f.verified());
        }

        @Test
        @DisplayName("no serving copy anywhere → CURRENT flagged unverified, never STALE")
        void missingServedCopyIsUnverifiedCurrent(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            Files.createDirectories(app.resolve("WEB-INF/classes"));
            Path out = tmp.resolve("out");
            write(out.resolve("App.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-module", List.of(out)),
                    Set.of("web-module"), Map.of(), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale(), "absence of evidence must never read as STALE");
            assertFalse(f.verified());
        }

        @Test
        @DisplayName("missing output roots → CURRENT (nothing exists to be stale)")
        void missingOutputRootsNeverStale(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            Files.createDirectories(app.resolve("WEB-INF/classes"));

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-module", List.of(tmp.resolve("does-not-exist"))),
                    Set.of("web-module"), Map.of(), Set.of());

            assertFalse(r.rows().get(0).freshness().stale());
        }

        @Test
        @DisplayName("overlay: no loose copy, output newer than covering jar → STALE")
        void overlayFallsBackToJarMtime(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of("web-lib"), scanJars(app), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertEquals(Delivery.JAR_PLUS_OVERLAY, r.rows().get(0).delivery());
            assertTrue(f.stale());
            assertEquals(300_000L, f.staleSinceMillis());
        }

        @Test
        @DisplayName("overlay: jar newer than every output file, no loose copy → CURRENT verified")
        void overlayCurrentWhenJarIsFresh(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 500_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of("web-lib"), scanJars(app), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale());
            assertTrue(f.verified(), "the jar is a serving copy — evidence, not a gap");
        }

        @Test
        @DisplayName("overlay: fresh loose copy wins over an older jar → CURRENT")
        void overlayLooseCopyBeatsOlderJar(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            write(app.resolve("WEB-INF/classes/Lib.class"), 300_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of("web-lib"), scanJars(app), Set.of());

            assertFalse(r.rows().get(0).freshness().stale());
        }
    }

    @Nested
    @DisplayName("jar / war mtime freshness")
    class SingleMtimeFreshness {

        @Test
        @DisplayName("JAR_ONLY: output newer than the deployed jar → STALE")
        void jarOnlyStale(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 100_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of(), scanJars(app), Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertEquals(Delivery.JAR_ONLY, r.rows().get(0).delivery());
            assertTrue(f.stale());
            assertEquals(300_000L, f.staleSinceMillis());
        }

        @Test
        @DisplayName("JAR_ONLY: jar at or after every output file → CURRENT")
        void jarOnlyCurrent(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            write(app.resolve("WEB-INF/lib/web-lib-1.0.0.jar"), 300_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of(), scanJars(app), Set.of());

            assertFalse(r.rows().get(0).freshness().stale());
        }

        @Test
        @DisplayName("JAR_ONLY: unreadable jar → CURRENT unverified, never STALE")
        void jarOnlyUnreadableJar(@TempDir Path tmp) throws Exception {
            Path app = tmp.resolve("app");
            Path out = tmp.resolve("out");
            write(out.resolve("Lib.class"), 300_000L);
            // The jar is listed as deployed but absent on disk — stat fails.
            Map<String, String> jars = Map.of("web-lib", "web-lib-1.0.0.jar");

            Report r = FR.forExploded("web-module", app,
                    Map.of("web-lib", List.of(out)),
                    Set.of(), jars, Set.of());

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale());
            assertFalse(f.verified());
        }

        @Test
        @DisplayName("PACKED_WAR: output newer than the war → STALE; older → CURRENT")
        void warFreshness(@TempDir Path tmp) throws Exception {
            Path war = write(tmp.resolve("app.war"), 200_000L);
            Path stale = tmp.resolve("out-stale");
            write(stale.resolve("App.class"), 500_000L);
            Path fresh = tmp.resolve("out-fresh");
            write(fresh.resolve("App.class"), 100_000L);

            Report r = FR.forWar("web-module", war,
                    Map.of("mod-a", List.of(stale), "mod-b", List.of(fresh)));

            ModuleRow a = r.rows().stream()
                    .filter(x -> x.moduleName().equals("mod-a")).findFirst().orElseThrow();
            ModuleRow b = r.rows().stream()
                    .filter(x -> x.moduleName().equals("mod-b")).findFirst().orElseThrow();
            assertTrue(a.freshness().stale());
            assertEquals(500_000L, a.freshness().staleSinceMillis());
            assertFalse(b.freshness().stale());
        }

        @Test
        @DisplayName("PACKED_WAR: missing war → every row CURRENT unverified")
        void missingWarIsUnverified(@TempDir Path tmp) throws Exception {
            Path out = tmp.resolve("out");
            write(out.resolve("App.class"), 300_000L);

            Report r = FR.forWar("web-module",
                    tmp.resolve("missing.war"), Map.of("web-module", List.of(out)));

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale());
            assertFalse(f.verified());
        }

        @Test
        @DisplayName("PACKED_WAR: freshness is judged against the DEPLOYED copy, not the rebuilt WAR")
        void rebuiltButNotRedeployedIsStale(@TempDir Path tmp) throws Exception {
            // The exact "my change isn't there" case: rebuilt WAR (newest),
            // compiled output newer than what Tomcat serves, served copy oldest.
            Path servedWar = write(tmp.resolve("webapps/app.war"), 100_000L);
            Path out = tmp.resolve("out");
            write(out.resolve("App.class"), 300_000L);
            Path builtWar = write(tmp.resolve("target/app.war"), 500_000L);

            Report r = DeploymentFreshnessReport.forWar("web-module", builtWar, servedWar,
                    Map.of("web-module", List.of(out)));

            Freshness f = r.rows().get(0).freshness();
            assertTrue(f.stale(),
                    "judging against the rebuilt WAR would claim Current while Tomcat serves old code");
            assertEquals(300_000L, f.staleSinceMillis());
        }

        @Test
        @DisplayName("PACKED_WAR: no served copy (server down) never claims a verified verdict")
        void noServedCopyIsUnverified(@TempDir Path tmp) throws Exception {
            Path out = tmp.resolve("out");
            write(out.resolve("App.class"), 100_000L);
            Path builtWar = write(tmp.resolve("target/app.war"), 500_000L);

            Report r = DeploymentFreshnessReport.forWar("web-module", builtWar, null,
                    Map.of("web-module", List.of(out)));

            Freshness f = r.rows().get(0).freshness();
            assertFalse(f.stale());
            assertFalse(f.verified(), "without the served copy the row cannot prove Current");
        }
    }

    @Nested
    @DisplayName("display wording")
    class Wording {

        @Test
        @DisplayName("stale label carries the age and the delivery-appropriate remedy")
        void staleLabels() {
            long now = 300_000L + 3 * 60_000L; // 3 min after the change landed
            Freshness stale = Freshness.stale(300_000L);

            String jarOnly = DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("web-lib", Delivery.JAR_ONLY, stale), now);
            String loose = DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("web-module", Delivery.LOOSE_CLASSES, stale), now);
            String war = DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("web-module", Delivery.PACKED_WAR, stale), now);

            String overlay = DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("web-lib", Delivery.JAR_PLUS_OVERLAY, stale), now);

            assertTrue(jarOnly.startsWith("Stale for 3 min"));
            assertTrue(jarOnly.contains("mvn install"));
            assertTrue(loose.contains("Update classes and resources"));
            assertTrue(war.contains("mvn package"));
            // An overlaid module is refreshed BY the sync — telling the user to
            // run a build-tool rebuild would send them down the slow wrong path.
            assertTrue(overlay.contains("Update classes and resources"),
                    "overlay staleness is fixed by the sync, not a rebuild");
            assertFalse(overlay.contains("mvn"));
        }

        @Test
        @DisplayName("every delivery kind has its own remedy — no generic fallback")
        void everyDeliveryHasItsOwnRemedy() {
            Set<String> remedies = new java.util.HashSet<>();
            for (Delivery d : Delivery.values()) {
                String remedy = DeploymentFreshnessReport.remedy(d);
                assertFalse(remedy.isBlank(), d + " must name a remedy");
                remedies.add(remedy);
            }
            // JAR_ONLY / PACKED_WAR / (LOOSE == JAR_PLUS_OVERLAY) / UNKNOWN
            assertEquals(4, remedies.size(),
                    "each delivery kind's remedy must be the one that actually refreshes it");
        }

        @Test
        @DisplayName("current labels distinguish verified from unverified")
        void currentLabels() {
            assertEquals("Current", DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("m", Delivery.LOOSE_CLASSES, Freshness.current()), 0));
            assertEquals("Current (not fully verified)", DeploymentFreshnessReport.freshnessLabel(
                    new ModuleRow("m", Delivery.LOOSE_CLASSES, Freshness.currentUnverified()), 0));
        }

        @Test
        @DisplayName("every delivery kind has a distinct label")
        void deliveryLabelsDistinct() {
            long distinct = List.of(Delivery.values()).stream()
                    .map(DeploymentFreshnessReport::deliveryLabel)
                    .distinct().count();
            assertEquals(Delivery.values().length, distinct);
        }
    }
}
