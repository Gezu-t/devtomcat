package com.dev.idea.plugins.tomcat.update;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Guards the hot-sync pipeline against writing into the user's own project tree.
 *
 * <p>Exploded deployments deploy <em>in place</em> — Tomcat's context {@code docBase}
 * points straight at the deployment path, and the sync pipeline creates and fills
 * {@code <docBase>/WEB-INF/classes} with compiled bytecode (reconcile-deleting there
 * too). That is correct only when the path is a <em>build output</em>. When a
 * deployment path is a source/content directory — e.g. {@code src/main/webapp}, which
 * some detection and hand-entry paths can produce — the same write pollutes the
 * version-controlled source tree and its reconcile pass can delete a user's file.
 *
 * <p>The discriminator is the project model, not a path-name heuristic:
 * {@link ProjectFileIndex#isInContent} is {@code true} for directories under a module
 * content root that are not excluded (source roots, resource roots, web roots), and
 * {@code false} for build outputs — Maven {@code target/}, Gradle {@code build/}, and
 * {@code out/} are registered as excluded folders, so a build output's exploded
 * staging dir (e.g. {@code target/<finalName>}) is not "in content". Note
 * {@code isInSourceContent} is deliberately <em>not</em> used: a web root is not a JPS
 * source root, so it would slip through exactly the check meant to protect it.
 */
public final class DeploymentSafety {

    private DeploymentSafety() {}

    /**
     * Whether {@code path} lies inside the project's content (user-managed tree) and
     * is therefore unsafe for the sync pipeline to write into. Build outputs, and any
     * path outside the project model, return {@code false}.
     *
     * <p><strong>Must be called under a read action</strong> (touches
     * {@link ProjectFileIndex}).
     */
    public static boolean isInsideProjectContent(@NotNull Project project, @NotNull Path path) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (vf == null) {
            // Not in the VFS at all — an external or not-yet-created build output.
            // The deployment.isValid() gate upstream guarantees the path exists on
            // disk before this runs, so a null here means "outside the project", safe.
            return false;
        }
        return isInsideProjectContent(project, vf);
    }

    /**
     * Like {@link #isInsideProjectContent(Project, Path)} but for a path that may not
     * exist yet (a directory about to be <em>created</em>): it classifies by the
     * nearest existing ancestor, so a to-be-created dir is judged by where it would
     * live. Use this at the point of deciding whether to CREATE a directory —
     * {@link #isInsideProjectContent(Project, Path)}'s "not in the VFS ⇒ safe"
     * shortcut is only valid for a path that already exists, so calling it on a
     * guaranteed-nonexistent path would silently classify everything as safe and
     * defeat the guard.
     *
     * <p><strong>Must be called under a read action.</strong>
     */
    public static boolean wouldCreateInsideProjectContent(@NotNull Project project, @NotNull Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(p);
            if (vf != null) {
                return isInsideProjectContent(project, vf);
            }
        }
        // No existing ancestor is in the VFS at all — outside any project root, safe.
        return false;
    }

    /**
     * {@link VirtualFile} overload of {@link #isInsideProjectContent(Project, Path)} —
     * the actual project-model classification, split out so it is directly testable
     * without local-filesystem resolution.
     *
     * <p><strong>Must be called under a read action.</strong>
     */
    public static boolean isInsideProjectContent(@NotNull Project project, @NotNull VirtualFile file) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        // isInContent already excludes excluded/ignored files; the explicit
        // !isExcluded is intent-documenting and guards a source root nested under
        // an otherwise-excluded directory.
        return index.isInContent(file) && !index.isExcluded(file);
    }
}
