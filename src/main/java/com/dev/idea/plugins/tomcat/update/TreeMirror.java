package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The single file-tree mirror engine behind {@link DeployedClassesSync}
 * (compiled output → {@code WEB-INF/classes/}) and {@link WebResourcesSync}
 * (webapp sources → exploded artifact root). Walks a source directory and
 * copies every file that is missing in the destination or differs from its
 * source counterpart, under a caller-supplied {@link Policy}.
 *
 * <p>Everything the two syncs share lives here — the nesting guard, the
 * no-follow-symlinks walk, cooperative cancellation, the contributed-paths
 * bookkeeping the manifest reconcile depends on, the mtime/size copy gate,
 * the attribute-free copy with mtime mirroring, and the per-file error
 * swallowing. Everything they legitimately differ on is a {@link Policy}
 * knob; the policies must NOT be merged (the class sync must not gain the
 * web-resources file exclusions or subtree skips, the web-resources sync
 * must not gain the copy veto).
 */
final class TreeMirror {

    private static final Logger LOG = Logger.getInstance(TreeMirror.class);

    private TreeMirror() {}

    /**
     * The per-caller knobs of the mirror engine.
     *
     * @param logPrefix           prefix for every log line this engine emits
     *                            ({@code "Class sync"} / {@code "Web resources sync"}),
     *                            keeping each caller's log lines byte-identical to
     *                            its pre-unification walker.
     * @param subtreeSkips        forward-slash-normalized relative directory
     *                            prefixes pruned in {@code preVisitDirectory}
     *                            (subtrees owned by other pipelines). Empty set
     *                            = walk everything.
     * @param fileExclude         files to skip WITHOUT contributing: applied
     *                            AFTER the symlink check and BEFORE the path is
     *                            recorded in {@code contributedPaths}, so an
     *                            excluded file is absent from the manifest and
     *                            the caller's reconcile deletes any copy an
     *                            earlier, more permissive sync left behind.
     *                            {@code null} = no exclusions.
     * @param copyVeto            veto applied ONLY to copy candidates, AFTER the
     *                            {@link #shouldCopy} gate — this ordering is a
     *                            performance contract: the veto may read the whole
     *                            file (the ECJ stub scan), so running it before the
     *                            cheap mtime/size gate would read the entire
     *                            deployed classpath off disk on every no-op sync.
     *                            A vetoed candidate counts into {@code brokenSkipped}
     *                            and is not copied (but stays contributed).
     *                            {@code null} = no veto.
     * @param copyOnDstStatError  what {@link #shouldCopy} does when reading the
     *                            destination's attributes fails with a generic
     *                            {@link IOException}: {@code true} = copy anyway
     *                            (safer than leaving stale code — class-sync
     *                            policy), {@code false} = propagate, so the
     *                            engine's per-file catch skips the file (it stays
     *                            contributed — web-resources policy).
     * @param sourceMtimeFloorMillis source files whose mtime is not strictly
     *                            newer than this are skipped WITHOUT contributing
     *                            (same semantics as {@code fileExclude}) — the
     *                            class sync's covering-JAR gate: a dependency's
     *                            loose classes may overlay its deployed
     *                            {@code WEB-INF/lib} JAR only when the IDE output
     *                            is newer than the JAR, or stale overlay classes
     *                            would shadow a freshly-rebuilt JAR forever.
     *                            {@link Long#MIN_VALUE} = no floor.
     */
    record Policy(@NotNull String logPrefix,
                  @NotNull Set<String> subtreeSkips,
                  @Nullable Predicate<Path> fileExclude,
                  @Nullable Predicate<Path> copyVeto,
                  boolean copyOnDstStatError,
                  long sourceMtimeFloorMillis,
                  @Nullable Map<String, SyncManifest.Stamp> recordedStamps) {

        /** No-floor convenience — the shape every pre-floor caller used. */
        Policy(@NotNull String logPrefix,
               @NotNull Set<String> subtreeSkips,
               @Nullable Predicate<Path> fileExclude,
               @Nullable Predicate<Path> copyVeto,
               boolean copyOnDstStatError) {
            this(logPrefix, subtreeSkips, fileExclude, copyVeto, copyOnDstStatError, Long.MIN_VALUE, null);
        }

        /** Floor without recorded stamps — every file goes through the destination stat. */
        Policy(@NotNull String logPrefix,
               @NotNull Set<String> subtreeSkips,
               @Nullable Predicate<Path> fileExclude,
               @Nullable Predicate<Path> copyVeto,
               boolean copyOnDstStatError,
               long sourceMtimeFloorMillis) {
            this(logPrefix, subtreeSkips, fileExclude, copyVeto, copyOnDstStatError, sourceMtimeFloorMillis, null);
        }
    }

    /**
     * Result of mirroring one source root.
     *
     * <p>{@code contributedPaths} is the set of forward-slash-normalized
     * relative paths the source walk visited (regardless of whether each was
     * actually copied). The union across every source root is what the
     * stale-file reconcile ({@link SyncManifest#reconcile}) records as this
     * run's synced set — a file previously in the manifest but no longer
     * contributed was removed from source and is cleaned.
     *
     * <p>{@code brokenSkipped} counts copy candidates the policy's
     * {@code copyVeto} refused (the class sync's ECJ "compile-with-errors"
     * stubs); always {@code 0} for a policy without a veto.
     *
     * <p>{@code walkFailed} marks a root whose walk could not be trusted to
     * have enumerated its full contribution — a vanished/non-directory
     * source, a nesting-guard refusal, any unvisitable entry, or an outer
     * walk failure. The caller must NOT reconcile when any source root
     * reports this, or a partial walk would make the caller delete deployed
     * files the failed root legitimately owns but did not get to visit.
     */
    record MirrorResult(int copied,
                        int brokenSkipped,
                        @NotNull Set<String> contributedPaths,
                        boolean walkFailed,
                        @NotNull Map<String, SyncManifest.Stamp> stamps,
                        int excludedNewerThanFloor) {
        /** Nothing contributed and the walk cannot be trusted — the caller must defer the reconcile. */
        static final MirrorResult FAILED = new MirrorResult(
                0, 0, Collections.emptySet(), true, Collections.emptyMap(), 0);
    }

    /**
     * Walks {@code src} and copies every regular file that is missing in
     * {@code dst} or newer / different size than its destination counterpart,
     * subject to {@code p}'s skips, exclusions, and veto. Symlinks (file and
     * directory) are never followed and never copied.
     *
     * <p>The walker swallows per-file IOExceptions to avoid aborting a sync
     * mid-way when one file is briefly locked (Windows file-handles, IDE
     * indexing). Each failure is debug-logged with the source path so the
     * issue is visible in {@code idea.log} without polluting the run
     * console.
     */
    @NotNull
    static MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst, @NotNull Policy p) {
        // Source vanished / not a directory: treat as a failed walk so the
        // caller defers the stale-file reconcile for the artifact rather than
        // deleting files this root should have contributed.
        if (!Files.isDirectory(src)) return MirrorResult.FAILED;

        // Pre-flight nesting guard: refuse to recurse when src and dst nest
        // inside each other. Two failure modes this catches:
        //   1. src contains dst — the walker would re-enter dst as it copies
        //      files INTO dst, doubling/looping every file.
        //   2. dst contains src — copying src/x → dst/x where dst is src's
        //      parent ends up overwriting the source. Less harmful but still
        //      nonsense.
        // Both cases happen in misconfigured setups where the user pointed
        // the deployment at the wrong directory. Better to bail loudly than
        // produce a runaway sync that fills the disk.
        Path srcNorm = src.toAbsolutePath().normalize();
        Path dstNorm = dst.toAbsolutePath().normalize();
        if (dstNorm.startsWith(srcNorm) || srcNorm.startsWith(dstNorm)) {
            LOG.warn(p.logPrefix() + ": refusing nested src/dst paths: src=" + srcNorm
                    + " dst=" + dstNorm);
            return MirrorResult.FAILED;
        }

        final int[] copied = {0};
        final int[] brokenSkipped = {0};
        // Files the policy excluded although newer than the floor: for the class
        // sync, resources edited after the covering jar was built.
        final int[] excludedNewerThanFloor = {0};
        // Flipped true when a whole subtree is silently dropped from the walk
        // (an unreadable directory) or the outer walk aborts — either way the
        // contributedPaths set is incomplete and the caller must not treat a
        // missing path as an orphan to delete.
        final boolean[] walkFailed = {false};
        // Forward-slash-normalized relative paths the walker visited, regardless
        // of copy outcome. Used by the caller to compute orphan candidates in
        // the destination tree.
        final Set<String> contributedPaths = new HashSet<>();
        // Destination stamp per contributed path, known without a stat wherever a
        // recorded stamp was trusted — the manifest is refreshed from this map
        // instead of re-statting every file it lists.
        final Map<String, SyncManifest.Stamp> stamps = new HashMap<>();
        // Names present in each destination directory, one readdir per directory
        // and no per-entry stat. This is what makes trusting a recorded stamp
        // safe: the manifest lives outside the webapp and survives `mvn clean`,
        // the tree does not — "the stamp matches" is evidence only while the
        // file is actually there.
        final Map<Path, Set<String>> dstNamesBySrcDir = new HashMap<>();
        try {
            // Default FileVisitOption set = do NOT follow symlinks. We don't
            // pass FOLLOW_LINKS: a symlinked subdirectory could point outside
            // the project, into the destination tree, or form a loop. Java's
            // walker honours this and just visits the link as a regular file
            // (which we then skip via the explicit isSymbolicLink check below
            // so it never even gets to the copy path).
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult preVisitDirectory(Path dir, @NotNull BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        // Symlinked directory inside the source tree — skip.
                        // We don't trust where it points; could be an infinite
                        // loop or an external dir we shouldn't copy from.
                        LOG.debug(p.logPrefix() + ": skipping symlinked directory " + dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!p.subtreeSkips().isEmpty()) {
                        // Skip subtrees that are owned by other pipelines.
                        Path rel = src.relativize(dir);
                        String relPath = rel.toString().replace('\\', '/');
                        for (String skip : p.subtreeSkips()) {
                            if (relPath.equals(skip) || relPath.startsWith(skip + "/")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
                    // Cooperative cancellation: this walk runs under the
                    // launch-prep modal and the update task's indicator.
                    // Deliberately OUTSIDE the try below — a cancellation must
                    // abort the walk, not be swallowed as a per-file failure.
                    TomcatProgress.checkCanceled();
                    try {
                        // Skip symlinks. The mirror's contract is "copy
                        // source-of-truth files"; a symlink doesn't qualify,
                        // and following one could land outside the source
                        // tree.
                        if (attrs.isSymbolicLink()) {
                            LOG.debug(p.logPrefix() + ": skipping symlink " + file);
                            return FileVisitResult.CONTINUE;
                        }

                        // Policy exclusion: skip BEFORE recording the path so
                        // the excluded file is absent from contributedPaths and
                        // the caller's orphan pass deletes any copy an earlier,
                        // more permissive sync left behind (self-healing a
                        // deployment broken by an old policy). See each
                        // caller's Policy construction for the rationale of
                        // its exclusions.
                        if (p.fileExclude() != null && p.fileExclude().test(file)) {
                            if (p.sourceMtimeFloorMillis() != Long.MIN_VALUE
                                    && attrs.lastModifiedTime().toMillis() > p.sourceMtimeFloorMillis()) {
                                excludedNewerThanFloor[0]++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        // Covering-JAR floor: a source file not strictly newer
                        // than the floor is skipped without contributing, so the
                        // caller's reconcile drops any previously-mirrored copy
                        // — the newer JAR serves instead of a stale overlay.
                        if (attrs.lastModifiedTime().toMillis() <= p.sourceMtimeFloorMillis()) {
                            return FileVisitResult.CONTINUE;
                        }

                        Path rel = src.relativize(file);
                        // Record the relative path BEFORE any gate. The
                        // reconcile treats "in the manifest but no longer
                        // contributed" as removed-from-source — so every source
                        // file the user authored (even one we skip because it
                        // is already up to date, or refuse because it is a
                        // broken stub) must be in contributedPaths, or its
                        // deployed copy would be cleaned as stale.
                        String relKey = rel.toString().replace('\\', '/');
                        contributedPaths.add(relKey);
                        Path target = dst.resolve(rel.toString());

                        // Trusted skip: the destination is the copy we made of exactly
                        // this source — same size and mtime as recorded at that copy
                        // (the copy below sets the destination's mtime to the
                        // source's) — and it is still present. No destination stat.
                        // Creation time is deliberately not compared: it is a
                        // destination-side value the source walk cannot know, and a
                        // replaced destination with identical size+mtime was never
                        // re-copied by the gate below either.
                        if (p.recordedStamps() != null) {
                            SyncManifest.Stamp recorded = p.recordedStamps().get(relKey);
                            if (recorded != null && !recorded.isUnknown()
                                    && recorded.size() == attrs.size()
                                    && recorded.mtimeMillis() == attrs.lastModifiedTime().toMillis()) {
                                Set<String> present = dstNamesBySrcDir.computeIfAbsent(file.getParent(),
                                        d -> listNames(dst.resolve(src.relativize(d).toString())));
                                if (present.contains(file.getFileName().toString())) {
                                    stamps.put(relKey, recorded);
                                    return FileVisitResult.CONTINUE;
                                }
                            }
                        }

                        // Cheap mtime/size gate FIRST. When the deployed copy is
                        // already current we return before the copy veto below —
                        // that veto may read the whole file (the broken-class
                        // scan), so running it for every up-to-date file on every
                        // sync would read the entire deployed classpath off disk
                        // each launch/update (the dominant cost on large
                        // multi-module projects). Gating it behind the copy
                        // decision keeps a no-op sync at stat-only cost; only
                        // copy candidates are ever inspected.
                        BasicFileAttributes dstAttrs = destinationAttributes(target, p.copyOnDstStatError());
                        if (dstAttrs != null && !shouldCopy(attrs, dstAttrs)) {
                            stamps.put(relKey, SyncManifest.stampOf(dstAttrs));
                            return FileVisitResult.CONTINUE;
                        }

                        // CRITICAL gate: refuse copy candidates the policy
                        // vetoes. Today's only veto is the class sync's ECJ
                        // "compile-with-errors" stub detector: overwriting a
                        // working deployed copy with a stub that throws
                        //   java.lang.Error("Unresolved compilation problems")
                        // at class init time would fail Tomcat's webapp startup
                        // with no obvious connection to the IDE's compile state.
                        // Leave the working copy in place and surface the count
                        // via brokenSkipped (the per-artifact summary in
                        // syncDeployments) so the user knows what happened.
                        // The log line keeps the pre-unification wording — it
                        // only ever fires for the one policy that installs a
                        // veto.
                        if (p.copyVeto() != null && p.copyVeto().test(file)) {
                            brokenSkipped[0]++;
                            LOG.warn(p.logPrefix() + ": refusing to copy ECJ broken-class stub: "
                                    + file);
                            return FileVisitResult.CONTINUE;
                        }

                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try {
                            // Copy WITHOUT COPY_ATTRIBUTES on every platform (Windows,
                            // Linux, macOS) — the deployed copy needs none of the
                            // source's permissions/ACLs anywhere. The speedup is
                            // largest on Windows, where COPY_ATTRIBUTES additionally
                            // re-applies NTFS security attributes (ACLs) per file — the
                            // dominant cost on large multi-module syncs (the
                            // copySecurityAttributes frames in the EDT-freeze report);
                            // on Linux/macOS it just avoids a cheaper permission copy.
                            // We still mirror just the source mtime onto the copy so
                            // shouldCopy's gate stays exact (dst mtime == src mtime),
                            // keeping an unchanged file a no-op on the next sync — same
                            // behaviour on every OS.
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                            try {
                                Files.setLastModifiedTime(target, attrs.lastModifiedTime());
                            } catch (IOException ignoreMtime) {
                                // mtime is a gate optimization, not correctness — worst
                                // case the next sync re-copies this one file.
                            }
                            copied[0]++;
                            // One stat, only for a file that actually changed.
                            stamps.put(relKey, SyncManifest.stampOf(target));
                        } catch (NoSuchFileException vanished) {
                            // The source file disappeared between visitFile and
                            // copy — common when the IDE re-compiles concurrently
                            // (Make replaces .class atomically). Don't count as
                            // copy, don't fail the walk; the next sync picks it
                            // up. Debug-level only.
                            LOG.debug(p.logPrefix() + ": source vanished during copy: " + file);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.debug(p.logPrefix() + ": skipped " + file + " (" + e.getMessage() + ")");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Walk continues (the mirror still copies everything else),
                    // but ANY unvisitable entry marks the walk failed: the
                    // entry is absent from contributedPaths even though its
                    // source exists, so reconciling would delete its deployed
                    // copy. No isDirectory probe here — visitFileFailed fires
                    // because the stat itself failed, and Files.isDirectory
                    // returns false when the type "cannot be determined"
                    // (no-execute parent dir, vanished entry), which is exactly
                    // when an entire subtree may be silently missing.
                    walkFailed[0] = true;
                    LOG.debug(p.logPrefix() + ": cannot visit " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Outer walk aborted: contributedPaths holds only a partial union,
            // so mark the walk failed to keep the caller's reconcile off it.
            walkFailed[0] = true;
            LOG.debug(p.logPrefix() + ": walk failed for " + src + " (" + e.getMessage() + ")");
        }
        return new MirrorResult(copied[0], brokenSkipped[0], contributedPaths, walkFailed[0], stamps,
                excludedNewerThanFloor[0]);
    }

    /**
     * Returns {@code true} when {@code source} should be written to
     * {@code target}. Two-axis check:
     *
     * <ol>
     *   <li><b>mtime forward:</b> source is strictly newer than destination
     *       — the common edit-then-restart case.</li>
     *   <li><b>size mismatch (tie-breaker):</b> mtimes equal but file sizes
     *       differ. This catches the edge case where a previous sync set
     *       {@code dst.mtime == src.mtime} via {@code Files.setLastModifiedTime}, then
     *       the user edited the source again within the filesystem's mtime
     *       resolution (1-second on some FSes, lower on APFS/NTFS but not
     *       impossible on a fast SSD). Without this tie-breaker, the
     *       second edit silently fails to sync — exactly the "I changed the
     *       file but the restart shows old code" symptom that the class
     *       sync was added to fix.</li>
     * </ol>
     *
     * <p>The mtime gate is still load-bearing for performance: a typical
     * web module has hundreds of files, and unconditional copying would add
     * visible latency to every restart. The size tie-breaker adds no extra
     * syscall: the destination's mtime and size come from a single
     * {@code Files.readAttributes} call, and the source's from the
     * {@code BasicFileAttributes} the file-tree walk already supplied.
     *
     * <p>{@code copyOnDstStatError} decides the generic-IOException case when
     * the destination's attributes cannot be read: {@code true} = copy anyway
     * (safer than leaving stale code), {@code false} = rethrow so the engine's
     * per-file catch skips the file (it stays contributed).
     */
    static boolean shouldCopy(@NotNull Path source,
                              @NotNull BasicFileAttributes sourceAttrs,
                              @NotNull Path target,
                              boolean copyOnDstStatError) throws IOException {
        BasicFileAttributes dstAttrs = destinationAttributes(target, copyOnDstStatError);
        return dstAttrs == null || shouldCopy(sourceAttrs, dstAttrs);
    }

    /**
     * The destination's attributes, or {@code null} meaning "copy": it is absent,
     * or — with {@code copyOnDstStatError} — unreadable. A stat failure without
     * that flag propagates so the engine's per-file catch skips the file.
     */
    @Nullable
    static BasicFileAttributes destinationAttributes(@NotNull Path target,
                                                     boolean copyOnDstStatError) throws IOException {
        try {
            return Files.readAttributes(target, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException e) {
            if (copyOnDstStatError) return null;
            throw e;
        }
    }

    /** Pure gate on attributes already in hand: source newer, or a different size, means copy. */
    static boolean shouldCopy(@NotNull BasicFileAttributes sourceAttrs, @NotNull BasicFileAttributes dstAttrs) {
        if (sourceAttrs.lastModifiedTime().toMillis() > dstAttrs.lastModifiedTime().toMillis()) {
            return true;
        }
        return sourceAttrs.size() != dstAttrs.size();
    }

    /** Entry names of {@code dir} from one readdir; empty when it is not a readable directory. */
    @NotNull
    static Set<String> listNames(@NotNull Path dir) {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) names.add(entry.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            return Collections.emptySet();
        }
        return names;
    }
}
