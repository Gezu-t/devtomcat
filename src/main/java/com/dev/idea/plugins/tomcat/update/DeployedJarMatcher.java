package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_CLASS;
import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_JAR;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_LIB_PATH;

/**
 * Which deployed {@code WEB-INF/lib} jar packages a module: decided by content
 * (the jar holds the module's compiled classes), so the build may name the jar
 * anything. The {@code <artifactId>-<version>.jar} name rule is the fallback
 * whenever content cannot identify a jar, so no case decides worse than before.
 */
final class DeployedJarMatcher {

    private static final Logger LOG = Logger.getInstance(DeployedJarMatcher.class);

    static final int SAMPLE_SIZE = 12;
    /** Class files collected per module before sampling; bounds the walk of a large output root. */
    static final int WALK_LIMIT = 256;
    private static final String MODULE_DESCRIPTOR = "module-info.class";
    private static final String PACKAGE_DESCRIPTOR = "package-info.class";

    /** The jar (null: not packaged here) and whether the jar's content decided it. */
    record Match(@Nullable String jar, boolean byContent) {
        static final Match NONE = new Match(null, false);
    }

    private final Path webInfLib;
    private final Map<String, String> jarsByKey;
    private final List<String> jarNames;

    DeployedJarMatcher(@NotNull Path artifactRoot, @NotNull Map<String, String> deployedLibraryJars) {
        this.webInfLib = artifactRoot.resolve(WEB_INF_LIB_PATH);
        this.jarsByKey = deployedLibraryJars;
        this.jarNames = listJars(webInfLib, deployedLibraryJars);
    }

    /** Every jar on disk is a candidate: the key map keeps one name per library, a leftover version is another. */
    @NotNull
    private static List<String> listJars(@NotNull Path webInfLib, @NotNull Map<String, String> deployedLibraryJars) {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(webInfLib)) {
            try (Stream<Path> stream = Files.list(webInfLib)) {
                stream.map(p -> p.getFileName().toString())
                      .filter(n -> n.endsWith(EXT_JAR))
                      .forEach(names::add);
            } catch (IOException | UncheckedIOException e) {
                LOG.debug("Jar match: could not list " + webInfLib + ": " + e.getMessage());
            }
        }
        for (String name : deployedLibraryJars.values()) {
            if (!names.contains(name)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    @NotNull
    Match jarFor(@NotNull Collection<Path> outputRoots, @Nullable String artifactName) {
        if (jarNames.isEmpty()) return Match.NONE;
        String named = byName(artifactName);
        List<String> sample = sampleClassPaths(outputRoots);
        if (sample.isEmpty()) return new Match(named, false);

        record Candidate(String jar, int hits, int size, boolean named) {}
        List<Candidate> candidates = new ArrayList<>();
        boolean anyReadable = false;
        for (String jar : jarNames) {
            TomcatProgress.checkCanceled();
            Path path = webInfLib.resolve(jar);
            int hits = WarClasspathDuplicateScanner.countContained(path, sample);
            if (hits < 0) continue;
            anyReadable = true;
            // At least half the sample: a jar built before the module grew still counts.
            if (hits * 2 < sample.size()) continue;
            candidates.add(new Candidate(jar, hits, WarClasspathDuplicateScanner.entryCount(path), jar.equals(named)));
        }
        // No jar identified by content: the name rule is the last word (a jar can predate a
        // package rename); only with no name match either is the module known to be unpackaged.
        if (!anyReadable || candidates.isEmpty()) {
            return named != null ? new Match(named, false) : new Match(null, anyReadable);
        }
        // Most of the sample wins; then the name-rule jar; then the smaller jar (the module's own, not a fat one).
        candidates.sort(Comparator.<Candidate>comparingInt(c -> -c.hits())
                .thenComparing(c -> !c.named())
                .thenComparingInt(Candidate::size)
                .thenComparing(Candidate::jar));
        return new Match(candidates.get(0).jar(), true);
    }

    @Nullable
    private String byName(@Nullable String artifactName) {
        if (artifactName == null) return null;
        return jarsByKey.get(LibraryArtifactNames.libraryArtifactKey(artifactName + EXT_JAR));
    }

    /** Up to {@link #SAMPLE_SIZE} class paths spread over the first {@link #WALK_LIMIT} found; descriptors excluded. */
    @NotNull
    static List<String> sampleClassPaths(@NotNull Collection<Path> outputRoots) {
        List<String> found = new ArrayList<>();
        for (Path root : outputRoots) {
            if (found.size() >= WALK_LIMIT) break;
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                Iterator<Path> it = walk.iterator();
                while (found.size() < WALK_LIMIT && it.hasNext()) {
                    TomcatProgress.checkCanceled();
                    Path p = it.next();
                    Path fileName = p.getFileName();
                    String name = fileName == null ? "" : fileName.toString();
                    if (!name.endsWith(EXT_CLASS) || MODULE_DESCRIPTOR.equals(name) || PACKAGE_DESCRIPTOR.equals(name)) continue;
                    if (!Files.isRegularFile(p)) continue;
                    found.add(root.relativize(p).toString().replace('\\', '/'));
                }
            } catch (IOException | UncheckedIOException | SecurityException e) {
                LOG.debug("Jar match: could not walk " + root + ": " + e.getMessage());
            }
        }
        if (found.size() <= SAMPLE_SIZE) return found;
        Collections.sort(found);
        List<String> sample = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            sample.add(found.get((int) ((long) i * found.size() / SAMPLE_SIZE)));
        }
        return sample;
    }
}
