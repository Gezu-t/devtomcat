package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_JAR;

/**
 * What the configured Tomcat ships in {@code lib/} and {@code bin/}: artifact keys and
 * class entries. A jar the container already provides must not be injected into a
 * webapp, or its classes and web fragments load twice. Read from the install itself,
 * so any Tomcat version, fork or renamed jar is covered without a name list.
 */
final class ContainerLibs {

    private static final Logger LOG = Logger.getInstance(ContainerLibs.class);

    static final ContainerLibs EMPTY = new ContainerLibs(Set.of(), Set.of(), Set.of());

    private final Set<String> keys;
    private final Set<String> classEntries;
    /** Package directories of the shipped classes: Tomcat's own copy of a spec API differs in helper classes, not in packages. */
    private final Set<String> packages;

    private ContainerLibs(@NotNull Set<String> keys, @NotNull Set<String> classEntries, @NotNull Set<String> packages) {
        this.keys = keys;
        this.classEntries = classEntries;
        this.packages = packages;
    }

    /** Every jar in the directories: its key by name, its classes by content (an unreadable jar contributes its key only). */
    @NotNull
    static ContainerLibs read(@NotNull Path... dirs) {
        Set<String> keys = new HashSet<>();
        Set<String> classes = new HashSet<>();
        Set<String> packages = new HashSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path jar : (Iterable<Path>) stream::iterator) {
                    String name = jar.getFileName().toString();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(EXT_JAR)) continue;
                    TomcatProgress.checkCanceled();
                    keys.add(LibraryArtifactNames.libraryArtifactKey(name));
                    for (String entry : WarClasspathDuplicateScanner.classEntries(jar)) {
                        classes.add(entry);
                        packages.add(packageOf(entry));
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                LOG.debug("Could not read container jars in " + dir + ": " + e.getMessage());
            }
        }
        return new ContainerLibs(Set.copyOf(keys), Set.copyOf(classes), Set.copyOf(packages));
    }

    private static String packageOf(@NotNull String classEntry) {
        int slash = classEntry.lastIndexOf('/');
        return slash < 0 ? "" : classEntry.substring(0, slash);
    }

    boolean isEmpty() {
        return keys.isEmpty();
    }

    @NotNull
    Set<String> keys() {
        return keys;
    }

    /** Same artifact key as a shipped jar, or at least half of its classes are shipped, by class or by package. */
    boolean provides(@NotNull Path jar) {
        if (keys.contains(LibraryArtifactNames.libraryArtifactKey(jar.getFileName().toString()))) return true;
        if (classEntries.isEmpty()) return false;
        List<String> classes = WarClasspathDuplicateScanner.classEntries(jar);
        if (classes.isEmpty()) return false;
        int hits = 0;
        for (String entry : classes) {
            if (classEntries.contains(entry) || packages.contains(packageOf(entry))) hits++;
        }
        return hits * 2 >= classes.size();
    }
}
