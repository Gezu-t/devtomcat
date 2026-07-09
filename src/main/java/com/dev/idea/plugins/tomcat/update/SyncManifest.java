package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persisted record of the forward-slash relative paths a sync pipeline wrote
 * into a deployed tree on its previous run — the authority that makes
 * stale-file cleanup safe for both sync pipelines
 * ({@link DeployedClassesSync} and {@link WebResourcesSync}).
 *
 * <h2>Why a manifest at all</h2>
 * A deployed tree is populated by TWO writers: the build tool
 * ({@code mvn package} / {@code gradle war} explodes classes, resources,
 * filtered web resources, WAR overlays, frontend build output) and this
 * plugin's incremental sync (mirroring fresh compile output / webapp
 * sources). The sync's source walk enumerates only what the <em>sync</em>
 * covers — it is NOT a complete authority over the deployed tree. A
 * "delete everything the walk didn't visit" orphan pass therefore deletes
 * build-produced files the sync never owned: deployed classes vanish
 * ({@code ClassNotFoundException}), filtered resources and frontend assets
 * vanish (silent 404s). The manifest closes that hole: cleanup may delete
 * ONLY a file this sync itself wrote on a prior run and no longer produces
 * — provably stale, never legitimately deployed by someone else.
 *
 * <h2>Entry stamps — proof the file is still ours</h2>
 * Each entry records the deployed file's size + mtime + creation time at
 * the moment the sync recorded it. {@link #reconcile} deletes an entry's
 * file only when the deployed copy STILL carries that exact stamp — path
 * membership alone is not proof of ownership; the stamp is. The three
 * fields are complementary: size/mtime catch in-place rewrites (build
 * filtering, editors), while creation time catches REPLACEMENT copies —
 * Maven/Gradle copiers preserve the source's mtime (as does this sync), so
 * a file migrated unmodified into a build-owned location re-lands with the
 * identical size+mtime, but the copier necessarily creates a new file and
 * cannot fake its creation time. On a filesystem without birth-time
 * support the JDK returns a consistent fallback for both record and check,
 * so nothing false-deletes; a flaky value only ever mismatches, which
 * preserves.
 *
 * <h2>Failure posture</h2>
 * Every failure degrades toward <em>not deleting</em>: an absent or
 * unreadable manifest reads as an empty prior set (nothing is a proven
 * orphan); an entry whose stamp is unknown or unverifiable is preserved;
 * a failed write only means the next run cannot clean up. A fresh or
 * clean-rebuilt deployment has no manifest, so the first sync deletes
 * nothing — and a clean rebuild has no stale files to clean anyway.
 *
 * <h2>Containment</h2>
 * {@link #reconcile} refuses to delete any path that escapes the deployed
 * base directory, so a corrupt or hand-edited manifest entry
 * (absolute path, {@code ..} traversal) can never reach outside the
 * deployment it tracks.
 *
 * <h2>Format</h2>
 * One entry per line:
 * {@code <relative-path>\t<size>\t<mtimeMillis>\t<creationMillis>}, UTF-8.
 * The relative path is recorded VERBATIM (no trimming on read) so the
 * round-trip is exact even for whitespace-edged filenames — a trimmed
 * read would alias a DIFFERENT deployed file and delete it. Filenames
 * containing tab / CR / LF cannot be represented in the line format and
 * are skipped on write (such a file is simply never eligible for cleanup —
 * the safe direction). A line without a parseable stamp is treated as a
 * known path with an UNKNOWN stamp: never deletable.
 */
final class SyncManifest {

    private static final Logger LOG = Logger.getInstance(SyncManifest.class);

    private SyncManifest() {}

    /**
     * Size + mtime + creation time of a deployed file at the moment the sync
     * recorded it. {@link #UNKNOWN} marks an entry whose deployed state could
     * not be captured (file absent at record time, unparseable manifest line,
     * test-seeded bare path) — such entries are never deletable.
     */
    record Stamp(long size, long mtimeMillis, long creationMillis) {
        static final Stamp UNKNOWN = new Stamp(-1L, -1L, -1L);

        boolean isUnknown() {
            return size < 0;
        }
    }

    /**
     * Reconciles stale synced files using the manifest of what this sync wrote on
     * its previous run, then records {@code currentlySynced} (freshly stamped) as
     * the new manifest. A file is deleted only when ALL of the following hold:
     *
     * <ul>
     *   <li>its path was in the prior manifest but is NOT in
     *       {@code currentlySynced} — we mirrored it before and no longer do
     *       (removed from source); files never recorded by the sync
     *       (build-produced classes, filtered web resources, WAR-overlay content,
     *       frontend build output, another pipeline's manifest) are never
     *       candidates;</li>
     *   <li>it resolves inside {@code baseDir} — a corrupt/hand-edited entry
     *       can never step outside the deployment;</li>
     *   <li>it is a regular non-symlink file — the sync never writes symlinks,
     *       so a symlink at a manifested path means someone else owns it now;</li>
     *   <li>it is not the same physical file as a currently-synced path under a
     *       different spelling — a case-only / Unicode-normalization rename on a
     *       case-insensitive filesystem must not delete the fresh copy;</li>
     *   <li>its deployed size + mtime + creation time still match the recorded
     *       stamp — a file rewritten OR replaced by another producer (the
     *       build) at a formerly-synced path is theirs now, not ours to
     *       delete.</li>
     * </ul>
     *
     * @return the number of stale files deleted
     */
    static int reconcile(@NotNull Path baseDir,
                         @NotNull Path manifest,
                         @NotNull Set<String> currentlySynced) {
        Path base = baseDir.toAbsolutePath().normalize();
        // Folded-key view of the current synced set (lowercase + Unicode NFC),
        // built lazily on the first stale candidate — the common no-stale run
        // never pays for it. On case-insensitive filesystems (macOS, Windows) a
        // case-only rename (Index.jsp -> index.jsp) makes the OLD manifest entry
        // and the NEW synced path the SAME physical file — deleting the "stale"
        // old name would delete the freshly-synced copy. A folded-key hit
        // nominates a candidate; Files.isSameFile below is the decider, so on a
        // case-sensitive filesystem (where the old name really is a distinct
        // stale file) deletion still proceeds.
        Map<String, String> foldedSynced = null;
        int removed = 0;
        for (Map.Entry<String, Stamp> entry : readStamped(manifest).entrySet()) {
            // Cooperative cancellation: this runs under the launch-prep modal
            // and the update task's indicator, same as the mirror walks.
            TomcatProgress.checkCanceled();
            String rel = entry.getKey();
            if (currentlySynced.contains(rel)) {
                continue;
            }
            Path stale = base.resolve(rel).normalize();
            if (!stale.startsWith(base)) {
                // Corrupt or hostile entry — never step outside the deployment.
                LOG.warn("Sync manifest: refusing out-of-tree entry '" + rel
                        + "' in " + manifest);
                continue;
            }
            try {
                if (!Files.isRegularFile(stale) || Files.isSymbolicLink(stale)) {
                    continue;
                }
                if (foldedSynced == null) {
                    foldedSynced = foldedView(currentlySynced);
                }
                String candidate = foldedSynced.get(foldKey(rel));
                if (candidate != null && Files.isSameFile(stale, base.resolve(candidate))) {
                    // Same physical file under a different spelling — a case or
                    // Unicode-normalization rename, not a stale file. The new
                    // manifest written below records the new spelling.
                    LOG.debug("Sync manifest: '" + rel + "' is the same file as synced '"
                            + candidate + "' — preserved");
                    continue;
                }
                Stamp recorded = entry.getValue();
                if (recorded.isUnknown() || !recorded.equals(currentStamp(stale))) {
                    // The deployed copy is not (provably) the bytes we recorded —
                    // another producer rewrote this path since, so it's theirs.
                    LOG.debug("Sync manifest: '" + rel
                            + "' no longer matches the recorded stamp — preserved");
                    continue;
                }
                Files.delete(stale);
                removed++;
                LOG.debug("Sync manifest: removed stale " + stale);
            } catch (IOException e) {
                // Includes isSameFile / stamp-probe failures: cannot prove the
                // entry is a distinct stale file we own — err toward preserving.
                LOG.debug("Sync manifest: could not delete stale " + stale
                        + " (" + e.getMessage() + ")");
            }
        }
        writeStamped(manifest, base, currentlySynced);
        return removed;
    }

    /** The deployed file's current size + mtime + creation time, for stamp recording and matching. */
    @NotNull
    private static Stamp currentStamp(@NotNull Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new Stamp(attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                attrs.creationTime().toMillis());
    }

    /**
     * Rename-detection key: Unicode NFC + lowercase. Two relative paths with
     * the same key <em>may</em> be the same physical file on a
     * case-insensitive / normalization-insensitive filesystem; the caller
     * confirms with {@link Files#isSameFile} before trusting it.
     */
    @NotNull
    private static String foldKey(@NotNull String rel) {
        return Normalizer.normalize(rel, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    @NotNull
    private static Map<String, String> foldedView(@NotNull Set<String> synced) {
        Map<String, String> folded = new HashMap<>();
        for (String p : synced) {
            folded.put(foldKey(p), p);
        }
        return folded;
    }

    /**
     * Reads the prior manifest as path → recorded stamp. A line without a
     * parseable {@code \t<size>\t<mtime>\t<creation>} tail is kept as a path
     * with {@link Stamp#UNKNOWN} (never deletable). Paths are NOT trimmed —
     * the read must be the exact inverse of the write, or a whitespace-edged
     * filename would alias (and delete) a different deployed file. Empty
     * on absence or read failure.
     */
    @NotNull
    static Map<String, Stamp> readStamped(@NotNull Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Stamp> out = new HashMap<>();
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                // The stamp is the LAST three tab-separated fields; parsing from
                // the right keeps the path unambiguous (tabs can't be in it —
                // representable() refuses them on write).
                int creationTab = line.lastIndexOf('\t');
                int mtimeTab = creationTab > 0 ? line.lastIndexOf('\t', creationTab - 1) : -1;
                int sizeTab = mtimeTab > 0 ? line.lastIndexOf('\t', mtimeTab - 1) : -1;
                if (sizeTab > 0) {
                    try {
                        long creation = Long.parseLong(line.substring(creationTab + 1));
                        long mtime = Long.parseLong(line.substring(mtimeTab + 1, creationTab));
                        long size = Long.parseLong(line.substring(sizeTab + 1, mtimeTab));
                        out.put(line.substring(0, sizeTab), new Stamp(size, mtime, creation));
                        continue;
                    } catch (NumberFormatException notAStamp) {
                        // Fall through: the whole line is a bare path.
                    }
                }
                out.put(line, Stamp.UNKNOWN);
            }
            return out;
        } catch (IOException e) {
            LOG.debug("Sync manifest: could not read " + manifest + " (" + e.getMessage() + ")");
            return Collections.emptyMap();
        }
    }

    /** The recorded paths of a prior sync (stamps dropped), or an empty set. */
    @NotNull
    static Set<String> read(@NotNull Path manifest) {
        return readStamped(manifest).keySet();
    }

    /**
     * Refreshes the manifest WITHOUT deleting anything — for runs whose walk
     * could not be trusted, where the caller defers {@link #reconcile}. The
     * mirror still ran and may have overwritten deployed files; skipping the
     * manifest update entirely would leave their recorded stamps stale, and a
     * stale stamp means the file can never be cleaned once it leaves the
     * synced set (permanent leak — for class sync a still-loadable stale
     * class). New manifest = prior entries (recorded stamps KEPT — re-stamping
     * a path this run didn't touch would launder another producer's rewrite
     * into "ours") ∪ {@code contributedPaths} freshly stamped.
     */
    static void refresh(@NotNull Path baseDir,
                        @NotNull Path manifest,
                        @NotNull Set<String> contributedPaths) {
        Path base = baseDir.toAbsolutePath().normalize();
        Map<String, Stamp> merged = new HashMap<>(readStamped(manifest));
        for (String rel : contributedPaths) {
            TomcatProgress.checkCanceled();
            merged.put(rel, statOrUnknown(base, rel));
        }
        writeEntries(manifest, merged);
    }

    /**
     * Records {@code synced} as the new manifest, stamping each entry with the
     * deployed file's current size + mtime + creation time (resolved against
     * {@code base}). An entry whose file cannot be stat'ed records
     * {@link Stamp#UNKNOWN} (never deletable); an entry whose name the line
     * format cannot represent (tab / CR / LF) is skipped entirely. Sorted for
     * determinism; failures are debug-logged and non-fatal (the next run just
     * cannot clean up).
     */
    static void writeStamped(@NotNull Path manifest, @NotNull Path base, @NotNull Set<String> synced) {
        Map<String, Stamp> entries = new HashMap<>();
        for (String rel : synced) {
            TomcatProgress.checkCanceled();
            entries.put(rel, statOrUnknown(base, rel));
        }
        writeEntries(manifest, entries);
    }

    @NotNull
    private static Stamp statOrUnknown(@NotNull Path base, @NotNull String rel) {
        try {
            return currentStamp(base.resolve(rel));
        } catch (IOException e) {
            return Stamp.UNKNOWN;
        }
    }

    private static void writeEntries(@NotNull Path manifest, @NotNull Map<String, Stamp> entries) {
        try {
            Path parent = manifest.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> sorted = new ArrayList<>(entries.keySet());
            Collections.sort(sorted);
            List<String> lines = new ArrayList<>(sorted.size());
            for (String rel : sorted) {
                if (!representable(rel)) continue;
                Stamp stamp = entries.get(rel);
                lines.add(rel + '\t' + stamp.size()
                        + '\t' + stamp.mtimeMillis()
                        + '\t' + stamp.creationMillis());
            }
            Files.write(manifest, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Sync manifest: could not write " + manifest + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Records bare paths with {@link Stamp#UNKNOWN} — entries a later
     * {@link #reconcile} will treat as known-but-unverifiable and never delete.
     * For seeding known-path sets (tests, external tooling); the production
     * pipelines always go through {@link #reconcile}, which writes real stamps.
     */
    static void write(@NotNull Path manifest, @NotNull Set<String> synced) {
        try {
            Path parent = manifest.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            for (String rel : synced) {
                if (representable(rel)) lines.add(rel);
            }
            Collections.sort(lines);
            Files.write(manifest, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Sync manifest: could not write " + manifest + " (" + e.getMessage() + ")");
        }
    }

    /** A name the one-entry-per-line, tab-separated format can hold losslessly. */
    private static boolean representable(@NotNull String rel) {
        if (rel.indexOf('\t') >= 0 || rel.indexOf('\n') >= 0 || rel.indexOf('\r') >= 0) {
            LOG.debug("Sync manifest: name not representable in the manifest format,"
                    + " never eligible for cleanup: '" + rel + "'");
            return false;
        }
        return true;
    }
}
