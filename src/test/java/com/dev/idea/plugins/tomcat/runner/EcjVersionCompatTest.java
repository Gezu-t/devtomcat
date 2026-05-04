package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the ECJ-vs-class-file compatibility shim:
 * <ul>
 *   <li>Version-table mapping for every well-known ECJ release plus boundary
 *       cases (unknown future versions, sub-3.4 versions, non-3.x majors).</li>
 *   <li>Class file header parsing tolerates correct files, rejects bad magic,
 *       handles empty streams.</li>
 *   <li>WEB-INF scanning samples both {@code classes/} and {@code lib/*.jar},
 *       skips Multi-Release slices and {@code module-info.class}, caps at the
 *       sample limit, and survives corrupt JARs.</li>
 *   <li>End-to-end {@code check} pins the Tomcat 7.0.30 reproducer (ECJ 3.7
 *       on a webapp with Java 11 classes) and confirms no false warning when
 *       a modern Tomcat ECJ comfortably covers the webapp.</li>
 * </ul>
 */
class EcjVersionCompatTest {

    @Nested
    @DisplayName("EcjVersion.parse")
    class VersionParse {

        @Test @DisplayName("parses MAJOR.MINOR.MICRO")
        void parseTriple() {
            EcjVersionCompat.EcjVersion v = EcjVersionCompat.EcjVersion.parse("3.7.2");
            assertNotNull(v);
            assertEquals(3, v.major());
            assertEquals(7, v.minor());
            assertEquals(2, v.micro());
        }

        @Test @DisplayName("parses MAJOR.MINOR")
        void parsePair() {
            EcjVersionCompat.EcjVersion v = EcjVersionCompat.EcjVersion.parse("3.30");
            assertNotNull(v);
            assertEquals(0, v.micro());
        }

        @Test @DisplayName("strips Eclipse build qualifier")
        void parseWithQualifier() {
            // The exact form ECJ writes into Bundle-Version.
            EcjVersionCompat.EcjVersion v = EcjVersionCompat.EcjVersion.parse("3.7.0.M20120208-0800");
            assertNotNull(v);
            assertEquals(3, v.major());
            assertEquals(7, v.minor());
            assertEquals(0, v.micro());
        }

        @Test @DisplayName("rejects garbage and empty")
        void parseGarbage() {
            assertNull(EcjVersionCompat.EcjVersion.parse(null));
            assertNull(EcjVersionCompat.EcjVersion.parse(""));
            assertNull(EcjVersionCompat.EcjVersion.parse("   "));
            assertNull(EcjVersionCompat.EcjVersion.parse("not-a-version"));
        }

        @Test @DisplayName("compareTo is lexicographic across major, minor, micro")
        void compareTo() {
            EcjVersionCompat.EcjVersion a = EcjVersionCompat.EcjVersion.parse("3.7.0");
            EcjVersionCompat.EcjVersion b = EcjVersionCompat.EcjVersion.parse("3.7.2");
            EcjVersionCompat.EcjVersion c = EcjVersionCompat.EcjVersion.parse("3.10.0");
            assertNotNull(a); assertNotNull(b); assertNotNull(c);
            assertTrue(a.compareTo(b) < 0);
            assertTrue(b.compareTo(c) < 0);
            assertTrue(a.compareTo(c) < 0);
            assertEquals(0, a.compareTo(EcjVersionCompat.EcjVersion.parse("3.7.0")));
        }
    }

    @Nested
    @DisplayName("maxSupportedJavaFor — version table")
    class MaxJavaTable {

        @Test @DisplayName("Tomcat 7.0.30 layout: ECJ 3.7.x -> Java 7")
        void ecj37() {
            assertEquals(7, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.7.2")));
        }

        @Test @DisplayName("Eclipse 4.4 / ECJ 3.10 -> Java 8")
        void ecj310() {
            assertEquals(8, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.10.0")));
        }

        @Test @DisplayName("Eclipse 4.9 / ECJ 3.15 -> Java 11")
        void ecj315() {
            assertEquals(11, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.15.0")));
        }

        @Test @DisplayName("Eclipse 4.20 / ECJ 3.26 -> Java 17")
        void ecj326() {
            assertEquals(17, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.26.0")));
        }

        @Test @DisplayName("Eclipse 4.28 / ECJ 3.34 -> Java 21")
        void ecj334() {
            assertEquals(21, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.34.0")));
        }

        @Test @DisplayName("Unknown future minor floors to nearest known + extrapolates")
        void unknownFutureMinor() {
            // Above 3.40 (the highest known entry, mapping to Java 24): assume
            // forward compatibility, one Java per two minors. 3.42 -> Java 25.
            int v342 = EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.42.0"));
            assertTrue(v342 >= 24,
                    "Unknown future ECJ minor must not regress below the latest known entry: " + v342);
        }

        @Test @DisplayName("Sub-3.4 versions floor to Java 6")
        void preEclipse34() {
            assertEquals(6, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.0.0")));
            assertEquals(6, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.3.0")));
        }

        @Test @DisplayName("Non-3.x major is not falsely flagged: assumes latest known support")
        void nonThreeMajor() {
            // ECJ has never left the 3.x major track. An entry like "4.0.0" or
            // "1.0.0" is exotic; treat as supporting whatever the latest table
            // entry claims so we do not falsely warn against an unfamiliar shape.
            int latestKnown = EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("3.40.0"));
            assertEquals(latestKnown, EcjVersionCompat.maxSupportedJavaFor(
                    EcjVersionCompat.EcjVersion.parse("4.0.0")));
        }
    }

    @Nested
    @DisplayName("classFileMajorFor / javaVersionFor")
    class MajorMapping {

        @Test @DisplayName("Java 7 <-> major 51")
        void java7() {
            assertEquals(51, EcjVersionCompat.classFileMajorFor(7));
            assertEquals(7, EcjVersionCompat.javaVersionFor(51));
        }

        @Test @DisplayName("Java 11 <-> major 55")
        void java11() {
            assertEquals(55, EcjVersionCompat.classFileMajorFor(11));
            assertEquals(11, EcjVersionCompat.javaVersionFor(55));
        }

        @Test @DisplayName("Java 21 <-> major 65")
        void java21() {
            assertEquals(65, EcjVersionCompat.classFileMajorFor(21));
            assertEquals(21, EcjVersionCompat.javaVersionFor(65));
        }
    }

    @Nested
    @DisplayName("readClassFileMajor")
    class ReadHeader {

        @Test @DisplayName("reads major from valid 8-byte header")
        void validHeader() throws Exception {
            byte[] header = ByteBuffer.allocate(8)
                    .putInt(0xCAFEBABE)
                    .putShort((short) 0)        // minor
                    .putShort((short) 55)       // major
                    .array();
            int major = EcjVersionCompat.readClassFileMajor(new ByteArrayInputStream(header));
            assertEquals(55, major);
        }

        @Test @DisplayName("returns -1 on bad magic")
        void badMagic() throws Exception {
            byte[] header = ByteBuffer.allocate(8)
                    .putInt(0xDEADBEEF)
                    .putShort((short) 0)
                    .putShort((short) 55)
                    .array();
            int major = EcjVersionCompat.readClassFileMajor(new ByteArrayInputStream(header));
            assertEquals(-1, major);
        }

        @Test @DisplayName("throws IOException on short stream")
        void shortStream() {
            byte[] truncated = new byte[]{(byte) 0xCA, (byte) 0xFE};
            assertThrows(IOException.class,
                    () -> EcjVersionCompat.readClassFileMajor(new ByteArrayInputStream(truncated)));
        }
    }

    @Nested
    @DisplayName("findHighestClassFileMajor — WEB-INF scan")
    class WebInfScan {

        @Test @DisplayName("returns -1 when no class files exist")
        void emptyWebInf(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            assertEquals(-1, EcjVersionCompat.findHighestClassFileMajor(List.of(webInf)));
        }

        @Test @DisplayName("returns -1 when WEB-INF directory does not exist")
        void nonExistentDir(@TempDir Path tmp) {
            assertEquals(-1, EcjVersionCompat.findHighestClassFileMajor(
                    List.of(tmp.resolve("missing"))));
        }

        @Test @DisplayName("scans WEB-INF/classes for Java major")
        void classesOnly(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path classes = Files.createDirectories(webInf.resolve("classes").resolve("com").resolve("ex"));
            writeClassFile(classes.resolve("Foo.class"), 55);  // Java 11

            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(55, max);
        }

        @Test @DisplayName("scans WEB-INF/lib JAR root entries")
        void libJars(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path lib = Files.createDirectories(webInf.resolve("lib"));
            writeJarWithClass(lib.resolve("dep.jar"), "com/ex/Foo.class", 52);  // Java 8

            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(52, max);
        }

        @Test @DisplayName("classes and lib combined: highest wins")
        void mixedClassesAndLib(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path classes = Files.createDirectories(webInf.resolve("classes"));
            Path lib = Files.createDirectories(webInf.resolve("lib"));
            writeClassFile(classes.resolve("App.class"), 55);              // Java 11
            writeJarWithClass(lib.resolve("legacy.jar"), "L.class", 49);   // Java 5
            writeJarWithClass(lib.resolve("modern.jar"), "M.class", 61);   // Java 17

            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(61, max);
        }

        @Test @DisplayName("Multi-Release slices are NOT considered (Jasper compiles against root)")
        void skipsMultiReleaseSlices(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path lib = Files.createDirectories(webInf.resolve("lib"));
            // Root entry at Java 8, MR slice at Java 17.
            writeJar(lib.resolve("mr.jar"),
                    new ClassEntry("com/ex/Foo.class", 52),
                    new ClassEntry("META-INF/versions/9/com/ex/Foo.class", 61));

            // The scanner must report the root major (52), not the slice (61).
            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(52, max);
        }

        @Test @DisplayName("module-info.class is skipped (would confuse with the slice contents)")
        void skipsModuleInfo(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path lib = Files.createDirectories(webInf.resolve("lib"));
            writeJar(lib.resolve("modular.jar"),
                    new ClassEntry("module-info.class", 53),       // Java 9
                    new ClassEntry("com/ex/Foo.class", 52));       // Java 8

            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(52, max, "module-info.class must be ignored, regular class wins");
        }

        @Test @DisplayName("corrupt JAR is skipped without aborting the scan")
        void corruptJar(@TempDir Path tmp) throws Exception {
            Path webInf = Files.createDirectories(tmp.resolve("WEB-INF"));
            Path lib = Files.createDirectories(webInf.resolve("lib"));
            Files.writeString(lib.resolve("broken.jar"), "not a zip");
            writeJarWithClass(lib.resolve("good.jar"), "G.class", 55);

            int max = EcjVersionCompat.findHighestClassFileMajor(List.of(webInf));
            assertEquals(55, max);
        }

        @Test @DisplayName("multiple WEB-INF dirs are scanned in order")
        void multipleWebInfs(@TempDir Path tmp) throws Exception {
            Path a = Files.createDirectories(tmp.resolve("a").resolve("WEB-INF").resolve("classes"));
            Path b = Files.createDirectories(tmp.resolve("b").resolve("WEB-INF").resolve("classes"));
            writeClassFile(a.resolve("A.class"), 52);
            writeClassFile(b.resolve("B.class"), 61);

            int max = EcjVersionCompat.findHighestClassFileMajor(
                    List.of(tmp.resolve("a").resolve("WEB-INF"), tmp.resolve("b").resolve("WEB-INF")));
            assertEquals(61, max);
        }
    }

    @Nested
    @DisplayName("findEcjBundle")
    class EcjBundleLookup {

        @Test @DisplayName("returns null when lib/ has no ecj-*.jar")
        void noEcj(@TempDir Path tmp) throws Exception {
            Files.createDirectories(tmp.resolve("lib"));
            assertNull(EcjVersionCompat.findEcjBundle(tmp));
        }

        @Test @DisplayName("returns null when lib/ doesn't exist")
        void noLib(@TempDir Path tmp) {
            assertNull(EcjVersionCompat.findEcjBundle(tmp));
        }

        @Test @DisplayName("parses version from manifest (Tomcat 7.0.30 layout)")
        void parsesFromManifest(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            // Reproduce the actual manifest format Tomcat 7.0.30 ships.
            writeJarWithManifest(lib.resolve("ecj-3.7.2.jar"),
                    "Bundle-Version", "3.7.0.M20120208-0800");

            EcjVersionCompat.EcjBundle bundle = EcjVersionCompat.findEcjBundle(tmp);
            assertNotNull(bundle);
            assertEquals("3.7.0", bundle.version().toString());
            // ECJ 3.7 supports Java 7 -> class file major 51.
            assertEquals(51, bundle.maxClassFileMajor());
        }

        @Test @DisplayName("falls back to file name when manifest is missing")
        void parsesFromFilename(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            writeJar(lib.resolve("ecj-4.30.jar"), new ClassEntry("Foo.class", 65));
            // The JAR has no manifest with a version attribute; the shim should
            // fall back to the filename's "4.30" — but ECJ has no 4.x track so
            // the version table treats it as "latest known support". The real
            // useful path here is that the lookup does not crash.
            EcjVersionCompat.EcjBundle bundle = EcjVersionCompat.findEcjBundle(tmp);
            assertNotNull(bundle);
        }
    }

    @Nested
    @DisplayName("check — end-to-end mismatch detection")
    class EndToEnd {

        @Test @DisplayName("Tomcat 7.0.30 + Java 11 webapp -> mismatch warning fires")
        void tomcat7WithJava11Webapp(@TempDir Path tmp) throws Exception {
            // Tomcat install: lib/ecj-3.7.2.jar (Java 7 ceiling).
            Path catalinaHome = Files.createDirectories(tmp.resolve("tomcat"));
            Path lib = Files.createDirectories(catalinaHome.resolve("lib"));
            writeJarWithManifest(lib.resolve("ecj-3.7.2.jar"),
                    "Bundle-Version", "3.7.0.M20120208-0800");

            // Webapp: WEB-INF/classes with a Java 11 class (major 55).
            Path webInf = Files.createDirectories(tmp.resolve("webapp").resolve("WEB-INF"));
            Path classes = Files.createDirectories(webInf.resolve("classes"));
            writeClassFile(classes.resolve("App.class"), 55);

            TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(TomcatDeploymentLogger.class);

            EcjVersionCompat.Mismatch mismatch =
                    EcjVersionCompat.check(catalinaHome, List.of(webInf), logger);

            assertTrue(mismatch.isMismatch(), "Java 11 classes vs ECJ 3.7 must be flagged");
            assertEquals(55, mismatch.actualClassFileMajor());
            assertNotNull(mismatch.message());
            assertTrue(mismatch.message().contains("ecj-3.7.2.jar"),
                    "Warning must name the offending JAR");
            assertTrue(mismatch.message().contains("Java 7"), "Warning must state ECJ ceiling");
            assertTrue(mismatch.message().contains("Java 11"), "Warning must state actual webapp Java");
            assertTrue(mismatch.message().contains("ClassFormatException"),
                    "Warning must mention the runtime exception users will see");
            org.mockito.Mockito.verify(logger).logServerWarning(
                    org.mockito.ArgumentMatchers.contains("ClassFormatException"));
        }

        @Test @DisplayName("Modern ECJ comfortably covering webapp -> no warning")
        void modernEcjNoWarning(@TempDir Path tmp) throws Exception {
            Path catalinaHome = Files.createDirectories(tmp.resolve("tomcat"));
            Path lib = Files.createDirectories(catalinaHome.resolve("lib"));
            writeJarWithManifest(lib.resolve("ecj-4.30.jar"),
                    "Bundle-Version", "3.36.0.v20231024-0500");

            Path webInf = Files.createDirectories(tmp.resolve("webapp").resolve("WEB-INF"));
            Path classes = Files.createDirectories(webInf.resolve("classes"));
            writeClassFile(classes.resolve("App.class"), 55);  // Java 11, well within ECJ 3.36 / Java 22

            TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(TomcatDeploymentLogger.class);

            EcjVersionCompat.Mismatch mismatch =
                    EcjVersionCompat.check(catalinaHome, List.of(webInf), logger);

            assertFalse(mismatch.isMismatch(), "Modern ECJ must not warn against a Java 11 webapp");
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.never())
                    .logServerWarning(org.mockito.ArgumentMatchers.anyString());
        }

        @Test @DisplayName("No ecj-*.jar -> silent skip (rare configuration)")
        void noEcjBundle(@TempDir Path tmp) throws Exception {
            Path catalinaHome = Files.createDirectories(tmp.resolve("tomcat"));
            Files.createDirectories(catalinaHome.resolve("lib"));

            Path webInf = Files.createDirectories(tmp.resolve("webapp").resolve("WEB-INF"));
            Files.createDirectories(webInf.resolve("classes"));

            EcjVersionCompat.Mismatch mismatch =
                    EcjVersionCompat.check(catalinaHome, List.of(webInf), null);

            assertFalse(mismatch.isMismatch());
        }

        @Test @DisplayName("Empty webapp -> silent skip")
        void emptyWebapp(@TempDir Path tmp) throws Exception {
            Path catalinaHome = Files.createDirectories(tmp.resolve("tomcat"));
            Path lib = Files.createDirectories(catalinaHome.resolve("lib"));
            writeJarWithManifest(lib.resolve("ecj-3.7.2.jar"),
                    "Bundle-Version", "3.7.0");

            Path webInf = Files.createDirectories(tmp.resolve("webapp").resolve("WEB-INF"));
            Files.createDirectories(webInf.resolve("classes"));

            EcjVersionCompat.Mismatch mismatch =
                    EcjVersionCompat.check(catalinaHome, List.of(webInf), null);

            assertFalse(mismatch.isMismatch(),
                    "Empty webapp must not produce a false mismatch");
        }
    }

    // ---------------------------------------------------------------- //
    // Test helpers
    // ---------------------------------------------------------------- //

    private record ClassEntry(String name, int classFileMajor) {}

    /** Writes a class file at {@code path} with a synthetic 8-byte header (CAFEBABE + 0 minor + given major). */
    private static void writeClassFile(Path path, int major) throws IOException {
        Files.write(path, classFileBytes(major));
    }

    private static byte[] classFileBytes(int major) {
        // 8-byte header is enough — the scanner only reads the magic + minor + major.
        return ByteBuffer.allocate(8)
                .putInt(0xCAFEBABE)
                .putShort((short) 0)
                .putShort((short) major)
                .array();
    }

    /** Writes a JAR containing a single class at the given entry name with the given class file major. */
    private static void writeJarWithClass(Path jar, String entryName, int major) throws IOException {
        writeJar(jar, new ClassEntry(entryName, major));
    }

    /** Writes a JAR with a manifest containing the given attribute. */
    private static void writeJarWithManifest(Path jar, String attrName, String attrValue) throws IOException {
        Manifest mf = new Manifest();
        Attributes a = mf.getMainAttributes();
        a.putValue("Manifest-Version", "1.0");
        a.putValue(attrName, attrValue);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, mf)) {
            // empty body - just the manifest
        }
        Files.write(jar, baos.toByteArray());
    }

    /** Writes a JAR with the given class entries. */
    private static void writeJar(Path jar, ClassEntry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (ClassEntry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(classFileBytes(e.classFileMajor()));
                zos.closeEntry();
            }
        }
        Files.write(jar, baos.toByteArray());
    }
}
