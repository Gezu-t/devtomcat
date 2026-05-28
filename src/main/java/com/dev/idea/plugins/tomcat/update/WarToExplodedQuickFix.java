package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-click migration that flips a WAR-typed {@link DeploymentArtifact} to
 * point at its sibling exploded directory.
 *
 * <p>Maven's {@code maven-war-plugin} produces both {@code target/<finalName>.war}
 * (the packaged archive) and {@code target/<finalName>/} (the exploded staging
 * directory it builds the archive from) during {@code mvn package}. Gradle's
 * {@code war} task likewise produces both forms. When the user picked the
 * {@code .war} file during auto-detection, hot class/resource sync skips the
 * deployment because Tomcat's classloader holds the sealed archive open and
 * mutating it from outside is unsafe.
 *
 * <p>This fix flips the deployment to point at the directory instead:
 * <ul>
 *   <li>{@code path}: {@code .../target/foo.war} → {@code .../target/foo}</li>
 *   <li>{@code type}: {@link DeploymentArtifact#TYPE_WAR} →
 *       {@link DeploymentArtifact#TYPE_EXPLODED}</li>
 * </ul>
 *
 * <p>The change is only offered when the sibling directory actually exists on
 * disk AND contains a {@code WEB-INF/} subdirectory — the latter being the
 * canonical "this is an exploded webapp" marker that distinguishes it from any
 * other directory that happens to share the base name (e.g. {@code target/foo/}
 * left over from a different build step).
 *
 * <p>After applying, hot sync via {@code Ctrl+F10} works for the deployment
 * with no further user action — Java edits land via {@link DeployedClassesSync}
 * and webapp resources via {@link WebResourcesSync}, both writing into the
 * directory the deployment now points at.
 */
public final class WarToExplodedQuickFix {

    private static final Logger LOG = Logger.getInstance(WarToExplodedQuickFix.class);

    private static final String WAR_EXTENSION = ".war";
    private static final String WEB_INF_MARKER = "WEB-INF";

    private WarToExplodedQuickFix() {}

    /**
     * One candidate flip: the artifact that will be mutated and the target
     * directory its path will move to.
     */
    public record FixCandidate(@NotNull DeploymentArtifact artifact,
                               @NotNull Path explodedDirectory) {}

    /**
     * Scans the legacy artifact list for WAR-typed entries whose sibling
     * exploded directory exists and looks like a real webapp ({@code WEB-INF/}
     * present). Returns a fresh list — caller may freely mutate.
     */
    @NotNull
    public static List<FixCandidate> findFixableArtifacts(@NotNull List<DeploymentArtifact> artifacts) {
        List<FixCandidate> out = new ArrayList<>();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            if (!DeploymentArtifact.TYPE_WAR.equals(artifact.getType())) continue;
            Path exploded = deriveExplodedPath(artifact.getPath());
            if (exploded == null) continue;
            if (!isExplodedWebapp(exploded)) continue;
            out.add(new FixCandidate(artifact, exploded));
        }
        return out;
    }

    /**
     * Applies the flip to every candidate and persists the change via
     * {@link RunManager}. Mutates the artifact list of {@code config} in place;
     * IntelliJ's run-config storage picks up the change on the next save tick.
     *
     * <p>Returns the number of artifacts actually mutated (some candidates may
     * have become stale between {@link #findFixableArtifacts} and this call,
     * e.g. if {@code mvn clean} ran in between).
     */
    public static int applyAll(@NotNull TomcatRunConfiguration config,
                               @NotNull List<FixCandidate> candidates) {
        int applied = 0;
        for (FixCandidate candidate : candidates) {
            if (!isExplodedWebapp(candidate.explodedDirectory())) {
                LOG.info("Skipping stale candidate (directory disappeared): "
                        + candidate.explodedDirectory());
                continue;
            }
            candidate.artifact().setPath(candidate.explodedDirectory().toString());
            candidate.artifact().setType(DeploymentArtifact.TYPE_EXPLODED);
            applied++;
        }

        if (applied > 0) {
            // The artifact list is mutated in place. Run-config state is
            // persisted by IntelliJ on its next save tick (run-config editor
            // open/close, IDE shutdown, etc.) — no explicit RunManager nudge
            // needed. In-memory state is already correct for the next launch.
            LOG.info("Flipped " + applied + " WAR deployment(s) to exploded type");
        }
        return applied;
    }

    /**
     * Maps a {@code .war} file path to the sibling exploded directory path.
     * Returns {@code null} when the input doesn't end with {@code .war} or
     * doesn't have a parent directory.
     *
     * <p>Path-shape only — does not touch the filesystem. Use
     * {@link #isExplodedWebapp} to confirm the directory actually exists.
     */
    @Nullable
    static Path deriveExplodedPath(@Nullable String warPath) {
        if (warPath == null || warPath.isEmpty()) return null;
        Path p;
        try {
            p = Path.of(warPath);
        } catch (Exception e) {
            return null;
        }
        Path filename = p.getFileName();
        if (filename == null) return null;
        String name = filename.toString();
        if (!name.toLowerCase().endsWith(WAR_EXTENSION)) return null;
        String base = name.substring(0, name.length() - WAR_EXTENSION.length());
        if (base.isEmpty()) return null;
        Path parent = p.getParent();
        return parent == null ? Path.of(base) : parent.resolve(base);
    }

    /**
     * True when {@code dir} is an existing directory containing a
     * {@code WEB-INF/} subdirectory — the canonical exploded-webapp signature.
     * Used to distinguish a real Maven/Gradle exploded WAR staging directory
     * from any directory that happens to share the base name.
     */
    static boolean isExplodedWebapp(@NotNull Path dir) {
        try {
            if (!Files.isDirectory(dir)) return false;
            File webInf = new File(dir.toFile(), WEB_INF_MARKER);
            return webInf.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }
}
