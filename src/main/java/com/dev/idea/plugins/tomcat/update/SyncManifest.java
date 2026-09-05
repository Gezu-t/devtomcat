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
 *
 * <h2>Covering-JAR records (class sync only)</h2>
 * Additive record form, one line per covered overlay path:
 * {@code #jar\t<jar-rel>\t<size>\t<mtime>\t<creation>\t<covered-rel>} — the
 * deployed {@code WEB-INF/lib} JAR (relative to the artifact root) that
 * covered a dependency whose files the sync mirrored, its stamp at sync
 * time, and one overlay path (relative to the manifest's base dir) mirrored
 * under that cover. {@link #dropStaleJarOverlays} uses these to delete
 * overlay files whose covering JAR was rebuilt since — Tomcat loads
 * {@code WEB-INF/classes} before {@code WEB-INF/lib}, so a stale overlay
 * silently shadows the newer JAR. The prefix cannot collide with an entry
 * line: entry paths cannot contain a tab (unrepresentable), so no entry
 * line starts with {@code "#jar\t"} except a file literally named
 * {@code #jar} — whose line has 4 fields, not the JAR record's 6, and is
 * therefore never mis-read as a JAR record (it degrades to a preserved
 * unknown entry — the safe direction). A manifest without JAR records
 * (any pre-existing manifest) parses to an empty record map: no drop.
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
     * A deployed {@code WEB-INF/lib} JAR that covered a dependency at sync
     * time: its {@link Stamp} then, and the overlay paths (relative to the
     * manifest's base dir) the sync mirrored while it was the cover.
     */
    record JarCoverage(@NotNull Stamp stamp, @NotNull Set<String> coveredPaths) {}

    /** Line prefix of the covering-JAR record form — see the class javadoc. */
    private static final String JAR_RECORD_PREFIX = "#jar\t";

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
        return reconcile(baseDir, manifest, currentlySynced, Collections.emptyMap());
    }

    /**
     * {@link #reconcile(Path, Path, Set)} plus covering-JAR records: the new
     * manifest also records {@code jarRecords} (the class sync's per-pass
     * JAR-coverage state), replacing any prior records. Pipelines without JAR
     * coverage use the three-argument overload.
     */
    static int reconcile(@NotNull Path baseDir,
                         @NotNull Path manifest,
                         @NotNull Set<String> currentlySynced,
                         @NotNull Map<String, JarCoverage> jarRecords) {
        Map<String, Stamp> unknown = new HashMap<>();
        for (String rel : currentlySynced) unknown.put(rel, Stamp.UNKNOWN);
        return reconcileStamped(baseDir, manifest, unknown, jarRecords).removed();
    }

    /**
     * {@link #reconcile(Path, Path, Set, Map)} with the current stamp of every synced
     * path where the mirror already knows it; only {@link Stamp#UNKNOWN} entries are
     * statted. The manifest is rewritten only when its content would change, so a
     * no-op sync neither stats its files again nor touches the manifest.
     */
    static ReconcileResult reconcileStamped(@NotNull Path baseDir,
                                            @NotNull Path manifest,
                                            @NotNull Map<String, Stamp> currentStamps,
                                            @NotNull Map<String, JarCoverage> jarRecords) {
        Path base = baseDir.toAbsolutePath().normalize();
        Set<String> currentlySynced = currentStamps.keySet();
        Map<String, Stamp> recordedEntries = readStamped(manifest);
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
        for (Map.Entry<String, Stamp> entry : recordedEntries.entrySet()) {
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
        Map<String, Stamp> entries = resolveStamps(base, currentStamps);
        boolean changed = removed > 0
                || !entries.equals(recordedEntries)
                || !jarRecords.equals(readJarRecords(manifest));
        if (changed) {
            writeEntries(manifest, entries, jarRecords);
        }
        return new ReconcileResult(removed, changed);
    }

    /** Stats only the entries whose stamp the caller did not already know. */
    @NotNull
    private static Map<String, Stamp> resolveStamps(@NotNull Path base, @NotNull Map<String, Stamp> known) {
        Map<String, Stamp> out = new HashMap<>(Math.max(16, known.size() * 2));
        for (Map.Entry<String, Stamp> e : known.entrySet()) {
            TomcatProgress.checkCanceled();
            Stamp s = e.getValue();
            out.put(e.getKey(), s == null || s.isUnknown() ? statOrUnknown(base, e.getKey()) : s);
        }
        return out;
    }

    /** The deployed file's current size + mtime + creation time, for stamp recording and matching. */
    @NotNull
    private static Stamp currentStamp(@NotNull Path file) throws IOException {
        return stampOf(Files.readAttributes(file, BasicFileAttributes.class));
    }

    /** A stamp from attributes already in hand — no filesystem access. */
    @NotNull
    static Stamp stampOf(@NotNull BasicFileAttributes attrs) {
        return new Stamp(attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                attrs.creationTime().toMillis());
    }

    /** Outcome of a stamped reconcile: what was deleted, and whether the manifest had to be rewritten. */
    record ReconcileResult(int removed, boolean manifestWritten) {}

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
                // Covering-JAR records are a separate record form, not entries.
                if (isJarRecordLine(line)) continue;
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
     * True for a well-formed covering-JAR record line (see the class javadoc):
     * the {@code #jar} tag plus exactly five payload fields. A file literally
     * named {@code #jar} produces a 4-field entry line and never matches.
     */
    private static boolean isJarRecordLine(@NotNull String line) {
        return line.startsWith(JAR_RECORD_PREFIX) && line.split("\t", -1).length == 6;
    }

    /**
     * The covering-JAR records of a prior sync, keyed by the JAR's
     * artifact-root-relative path. Malformed lines are ignored; a manifest
     * without JAR records (including every pre-existing manifest) yields an
     * empty map — the tolerant direction, no drop.
     */
    @NotNull
    static Map<String, JarCoverage> readJarRecords(@NotNull Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Stamp> stamps = new HashMap<>();
            Map<String, Set<String>> covered = new HashMap<>();
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (!isJarRecordLine(line)) continue;
                String[] parts = line.split("\t", -1);
                try {
                    Stamp stamp = new Stamp(Long.parseLong(parts[2]),
                            Long.parseLong(parts[3]), Long.parseLong(parts[4]));
                    stamps.put(parts[1], stamp);
                    covered.computeIfAbsent(parts[1], k -> new java.util.HashSet<>()).add(parts[5]);
                } catch (NumberFormatException malformed) {
                    // Not a parseable record — ignored, never a drop trigger.
                }
            }
            Map<String, JarCoverage> out = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : covered.entrySet()) {
                out.put(e.getKey(), new JarCoverage(stamps.get(e.getKey()), e.getValue()));
            }
            return out;
        } catch (IOException e) {
            LOG.debug("Sync manifest: could not read " + manifest + " (" + e.getMessage() + ")");
            return Collections.emptyMap();
        }
    }

    /**
     * Deletes overlay files whose covering {@code WEB-INF/lib} JAR was rebuilt
     * since the sync recorded them. The webapp classloader searches
     * {@code WEB-INF/classes} before {@code WEB-INF/lib}, so an overlay class
     * mirrored while an older JAR was deployed keeps shadowing the JAR after a
     * build-tool rebuild — Tomcat serves the old code with no visible cause.
     * Runs BEFORE the mirror pass; the mirror then re-creates an overlay entry
     * only where the IDE output is newer than the JAR again (the caller's
     * covering-JAR mtime floor), restoring "newest wins" in both directions.
     *
     * <p>A JAR counts as rebuilt when its current mtime advanced past the
     * recorded stamp's, or its size changed at an identical mtime. A missing /
     * unreadable JAR, or a record with an unknown stamp, never triggers a drop.
     *
     * <p>Deletion follows the same discipline as {@link #reconcile}: contained
     * in {@code baseDir} (and the JAR path in {@code artifactRoot}), regular
     * non-symlink files only, and ONLY when the deployed file still carries the
     * exact stamp the manifest recorded for it — bytes another producer wrote
     * are never ours to delete. The manifest itself is not rewritten here; the
     * reconcile / refresh at the end of the same pass records the new state.
     *
     * @return the number of stale overlay files deleted
     */
    static int dropStaleJarOverlays(@NotNull Path baseDir,
                                    @NotNull Path manifest,
                                    @NotNull Path artifactRoot) {
        Map<String, JarCoverage> jars = readJarRecords(manifest);
        if (jars.isEmpty()) return 0;
        Path base = baseDir.toAbsolutePath().normalize();
        Path artRoot = artifactRoot.toAbsolutePath().normalize();
        Map<String, Stamp> entries = readStamped(manifest);
        int removed = 0;
        for (Map.Entry<String, JarCoverage> record : jars.entrySet()) {
            TomcatProgress.checkCanceled();
            String jarRel = record.getKey();
            Stamp recorded = record.getValue().stamp();
            if (recorded == null || recorded.isUnknown()) continue;
            Path jar = artRoot.resolve(jarRel).normalize();
            if (!jar.startsWith(artRoot)) {
                LOG.warn("Sync manifest: refusing out-of-tree JAR record '" + jarRel
                        + "' in " + manifest);
                continue;
            }
            Stamp current;
            try {
                if (!Files.isRegularFile(jar)) continue;
                current = currentStamp(jar);
            } catch (IOException e) {
                continue;
            }
            boolean rebuilt = current.mtimeMillis() > recorded.mtimeMillis()
                    || (current.mtimeMillis() == recorded.mtimeMillis()
                        && current.size() != recorded.size());
            if (!rebuilt) continue;

            for (String rel : record.getValue().coveredPaths()) {
                TomcatProgress.checkCanceled();
                Stamp entryStamp = entries.get(rel);
                if (entryStamp == null || entryStamp.isUnknown()) continue;
                Path stale = base.resolve(rel).normalize();
                if (!stale.startsWith(base)) {
                    LOG.warn("Sync manifest: refusing out-of-tree entry '" + rel
                            + "' in " + manifest);
                    continue;
                }
                try {
                    if (!Files.isRegularFile(stale) || Files.isSymbolicLink(stale)) continue;
                    if (!entryStamp.equals(currentStamp(stale))) {
                        // Rewritten by another producer since we recorded it.
                        continue;
                    }
                    Files.delete(stale);
                    removed++;
                    LOG.debug("Sync manifest: dropped overlay " + stale
                            + " shadowed by rebuilt " + jarRel);
                } catch (IOException e) {
                    LOG.debug("Sync manifest: could not drop overlay " + stale
                            + " (" + e.getMessage() + ")");
                }
            }
        }
        return removed;
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
        refresh(baseDir, manifest, contributedPaths, Collections.emptyMap());
    }

    /**
     * {@link #refresh(Path, Path, Set)} plus covering-JAR records. Unlike the
     * trusted reconcile path, prior JAR records are MERGED, not replaced: an
     * incomplete walk means this pass's coverage may miss overlay files an
     * earlier pass mirrored, and losing their coverage link would make a later
     * JAR rebuild unable to drop them (a permanent stale shadow). Per JAR, the
     * covered set is the union of prior and current; the stamp is this pass's
     * when the JAR was seen, else the prior one.
     */
    static void refresh(@NotNull Path baseDir,
                        @NotNull Path manifest,
                        @NotNull Set<String> contributedPaths,
                        @NotNull Map<String, JarCoverage> jarRecords) {
        Map<String, Stamp> unknown = new HashMap<>();
        for (String rel : contributedPaths) unknown.put(rel, Stamp.UNKNOWN);
        refreshStamped(baseDir, manifest, unknown, jarRecords);
    }

    /** {@link #refresh(Path, Path, Set, Map)} with known stamps; only {@link Stamp#UNKNOWN} entries are statted. */
    static void refreshStamped(@NotNull Path baseDir,
                               @NotNull Path manifest,
                               @NotNull Map<String, Stamp> contributedStamps,
                               @NotNull Map<String, JarCoverage> jarRecords) {
        Path base = baseDir.toAbsolutePath().normalize();
        Map<String, Stamp> merged = new HashMap<>(readStamped(manifest));
        merged.putAll(resolveStamps(base, contributedStamps));
        Map<String, JarCoverage> mergedJars = new HashMap<>(readJarRecords(manifest));
        for (Map.Entry<String, JarCoverage> e : jarRecords.entrySet()) {
            JarCoverage prior = mergedJars.get(e.getKey());
            if (prior == null) {
                mergedJars.put(e.getKey(), e.getValue());
            } else {
                Set<String> union = new java.util.HashSet<>(prior.coveredPaths());
                union.addAll(e.getValue().coveredPaths());
                mergedJars.put(e.getKey(), new JarCoverage(e.getValue().stamp(), union));
            }
        }
        writeEntries(manifest, merged, mergedJars);
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
        writeStamped(manifest, base, synced, Collections.emptyMap());
    }

    /** {@link #writeStamped(Path, Path, Set)} plus covering-JAR records. */
    static void writeStamped(@NotNull Path manifest, @NotNull Path base,
                             @NotNull Set<String> synced,
                             @NotNull Map<String, JarCoverage> jarRecords) {
        Map<String, Stamp> entries = new HashMap<>();
        for (String rel : synced) {
            TomcatProgress.checkCanceled();
            entries.put(rel, statOrUnknown(base, rel));
        }
        writeEntries(manifest, entries, jarRecords);
    }

    @NotNull
    private static Stamp statOrUnknown(@NotNull Path base, @NotNull String rel) {
        return stampOf(base.resolve(rel));
    }

    /** The file's current stamp, or {@link Stamp#UNKNOWN} when it cannot be stat'ed. */
    @NotNull
    static Stamp stampOf(@NotNull Path file) {
        try {
            return currentStamp(file);
        } catch (IOException e) {
            return Stamp.UNKNOWN;
        }
    }

    private static void writeEntries(@NotNull Path manifest,
                                     @NotNull Map<String, Stamp> entries,
                                     @NotNull Map<String, JarCoverage> jarRecords) {
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
            // Covering-JAR records after the entries, sorted for determinism.
            List<String> jarKeys = new ArrayList<>(jarRecords.keySet());
            Collections.sort(jarKeys);
            for (String jarRel : jarKeys) {
                if (!representable(jarRel)) continue;
                JarCoverage coverage = jarRecords.get(jarRel);
                Stamp stamp = coverage.stamp();
                List<String> covered = new ArrayList<>(coverage.coveredPaths());
                Collections.sort(covered);
                for (String rel : covered) {
                    if (!representable(rel)) continue;
                    lines.add(JAR_RECORD_PREFIX + jarRel
                            + '\t' + stamp.size()
                            + '\t' + stamp.mtimeMillis()
                            + '\t' + stamp.creationMillis()
                            + '\t' + rel);
                }
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
