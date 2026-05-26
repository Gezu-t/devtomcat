package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.model.Deployment;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pre-launch validation of a {@link Deployment}'s on-disk structure.
 *
 * <p>The platform's "Make" task and our existing path-exists check both pass
 * silently when an artifact's path EXISTS but its <em>contents</em> are not a
 * deployable webapp. Examples that used to fail at Tomcat startup with cryptic
 * 404s or NoClassDefFoundError:
 * <ul>
 *   <li>An exploded artifact pointing at a directory that has no
 *       {@code WEB-INF/} — build never completed, or the path is wrong.</li>
 *   <li>An exploded artifact whose {@code WEB-INF/classes/} is empty — Make
 *       step is broken, or the user's IntelliJ compile output is going
 *       somewhere else.</li>
 *   <li>An artifact configured as WAR (i.e. {@code !deployment.isExploded()})
 *       whose path is actually a directory — type / path mismatch.</li>
 * </ul>
 *
 * <p>Two severity buckets:
 * <ul>
 *   <li>{@code blockingErrors} — Tomcat cannot meaningfully serve this
 *       artifact. The caller should refuse the launch.</li>
 *   <li>{@code warnings} — suspicious but not fatal (e.g. empty classes
 *       directory). The caller should surface but let the launch proceed.</li>
 * </ul>
 *
 * <p>All checks are local filesystem reads with no Tomcat / IntelliJ
 * dependencies, so they're safe to call from any thread. The implementation
 * is conservative: any IO failure during the check counts as "no finding"
 * rather than "broken" — a corrupted artifact will be re-caught by
 * Tomcat's own startup, and we don't want to false-positive on a transient
 * permissions blip.
 */
public final class ArtifactStructureValidator {

    private ArtifactStructureValidator() {}

    public record Result(@NotNull List<String> blockingErrors,
                         @NotNull List<String> warnings) {

        public boolean hasBlockingErrors() {
            return !blockingErrors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    @NotNull
    public static Result validate(@NotNull List<Deployment> deployments) {
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Deployment d : deployments) {
            Path path = d.getResolvedPath();
            if (path == null || path.toString().isEmpty()) continue;
            if (!Files.exists(path)) continue; // path-exists is the validator's other concern

            String displayName = d.getDisplayName();
            if (d.isExploded()) {
                validateExploded(displayName, path, blocking, warnings);
            } else {
                validateWar(displayName, path, blocking);
            }
        }
        return new Result(List.copyOf(blocking), List.copyOf(warnings));
    }

    /**
     * Exploded webapp must be a directory containing {@code WEB-INF/}.
     * {@code WEB-INF/classes/} is checked only as a warning — some legacy
     * apps put all their code in {@code WEB-INF/lib/} JARs and have no
     * classes directory at all.
     */
    private static void validateExploded(@NotNull String displayName, @NotNull Path path,
                                         @NotNull List<String> blocking,
                                         @NotNull List<String> warnings) {
        if (!Files.isDirectory(path)) {
            blocking.add("'" + displayName + "' is configured as an exploded webapp but "
                    + "the path is not a directory: " + path
                    + ". Either change the artifact type to WAR, or point the path at "
                    + "the exploded output directory (e.g. target/myapp/ for Maven).");
            return;
        }

        Path webInf = path.resolve("WEB-INF");
        if (!Files.isDirectory(webInf)) {
            blocking.add("'" + displayName + "' is missing WEB-INF/ at " + path
                    + ". The build may be incomplete — run Build → Build Artifacts, "
                    + "or your build tool's packaging goal (mvn package, gradle war).");
            return;
        }

        Path classes = webInf.resolve("classes");
        if (Files.isDirectory(classes) && isDirectoryEmpty(classes)) {
            warnings.add("'" + displayName + "' has an empty WEB-INF/classes/ at "
                    + classes + ". If your code is not packaged as JARs in WEB-INF/lib/, "
                    + "the 'Build' (Make) step may have failed to produce class files.");
        }
    }

    /**
     * WAR artifact must be a regular file AND a structurally-valid JAR / ZIP.
     * If the user picked TYPE_WAR but the path resolves to a directory, Tomcat
     * will try to read the directory as a JAR and fail with a confusing error.
     * If the file exists but is corrupted (Maven build interrupted, partial
     * download, manual overwrite while the file was open), Tomcat fails with
     * {@code java.util.zip.ZipException: error opening zip file} 10 seconds
     * into startup — the user has no signal that the WAR itself is the
     * problem. We catch both up front.
     */
    private static void validateWar(@NotNull String displayName, @NotNull Path path,
                                    @NotNull List<String> blocking) {
        if (Files.isDirectory(path)) {
            blocking.add("'" + displayName + "' is configured as a WAR archive but "
                    + "the path is a directory: " + path
                    + ". Either change the artifact type to 'Exploded', or point the "
                    + "path at the .war file (e.g. target/myapp.war).");
            return;
        }
        // Open the WAR as a JAR. The constructor parses the ZIP central directory
        // and throws IOException on any structural problem — empty file, truncated
        // bytes, non-ZIP content, etc. Touching the manifest forces a read past
        // the constructor's lazy validation. Closing immediately keeps the file
        // handle scope tight; Tomcat opens its own when it deploys.
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path.toFile())) {
            jar.getManifest();
        } catch (java.io.IOException e) {
            blocking.add("'" + displayName + "' at " + path + " is not a readable "
                    + "WAR archive (" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + "). The file may be corrupted, truncated, or your build may have "
                    + "been interrupted — rebuild the artifact (e.g. mvn package) and retry.");
        }
    }

    private static boolean isDirectoryEmpty(@NotNull Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            // Treat IO failure as "not empty" — better to skip the warning
            // than to alarm the user about a transient permissions blip.
            return false;
        }
    }
}
