package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static com.dev.idea.plugins.tomcat.TomcatConstants.DIR_WEBAPPS;

/**
 * Mirrors Tomcat's bundled web applications from CATALINA_HOME into the per-run
 * CATALINA_BASE so they deploy alongside IDE-managed artifacts.
 *
 * <p>Activated when the user checks <em>"Deploy applications configured in Tomcat
 * instance"</em> on the Server tab. Apps like Tomcat's {@code manager},
 * {@code host-manager}, {@code ROOT}, {@code examples}, and {@code docs} become
 * available to the running instance without hand-copying files.
 *
 * <h2>Two-tier strategy (more efficient than IntelliJ Ultimate's copy-everything
 * approach)</h2>
 * <ol>
 *   <li><b>WAR files</b> → hardlinked into {@code CATALINA_BASE/webapps/} when the
 *       filesystem supports it; falls back to copy otherwise. Hardlinking saves
 *       disk space and keeps shared-install updates visible without stale-symlink
 *       pitfalls.</li>
 *   <li><b>Exploded web apps</b> → <i>not</i> duplicated on disk. A tiny synthesized
 *       context descriptor is written to
 *       {@code CATALINA_BASE/conf/Catalina/localhost/&lt;name&gt;.xml} with an absolute
 *       {@code docBase} pointing straight at the source directory. Zero file
 *       duplication, instant reflection of install-side changes, trivial
 *       cleanup.</li>
 * </ol>
 *
 * <h2>Safety contract</h2>
 * <ul>
 *   <li>IDE artifacts always win context-path collisions. When a shared app would
 *       land at the same context path as an IDE deployment, the shared app is
 *       skipped and the skip is reported.</li>
 *   <li>Author-provided context descriptors in
 *       {@code CATALINA_HOME/conf/Catalina/localhost/*.xml} take precedence over
 *       the synthesized ones — their explicit {@code docBase} settings are
 *       honoured.</li>
 *   <li>A manifest at {@code CATALINA_BASE/.devtomcat-mirror.manifest} records
 *       every path the mirror placed. The next run cleans up manifest entries
 *       first, so toggling the checkbox off (or deleting an app from the
 *       install) removes stale artefacts from the base directory.</li>
 *   <li>Symlinked entries in {@code webapps/} or {@code conf/Catalina/localhost/}
 *       are refused with a warning — they could point outside the install.</li>
 *   <li>A single-entry failure is logged and never aborts the overall mirror.
 *       The run proceeds with whatever entries succeeded.</li>
 *   <li>Source files are never modified. Hardlinks are one-way: only the base
 *       directory's directory entries are created.</li>
 * </ul>
 */
public final class CatalinaHomeMirror {

    private static final Logger LOG = Logger.getInstance(CatalinaHomeMirror.class);

    /** Stored at {@code CATALINA_BASE/.devtomcat-mirror.manifest} — outside conf/ so it survives the conf-tree rewrite. */
    static final String MANIFEST_NAME = ".devtomcat-mirror.manifest";

    private static final String MANIFEST_HEADER =
            "# DevTomcat mirror manifest — do not edit. Generated automatically.\n";
    private static final String CATALINA_LOCALHOST = "conf/Catalina/localhost";
    private static final String WAR_SUFFIX = ".war";

    private CatalinaHomeMirror() {}

    /** Summary of a single mirror run. */
    public static final class Result {
        public final int entriesLinked;
        public final int entriesCopied;
        public final int entriesSynthesized;
        public final int entriesSkipped;
        public final int entriesCleanedUp;
        public final List<String> warnings;

        Result(int linked, int copied, int synthesized, int skipped, int cleanedUp,
               @NotNull List<String> warnings) {
            this.entriesLinked = linked;
            this.entriesCopied = copied;
            this.entriesSynthesized = synthesized;
            this.entriesSkipped = skipped;
            this.entriesCleanedUp = cleanedUp;
            this.warnings = List.copyOf(warnings);
        }

        static Result empty() {
            return new Result(0, 0, 0, 0, 0, List.of());
        }
    }

    /**
     * Applies the mirror according to {@code enabled}. When disabled, any entries
     * left behind by a prior run are cleaned up; when enabled, the mirror places
     * fresh entries and writes a new manifest.
     *
     * @param enabled              checkbox state
     * @param catalinaHome         Tomcat install root
     * @param catalinaBase         per-run base directory
     * @param reservedContextStems filename stems already claimed by IDE artifacts
     *                             ({@code ROOT}, {@code myapp}, ...). Never overwritten.
     * @return summary of what happened; never throws — issues are reported via
     *         {@link Result#warnings}.
     */
    @NotNull
    public static Result apply(boolean enabled,
                               @NotNull Path catalinaHome,
                               @NotNull Path catalinaBase,
                               @NotNull Set<String> reservedContextStems) {
        int cleanedUp = cleanupPreviousMirror(catalinaBase);
        if (!enabled) {
            if (cleanedUp > 0) {
                LOG.info("CatalinaHomeMirror disabled: cleaned up " + cleanedUp +
                        " entr" + (cleanedUp == 1 ? "y" : "ies") + " from previous run");
            }
            return new Result(0, 0, 0, 0, cleanedUp, List.of());
        }

        List<String> warnings = new ArrayList<>();
        Set<String> manifestEntries = new LinkedHashSet<>();
        Set<String> reserved = new HashSet<>();
        for (String stem : reservedContextStems) {
            if (stem != null && !stem.isEmpty()) {
                reserved.add(stem.toLowerCase(Locale.ROOT));
            }
        }

        Counters counters = new Counters();
        Set<String> ctxStemsPlaced = mirrorAuthorContexts(
                catalinaHome, catalinaBase, reserved, manifestEntries, counters, warnings);
        mirrorWebapps(
                catalinaHome, catalinaBase, reserved, ctxStemsPlaced, manifestEntries, counters, warnings);

        try {
            writeManifest(catalinaBase, catalinaHome, manifestEntries);
        } catch (IOException e) {
            warnings.add("Could not write mirror manifest: " + e.getMessage());
            LOG.warn("Mirror manifest write failed", e);
        }

        LOG.info("CatalinaHomeMirror: linked=" + counters.linked +
                ", copied=" + counters.copied +
                ", synthesized=" + counters.synthesized +
                ", skipped=" + counters.skipped +
                ", cleanedUp=" + cleanedUp +
                ", warnings=" + warnings.size());

        return new Result(counters.linked, counters.copied, counters.synthesized,
                counters.skipped, cleanedUp, warnings);
    }

    /**
     * Removes every entry recorded in a previous run's manifest. The manifest
     * itself is deleted afterwards. Missing entries and individual delete
     * failures are logged but do not abort the cleanup.
     *
     * @return number of entries removed (non-negative)
     */
    public static int cleanupPreviousMirror(@NotNull Path catalinaBase) {
        Path manifest = catalinaBase.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return 0;
        }

        List<String> relativePaths;
        try {
            relativePaths = Files.readAllLines(manifest);
        } catch (IOException e) {
            LOG.warn("Could not read mirror manifest at " + manifest + ": " + e.getMessage());
            return 0;
        }

        int removed = 0;
        for (String raw : relativePaths) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.contains("=")) {
                continue;
            }
            // Guard against absolute paths or traversal — defensive only, we write our own file.
            if (trimmed.contains("..") || trimmed.startsWith("/") || trimmed.contains(":")) {
                LOG.warn("Skipping suspicious manifest entry: " + trimmed);
                continue;
            }
            Path target = catalinaBase.resolve(trimmed);
            try {
                if (Files.isDirectory(target) && !Files.isSymbolicLink(target)) {
                    deleteRecursively(target);
                    removed++;
                } else if (Files.deleteIfExists(target)) {
                    removed++;
                }
            } catch (IOException e) {
                LOG.warn("Could not remove mirrored entry '" + target + "': " + e.getMessage());
            }
        }

        try {
            Files.deleteIfExists(manifest);
        } catch (IOException e) {
            LOG.warn("Could not delete mirror manifest: " + e.getMessage());
        }
        return removed;
    }

    // =========================================================================
    // Author-provided context descriptors (conf/Catalina/localhost/*.xml)
    // =========================================================================

    private static Set<String> mirrorAuthorContexts(@NotNull Path catalinaHome,
                                                    @NotNull Path catalinaBase,
                                                    @NotNull Set<String> reserved,
                                                    @NotNull Set<String> manifestEntries,
                                                    @NotNull Counters counters,
                                                    @NotNull List<String> warnings) {
        Set<String> placed = new HashSet<>();
        Path source = catalinaHome.resolve(CATALINA_LOCALHOST);
        if (!Files.isDirectory(source)) {
            return placed;
        }
        Path target = catalinaBase.resolve(CATALINA_LOCALHOST);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            warnings.add("Could not create " + CATALINA_LOCALHOST + ": " + e.getMessage());
            return placed;
        }
        try (Stream<Path> stream = Files.list(source)) {
            List<Path> xmls = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .sorted()
                    .toList();
            for (Path xml : xmls) {
                if (Files.isSymbolicLink(xml)) {
                    warnings.add("Skipped symlink context descriptor: " + xml.getFileName());
                    counters.skipped++;
                    continue;
                }
                String name = xml.getFileName().toString();
                String stem = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (reserved.contains(stem)) {
                    warnings.add("Skipped shared context '" + name +
                            "': conflicts with IDE artifact");
                    counters.skipped++;
                    continue;
                }
                Path dst = target.resolve(name);
                try {
                    placeFile(xml, dst, counters);
                    manifestEntries.add(CATALINA_LOCALHOST + "/" + name);
                    placed.add(stem);
                } catch (IOException e) {
                    warnings.add("Failed to mirror context '" + name + "': " + e.getMessage());
                    LOG.warn("Context mirror failed for " + xml, e);
                }
            }
        } catch (IOException e) {
            warnings.add("Could not enumerate " + CATALINA_LOCALHOST + ": " + e.getMessage());
        }
        return placed;
    }

    // =========================================================================
    // webapps/* — WARs hardlinked, exploded apps synthesized as context XML
    // =========================================================================

    private static void mirrorWebapps(@NotNull Path catalinaHome,
                                      @NotNull Path catalinaBase,
                                      @NotNull Set<String> reserved,
                                      @NotNull Set<String> ctxStemsPlaced,
                                      @NotNull Set<String> manifestEntries,
                                      @NotNull Counters counters,
                                      @NotNull List<String> warnings) {
        Path source = catalinaHome.resolve(DIR_WEBAPPS);
        if (!Files.isDirectory(source)) {
            warnings.add("CATALINA_HOME/" + DIR_WEBAPPS + " not found at " + source);
            return;
        }
        Path target = catalinaBase.resolve(DIR_WEBAPPS);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            warnings.add("Could not create " + DIR_WEBAPPS + ": " + e.getMessage());
            return;
        }
        Path ctxTarget = catalinaBase.resolve(CATALINA_LOCALHOST);
        try {
            Files.createDirectories(ctxTarget);
        } catch (IOException e) {
            warnings.add("Could not create " + CATALINA_LOCALHOST + ": " + e.getMessage());
            return;
        }

        List<Path> entries;
        try (Stream<Path> stream = Files.list(source)) {
            entries = stream.sorted().toList();
        } catch (IOException e) {
            warnings.add("Could not enumerate " + DIR_WEBAPPS + ": " + e.getMessage());
            return;
        }

        Set<String> warStems = collectWarStems(entries);

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isWar = lower.endsWith(WAR_SUFFIX) && Files.isRegularFile(entry);
            boolean isDir = Files.isDirectory(entry) && !Files.isSymbolicLink(entry);
            String stem = (isWar ? name.substring(0, name.length() - 4) : name).toLowerCase(Locale.ROOT);

            if (Files.isSymbolicLink(entry)) {
                warnings.add("Skipped symlink entry in webapps/: " + name);
                counters.skipped++;
                continue;
            }
            if (reserved.contains(stem)) {
                warnings.add("Skipped shared app '" + name + "': conflicts with IDE artifact");
                counters.skipped++;
                continue;
            }
            if (ctxStemsPlaced.contains(stem)) {
                // Author-provided context XML already handles this app — skip silently.
                counters.skipped++;
                continue;
            }

            if (isWar) {
                Path dst = target.resolve(name);
                try {
                    placeFile(entry, dst, counters);
                    manifestEntries.add(DIR_WEBAPPS + "/" + name);
                } catch (IOException e) {
                    warnings.add("Failed to mirror WAR '" + name + "': " + e.getMessage());
                    LOG.warn("WAR mirror failed for " + entry, e);
                }
                continue;
            }

            if (isDir) {
                if (warStems.contains(lower)) {
                    // Exploded directory shadowing a sibling WAR we already hardlinked.
                    // Tomcat unpacks the WAR on demand; don't duplicate.
                    counters.skipped++;
                    continue;
                }
                String ctxFile = sanitizeStem(name) + ".xml";
                Path ctxPath = ctxTarget.resolve(ctxFile);
                try {
                    atomicWriteString(ctxPath, buildSharedContextXml(entry));
                    manifestEntries.add(CATALINA_LOCALHOST + "/" + ctxFile);
                    counters.synthesized++;
                } catch (IOException e) {
                    warnings.add("Failed to synthesize context for '" + name + "': " + e.getMessage());
                    LOG.warn("Context synthesis failed for " + entry, e);
                }
                continue;
            }

            // Regular file that isn't a .war — leave it alone.
            counters.skipped++;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Set<String> collectWarStems(@NotNull List<Path> entries) {
        Set<String> out = new HashSet<>();
        for (Path p : entries) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(WAR_SUFFIX) && Files.isRegularFile(p)) {
                out.add(n.substring(0, n.length() - 4));
            }
        }
        return out;
    }

    /**
     * Writes a minimal {@code <Context docBase="..."/>} descriptor that makes Tomcat
     * deploy the shared application directly from its source directory inside
     * {@code CATALINA_HOME} — no copying required.
     */
    @NotNull
    static String buildSharedContextXml(@NotNull Path sourceDir) {
        String absolute = sourceDir.toAbsolutePath().toString();
        // XML 1.0 forbids "--" inside comments; a dir named e.g. 'my--app' would
        // otherwise produce a malformed comment that Tomcat's Digester rejects.
        String safeName = sourceDir.getFileName().toString().replace("--", "- -");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!-- Generated by DevTomcat: mirrors " + safeName +
                " from CATALINA_HOME. Delete this file and the mirror manifest to remove. -->\n" +
                "<Context docBase=\"" + escapeXmlAttribute(absolute) + "\" reloadable=\"false\" />\n";
    }

    @NotNull
    static String sanitizeStem(@NotNull String name) {
        // Tomcat convention: multi-segment context paths become filenames via '#'.
        // For single-segment webapp directory names this is just the raw name —
        // strip filesystem-invalid characters as a defensive cleanup.
        String clean = name.replace('/', '#').replace('\\', '#');
        StringBuilder sb = new StringBuilder(clean.length());
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '#' || c == '-' || c == '_' || c == '.' || Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "ROOT" : out;
    }

    private static void placeFile(@NotNull Path source, @NotNull Path target,
                                   @NotNull Counters counters) throws IOException {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try {
            Files.createLink(target, source);
            counters.linked++;
        } catch (UnsupportedOperationException | FileSystemException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            counters.copied++;
        }
    }

    private static void writeManifest(@NotNull Path catalinaBase, @NotNull Path catalinaHome,
                                       @NotNull Set<String> entries) throws IOException {
        Path manifest = catalinaBase.resolve(MANIFEST_NAME);
        StringBuilder sb = new StringBuilder(MANIFEST_HEADER);
        sb.append("version=1\n");
        sb.append("catalina.home=").append(catalinaHome.toAbsolutePath()).append('\n');
        sb.append("generatedAt=").append(Instant.now()).append('\n');
        // Entries follow in insertion order so the manifest is deterministic.
        for (String e : entries) {
            sb.append(e).append('\n');
        }

        Path tmp = manifest.resolveSibling(MANIFEST_NAME + ".tmp");
        try {
            Files.writeString(tmp, sb.toString());
            try {
                Files.move(tmp, manifest,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void atomicWriteString(@NotNull Path target, @NotNull String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void deleteRecursively(@NotNull Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attrs) throws IOException {
                if (!current.equals(dir) && attrs.isSymbolicLink()) {
                    Files.deleteIfExists(current);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @NotNull
    private static String escapeXmlAttribute(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class Counters {
        int linked;
        int copied;
        int synthesized;
        int skipped;
    }
}
