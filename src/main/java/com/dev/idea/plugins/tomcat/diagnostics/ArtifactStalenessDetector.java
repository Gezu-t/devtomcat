package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects whether a configured {@link DeploymentArtifact} is older than the
 * project's source files, which is the most common reason for "I edited code,
 * restarted Tomcat, but the change is not reflected".
 *
 * <p>The pain shape: the platform's "Make" Before Launch task compiles to
 * {@code out/production/classes/} but does <em>not</em> refresh the deployed
 * artifact at {@code out/artifacts/.../} or {@code target/foo.war}. If the
 * configuration has an IntelliJ Artifact, the platform's
 * {@code BuildArtifactsBeforeRunTask} rebuilds it; if the artifact is an
 * external path (Maven {@code target/}, Gradle {@code build/}, or any other
 * filesystem location), nothing rebuilds it and Tomcat happily serves the
 * stale version.
 *
 * <p>This detector compares mtimes — recent source edits vs the deployed
 * file — and surfaces a warning to the user with the offending source file
 * and the artifact path. It does not block the launch; the user decides
 * whether to fix the build setup or proceed with possibly-stale code.
 *
 * <h2>Performance</h2>
 * Walks module source roots through the IntelliJ VFS (cached, much faster
 * than {@link Files#walk}). Stops at the first {@code .java} file newer than
 * the artifact — there is no value in counting every stale file; one example
 * is enough to motivate the user. Per-module walk is capped at
 * {@value #FILE_WALK_CAP} files as a runaway safety net for unusual project
 * layouts (generated source dirs containing millions of files).
 */
public final class ArtifactStalenessDetector {

    /** Hard cap on files visited per module to keep launch latency bounded on huge projects. */
    private static final int FILE_WALK_CAP = 5_000;

    private ArtifactStalenessDetector() {}

    /**
     * Report of a single stale artifact. {@code exampleSourceFile} is the
     * first source file we found newer than the artifact — meant to give the
     * user a concrete pointer ("you edited UserService.java; the artifact has
     * not been rebuilt since"), not an exhaustive list.
     */
    public record StaleReport(@NotNull String artifactDisplayName,
                              @NotNull String artifactPath,
                              @NotNull String exampleSourceFile) {}

    /**
     * Returns a report for every artifact whose deployed path is older than
     * at least one {@code .java} source file in the project's module source
     * roots. Returns an empty list when nothing is stale or when staleness
     * cannot be determined (missing path, IO error, etc.).
     */
    @NotNull
    public static List<StaleReport> findStaleArtifacts(@NotNull Project project,
                                                       @NotNull List<DeploymentArtifact> artifacts) {
        if (project.isDisposed() || artifacts.isEmpty()) return List.of();

        List<VirtualFile> sourceRoots;
        try {
            sourceRoots = collectSourceRoots(project);
        } catch (Throwable t) {
            // Service container unavailable (e.g. partially-initialized project,
            // unit-test fixture without ModuleManager). Degrading to "no stale
            // findings" is the right answer — the launch proceeds normally and
            // we never block on a diagnostics scan that cannot run.
            return List.of();
        }
        if (sourceRoots.isEmpty()) return List.of();

        List<StaleReport> reports = new ArrayList<>();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            String path = artifact.getPath();
            if (path == null || path.isBlank()) continue;
            long artifactMtime;
            try {
                Path p = Paths.get(path);
                if (!Files.exists(p)) continue;
                artifactMtime = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException | RuntimeException e) {
                continue;
            }

            VirtualFile staleSource = findFirstSourceNewerThan(sourceRoots, artifactMtime);
            if (staleSource != null) {
                reports.add(new StaleReport(
                        artifact.getDisplayName(),
                        path,
                        staleSource.getName()));
            }
        }
        return reports;
    }

    @NotNull
    private static List<VirtualFile> collectSourceRoots(@NotNull Project project) {
        List<VirtualFile> roots = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
                if (root != null && root.isValid()) roots.add(root);
            }
        }
        return roots;
    }

    /**
     * Walks the supplied source roots looking for the first {@code .java}
     * file newer than {@code referenceMtime}. Returns null if no such file
     * is found within the {@link #FILE_WALK_CAP} budget.
     */
    private static VirtualFile findFirstSourceNewerThan(@NotNull List<VirtualFile> sourceRoots,
                                                        long referenceMtime) {
        for (VirtualFile root : sourceRoots) {
            int[] visited = {0};
            VirtualFile match = findFirstNewerInTree(root, referenceMtime, visited);
            if (match != null) return match;
        }
        return null;
    }

    private static VirtualFile findFirstNewerInTree(@NotNull VirtualFile dir,
                                                    long referenceMtime,
                                                    @NotNull int[] visited) {
        if (visited[0] >= FILE_WALK_CAP) return null;
        if (!dir.isValid()) return null;

        VirtualFile[] children;
        try {
            children = dir.getChildren();
        } catch (RuntimeException e) {
            return null;
        }
        if (children == null) return null;

        for (VirtualFile child : children) {
            if (visited[0] >= FILE_WALK_CAP) return null;
            visited[0]++;
            if (child == null || !child.isValid()) continue;
            if (child.isDirectory()) {
                VirtualFile match = findFirstNewerInTree(child, referenceMtime, visited);
                if (match != null) return match;
            } else if (child.getName().endsWith(".java")) {
                if (child.getTimeStamp() > referenceMtime) {
                    return child;
                }
            }
        }
        return null;
    }
}
