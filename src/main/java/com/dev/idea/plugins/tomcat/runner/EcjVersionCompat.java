package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Detects mismatches between Tomcat's bundled Eclipse JDT compiler (ECJ) and
 * the class file versions used by a deployed webapp's dependencies.
 *
 * <h2>The problem</h2>
 * Tomcat's Jasper module compiles JSPs at request time using the ECJ JAR
 * shipped in {@code CATALINA_HOME/lib/ecj-*.jar}. Each ECJ release supports a
 * fixed maximum class file major version. When a JSP imports a class from a
 * dependency JAR that was compiled for a newer Java target, ECJ rejects the
 * class with
 * {@code org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException}.
 * The compilation cascade fails for every JSP that touches a too-new class.
 *
 * <p>Tomcat 7.0.30, for example, bundles ECJ 3.7.2 (February 2012) which
 * accepts up to Java 7 (class file major 51). A webapp compiled for Java 8+
 * — which is virtually every modern webapp — cannot run JSPs on that
 * Tomcat without either upgrading Tomcat or replacing the bundled ECJ with
 * a newer version.
 *
 * <h2>The mitigation</h2>
 * This shim is detection-only. It does not modify the user's Tomcat install
 * (which would be invasive) nor replace the ECJ JAR. It surfaces a clear,
 * pre-launch warning naming the bundled ECJ version, the highest class file
 * major in WEB-INF/, and the recommended remedies. That replaces the cryptic
 * runtime cascade with an upfront diagnostic the user can act on.
 *
 * <h2>Version table</h2>
 * The ECJ-to-max-Java table is hardcoded from the historical Eclipse release
 * record. Versions outside the table are treated as "unknown, assume modern"
 * so newer bundled ECJs do not produce false warnings.
 *
 * @author Gezahegn Lemma (Gezu)
 */
final class EcjVersionCompat {

    private static final Logger LOG = Logger.getInstance(EcjVersionCompat.class);

    /** Class file major version offset from Java major (Java 1 = major 45). */
    private static final int JAVA_MAJOR_OFFSET = 44;

    /**
     * How many class files we sample during the WEB-INF scan before giving up.
     * Detection is sample-based: we only need to find ONE class file with a
     * major version higher than ECJ supports to surface the warning.
     */
    private static final int MAX_CLASS_FILE_SAMPLES = 200;

    /** Class file magic number ({@code 0xCAFEBABE}). */
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    /** {@code ecj-3.7.2.jar}, {@code ecj-4.30.jar}, etc. */
    private static final Pattern ECJ_JAR_NAME = Pattern.compile(
            "^ecj-([\\d.]+)(?:\\.\\w+)?\\.jar$", Pattern.CASE_INSENSITIVE);

    /**
     * Maps ECJ minor version to the highest Java major that ECJ release
     * accepts. Sourced from the Eclipse JDT release notes.
     *
     * <p>The mapping uses the {@code 3.MINOR} family because every modern
     * ECJ release lives there. Lookups round DOWN to the nearest known minor
     * (so 3.7.2 maps to the entry for 3.7). Unknown minors above the highest
     * known entry are treated as supporting a major one higher than the last
     * known mapping (assume forward compatibility), and unknown minors below
     * the lowest entry are treated as Java 6 max.
     */
    private static final TreeMap<Integer, Integer> ECJ_MINOR_TO_JAVA = new TreeMap<>();
    static {
        // Eclipse 3.4 / 3.5 / 3.6 — all max Java 6.
        ECJ_MINOR_TO_JAVA.put(4, 6);
        ECJ_MINOR_TO_JAVA.put(5, 6);
        ECJ_MINOR_TO_JAVA.put(6, 6);
        // Eclipse 3.7 / 3.8 / 3.9 — Java 7.
        ECJ_MINOR_TO_JAVA.put(7, 7);
        ECJ_MINOR_TO_JAVA.put(8, 7);
        ECJ_MINOR_TO_JAVA.put(9, 7);
        // Eclipse 4.4 (ECJ 3.10) onwards: Java 8.
        ECJ_MINOR_TO_JAVA.put(10, 8);
        ECJ_MINOR_TO_JAVA.put(11, 8);
        ECJ_MINOR_TO_JAVA.put(12, 8);
        ECJ_MINOR_TO_JAVA.put(13, 9);    // 4.7
        ECJ_MINOR_TO_JAVA.put(14, 10);   // 4.8
        ECJ_MINOR_TO_JAVA.put(15, 11);   // 4.9
        ECJ_MINOR_TO_JAVA.put(16, 11);   // 4.10
        ECJ_MINOR_TO_JAVA.put(17, 12);   // 4.11
        ECJ_MINOR_TO_JAVA.put(18, 13);   // 4.12
        ECJ_MINOR_TO_JAVA.put(19, 13);   // 4.13
        ECJ_MINOR_TO_JAVA.put(20, 14);   // 4.14
        ECJ_MINOR_TO_JAVA.put(21, 14);   // 4.15
        ECJ_MINOR_TO_JAVA.put(22, 15);   // 4.16
        ECJ_MINOR_TO_JAVA.put(23, 15);   // 4.17
        ECJ_MINOR_TO_JAVA.put(24, 16);   // 4.18
        ECJ_MINOR_TO_JAVA.put(25, 16);   // 4.19
        ECJ_MINOR_TO_JAVA.put(26, 17);   // 4.20
        ECJ_MINOR_TO_JAVA.put(27, 17);   // 4.21
        ECJ_MINOR_TO_JAVA.put(28, 18);   // 4.22
        ECJ_MINOR_TO_JAVA.put(29, 18);   // 4.23
        ECJ_MINOR_TO_JAVA.put(30, 19);   // 4.24
        ECJ_MINOR_TO_JAVA.put(31, 19);   // 4.25
        ECJ_MINOR_TO_JAVA.put(32, 20);   // 4.26
        ECJ_MINOR_TO_JAVA.put(33, 20);   // 4.27
        ECJ_MINOR_TO_JAVA.put(34, 21);   // 4.28
        ECJ_MINOR_TO_JAVA.put(35, 21);   // 4.29
        ECJ_MINOR_TO_JAVA.put(36, 22);   // 4.30
        ECJ_MINOR_TO_JAVA.put(37, 22);   // 4.31
        ECJ_MINOR_TO_JAVA.put(38, 23);   // 4.32
        ECJ_MINOR_TO_JAVA.put(39, 23);   // 4.33
        ECJ_MINOR_TO_JAVA.put(40, 24);   // 4.34
    }

    private EcjVersionCompat() {}

    // ------------------------------------------------------------------ //
    // Public API
    // ------------------------------------------------------------------ //

    /**
     * Performs a pre-launch ECJ-vs-class-file compatibility check and
     * surfaces a console warning if the bundled ECJ cannot read the
     * webapp's class files. Never throws; failures (missing ECJ JAR,
     * unreadable manifests, I/O errors during scan) are logged at debug
     * and the method returns silently.
     *
     * @param catalinaHome resolved Tomcat install directory
     * @param webInfDirs   list of {@code WEB-INF} directories to sample
     *                     across all deployed exploded artifacts
     * @param logger       optional run-console logger
     * @return the detected mismatch (or {@link Mismatch#NONE} if compatible
     *         / undetermined)
     */
    @NotNull
    static Mismatch check(@NotNull Path catalinaHome,
                          @NotNull Iterable<Path> webInfDirs,
                          @Nullable TomcatDeploymentLogger logger) {
        EcjBundle ecj = findEcjBundle(catalinaHome);
        if (ecj == null) {
            // No ECJ bundle to check against (rare: Tomcat without Jasper, or
            // an unusual install layout). Skip silently.
            LOG.debug("No ecj-*.jar found under " + catalinaHome.resolve("lib") + "; skipping check");
            return Mismatch.NONE;
        }

        int highest = findHighestClassFileMajor(webInfDirs);
        if (highest < 0) {
            LOG.debug("No class files sampled under " + webInfDirs + "; skipping check");
            return Mismatch.NONE;
        }

        if (highest <= ecj.maxClassFileMajor()) {
            LOG.debug("ECJ " + ecj.version() + " supports class file major "
                    + ecj.maxClassFileMajor() + "; webapp max is " + highest + " (compatible)");
            return Mismatch.NONE;
        }

        // Mismatch detected. Build the user-facing warning.
        String warning = buildWarningMessage(ecj, highest);
        if (logger != null) {
            logger.logServerWarning(warning);
        }
        LOG.warn("ECJ/class-file mismatch detected: " + warning);
        return new Mismatch(ecj, highest, warning);
    }

    // ------------------------------------------------------------------ //
    // ECJ bundle detection
    // ------------------------------------------------------------------ //

    /**
     * Locates {@code ecj-VERSION.jar} under {@code catalinaHome/lib} and
     * parses its version. Returns {@code null} when no ecj JAR is present,
     * its name does not match the expected pattern, or its manifest cannot
     * be read.
     */
    @Nullable
    static EcjBundle findEcjBundle(@NotNull Path catalinaHome) {
        Path libDir = catalinaHome.resolve("lib");
        if (!Files.isDirectory(libDir)) return null;

        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(EcjVersionCompat::isEcjJarName)
                    .map(EcjVersionCompat::readEcjBundle)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOG.debug("Could not enumerate " + libDir + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isEcjJarName(@NotNull Path p) {
        return ECJ_JAR_NAME.matcher(p.getFileName().toString()).matches();
    }

    /**
     * Reads the ECJ JAR's version. The JAR's manifest is checked first
     * (most reliable: ECJ writes both {@code Bundle-Version} and
     * {@code Implementation-Version}). Falls back to the version embedded
     * in the file name when the manifest is unavailable.
     */
    @Nullable
    static EcjBundle readEcjBundle(@NotNull Path jar) {
        EcjVersion version = readVersionFromManifest(jar);
        if (version == null) {
            version = readVersionFromFileName(jar);
        }
        if (version == null) {
            LOG.debug("Could not parse ECJ version for " + jar);
            return null;
        }
        int maxJava = maxSupportedJavaFor(version);
        return new EcjBundle(jar, version, classFileMajorFor(maxJava));
    }

    @Nullable
    private static EcjVersion readVersionFromManifest(@NotNull Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return null;
            Attributes a = mf.getMainAttributes();
            String[] candidates = {
                    a.getValue("Bundle-Version"),
                    a.getValue("Implementation-Version"),
                    a.getValue("Specification-Version"),
            };
            for (String c : candidates) {
                EcjVersion v = EcjVersion.parse(c);
                if (v != null) return v;
            }
        } catch (IOException e) {
            LOG.debug("Could not read manifest from " + jar + ": " + e.getMessage());
        }
        return null;
    }

    @Nullable
    private static EcjVersion readVersionFromFileName(@NotNull Path jar) {
        Matcher m = ECJ_JAR_NAME.matcher(jar.getFileName().toString());
        return m.matches() ? EcjVersion.parse(m.group(1)) : null;
    }

    // ------------------------------------------------------------------ //
    // Class file scanning
    // ------------------------------------------------------------------ //

    /**
     * Scans up to {@link #MAX_CLASS_FILE_SAMPLES} class files across the given
     * {@code WEB-INF} directories and returns the highest class file major
     * version observed, or {@code -1} if no class file was sampled.
     *
     * <p>Examines:
     * <ol>
     *   <li>{@code WEB-INF/classes/**}{@code /*.class} — the webapp's own
     *       compiled classes;</li>
     *   <li>{@code WEB-INF/lib/*.jar} — root-level {@code .class} entries.
     *       Multi-Release {@code META-INF/versions/<n>/} slices are skipped
     *       because Tomcat's Jasper compiles against the root-level slice.</li>
     * </ol>
     *
     * <p>Reads only the first 8 bytes of each {@code .class} entry (magic +
     * minor + major) — no full classfile parsing required, no BCEL
     * dependency, no allocation per class beyond a small buffer.
     */
    static int findHighestClassFileMajor(@NotNull Iterable<Path> webInfDirs) {
        int[] maxMajor = {-1};
        int[] sampled = {0};
        for (Path webInf : webInfDirs) {
            sampleClassesDir(webInf.resolve("classes"), maxMajor, sampled);
            if (sampled[0] >= MAX_CLASS_FILE_SAMPLES) break;
            sampleLibDir(webInf.resolve("lib"), maxMajor, sampled);
            if (sampled[0] >= MAX_CLASS_FILE_SAMPLES) break;
        }
        return maxMajor[0];
    }

    private static void sampleClassesDir(@NotNull Path classesDir,
                                         int[] maxMajor,
                                         int[] sampled) {
        if (!Files.isDirectory(classesDir)) return;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (sampled[0] >= MAX_CLASS_FILE_SAMPLES) return;
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".class")) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    int major = readClassFileMajor(in);
                    if (major > maxMajor[0]) maxMajor[0] = major;
                    sampled[0]++;
                } catch (IOException ignored) {
                    // unreadable class - skip
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not walk " + classesDir + ": " + e.getMessage());
        }
    }

    private static void sampleLibDir(@NotNull Path libDir,
                                     int[] maxMajor,
                                     int[] sampled) {
        if (!Files.isDirectory(libDir)) return;
        try (Stream<Path> stream = Files.list(libDir)) {
            for (Path jar : (Iterable<Path>) stream::iterator) {
                if (sampled[0] >= MAX_CLASS_FILE_SAMPLES) return;
                String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar")) continue;
                sampleJar(jar, maxMajor, sampled);
            }
        } catch (IOException e) {
            LOG.debug("Could not enumerate " + libDir + ": " + e.getMessage());
        }
    }

    private static void sampleJar(@NotNull Path jar,
                                  int[] maxMajor,
                                  int[] sampled) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && sampled[0] < MAX_CLASS_FILE_SAMPLES) {
                ZipEntry e = entries.nextElement();
                String entryName = e.getName();
                if (e.isDirectory() || !entryName.endsWith(".class")) continue;
                if (entryName.equals("module-info.class")) continue;
                // Skip Multi-Release slices: Tomcat's Jasper compiles against
                // the root-level slice, so the MR slice's major version is
                // not what triggers the ECJ ClassFormatException.
                if (entryName.startsWith("META-INF/versions/")) continue;

                try (InputStream in = zip.getInputStream(e)) {
                    int major = readClassFileMajor(in);
                    if (major > maxMajor[0]) maxMajor[0] = major;
                    sampled[0]++;
                } catch (IOException ignored) {
                    // unreadable entry - skip
                }
            }
        } catch (ZipException ze) {
            LOG.debug("Skipping unreadable JAR " + jar + ": " + ze.getMessage());
        } catch (IOException e) {
            LOG.debug("I/O error scanning " + jar + ": " + e.getMessage());
        }
    }

    /**
     * Reads bytes 0-7 of a {@code .class} file and returns the major version,
     * or {@code -1} if the magic number does not match. Does not consume
     * beyond the version header.
     */
    static int readClassFileMajor(@NotNull InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != CLASS_FILE_MAGIC) return -1;
        dis.readUnsignedShort(); // minor
        return dis.readUnsignedShort(); // major
    }

    // ------------------------------------------------------------------ //
    // Version mapping
    // ------------------------------------------------------------------ //

    /**
     * Returns the highest Java version supported by the given ECJ release.
     * Lookups round DOWN to the nearest known minor; unknown minors above
     * the highest known entry assume forward compatibility (one Java newer
     * than the highest entry); unknown minors below the lowest entry default
     * to Java 6.
     */
    static int maxSupportedJavaFor(@NotNull EcjVersion version) {
        if (version.major() != 3) {
            // ECJ has been on the 3.x major track since 2004. Anything else
            // is exotic; treat as supporting whatever the latest table entry
            // claims so we do not falsely warn.
            return ECJ_MINOR_TO_JAVA.lastEntry().getValue();
        }
        Map.Entry<Integer, Integer> floor = ECJ_MINOR_TO_JAVA.floorEntry(version.minor());
        if (floor == null) {
            return 6; // pre-3.4
        }
        // Forward compat for unknown future minors: assume +1 java per +2 minor
        // beyond the highest known entry. Conservative: under-promise rather
        // than over-promise.
        Integer ceiling = ECJ_MINOR_TO_JAVA.lastKey();
        if (version.minor() > ceiling) {
            int extraMinors = version.minor() - ceiling;
            return floor.getValue() + extraMinors / 2;
        }
        return floor.getValue();
    }

    /** Maps a Java version (e.g. 11) to the corresponding class file major (55). */
    static int classFileMajorFor(int javaVersion) {
        return javaVersion + JAVA_MAJOR_OFFSET;
    }

    /** Maps a class file major (55) to the corresponding Java version (11). */
    static int javaVersionFor(int classFileMajor) {
        return classFileMajor - JAVA_MAJOR_OFFSET;
    }

    // ------------------------------------------------------------------ //
    // Warning composition
    // ------------------------------------------------------------------ //

    @NotNull
    static String buildWarningMessage(@NotNull EcjBundle ecj, int actualMajor) {
        int actualJava = javaVersionFor(actualMajor);
        int ecjMaxJava = javaVersionFor(ecj.maxClassFileMajor());
        return "Tomcat's bundled ECJ ('" + ecj.jarPath().getFileName()
                + "', version " + ecj.version() + ") supports up to Java " + ecjMaxJava
                + " (class file major " + ecj.maxClassFileMajor()
                + "), but the deployed webapp contains class files compiled for Java "
                + actualJava + " (major " + actualMajor + "). "
                + "JSP compilation will fail with "
                + "'org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException'. "
                + "Resolutions: (1) upgrade to a newer Tomcat (9+ for javax.servlet, "
                + "10+ for jakarta.servlet); (2) replace " + ecj.jarPath().getFileName()
                + " with a newer ECJ from https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/ "
                + "(API-stable across versions); (3) compile the webapp with an older "
                + "Java target (impractical for modern frameworks).";
    }

    // ------------------------------------------------------------------ //
    // Records
    // ------------------------------------------------------------------ //

    /**
     * Parsed ECJ version triple. Patch suffixes (e.g. {@code 3.7.2}) and
     * Eclipse build qualifiers (e.g. {@code 3.7.0.M20120208-0800}) are both
     * accepted; only the leading three numeric components matter.
     */
    record EcjVersion(int major, int minor, int micro) implements Comparable<EcjVersion> {

        @Nullable
        static EcjVersion parse(@Nullable String version) {
            if (version == null) return null;
            String trimmed = version.trim();
            if (trimmed.isEmpty()) return null;
            // Strip Eclipse build qualifier: "3.7.0.M20120208-0800" -> "3.7.0"
            String numericOnly = trimmed.split("[^\\d.]", 2)[0];
            if (numericOnly.endsWith(".")) {
                numericOnly = numericOnly.substring(0, numericOnly.length() - 1);
            }
            String[] parts = numericOnly.split("\\.");
            if (parts.length == 0 || parts[0].isEmpty()) return null;
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                int micro = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                if (major < 0 || minor < 0 || micro < 0) return null;
                return new EcjVersion(major, minor, micro);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public int compareTo(@NotNull EcjVersion other) {
            int c = Integer.compare(major, other.major);
            if (c != 0) return c;
            c = Integer.compare(minor, other.minor);
            if (c != 0) return c;
            return Integer.compare(micro, other.micro);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + micro;
        }
    }

    /** A located ecj-VERSION.jar with its parsed version and computed class-file ceiling. */
    record EcjBundle(@NotNull Path jarPath, @NotNull EcjVersion version, int maxClassFileMajor) {}

    /**
     * Outcome of {@link #check}. {@link #NONE} indicates either no
     * mismatch or insufficient information; otherwise the record carries
     * enough detail for the caller to log or display the diagnostic.
     */
    record Mismatch(@Nullable EcjBundle ecj, int actualClassFileMajor, @Nullable String message) {
        static final Mismatch NONE = new Mismatch(null, -1, null);

        boolean isMismatch() {
            return ecj != null && actualClassFileMajor > ecj.maxClassFileMajor();
        }
    }

}
