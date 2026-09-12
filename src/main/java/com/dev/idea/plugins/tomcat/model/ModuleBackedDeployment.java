package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Deployment backed directly by an IntelliJ Module —
 * for auto-detected webapps with no registered IntelliJ Artifact.
 * Output path is user-supplied (Maven {@code target/<warname>/} or equivalent).
 */
public final class ModuleBackedDeployment implements Deployment {

    private final @NotNull ModuleRef moduleRef;
    private final @NotNull String contextPath;
    private final @NotNull Path outputPath;
    private final boolean exploded;
    /**
     * Original stored display name from the persisted legacy record (e.g.
     * {@code webapp.war}), which may differ from the resolved module name when
     * the artifact filename was mapped to a differently-named module by
     * {@link DeploymentResolver}. Preserved so the legacy round trip echoes back
     * the persisted name instead of silently rewriting it to the module name.
     * Null when the deployment was created directly from a module (name ==
     * module name). Deliberately excluded from equals/hashCode — identity stays
     * module name + output path + context + exploded, to keep typed dedupe stable.
     */
    private final @Nullable String legacyName;

    public ModuleBackedDeployment(@NotNull ModuleRef moduleRef,
                                  @NotNull Path outputPath,
                                  @NotNull String contextPath,
                                  boolean exploded) {
        this(moduleRef, outputPath, contextPath, exploded, null);
    }

    public ModuleBackedDeployment(@NotNull ModuleRef moduleRef,
                                  @NotNull Path outputPath,
                                  @NotNull String contextPath,
                                  boolean exploded,
                                  @Nullable String legacyName) {
        this.moduleRef = moduleRef;
        this.outputPath = outputPath;
        this.contextPath = normaliseContextPath(contextPath);
        this.exploded = exploded;
        this.legacyName = legacyName;
    }

    public static @NotNull ModuleBackedDeployment ofName(@NotNull Project project,
                                                         @NotNull String moduleName,
                                                         @NotNull Path outputPath,
                                                         @NotNull String contextPath,
                                                         boolean exploded) {
        return new ModuleBackedDeployment(
                ModuleRef.of(project, moduleName),
                outputPath,
                contextPath,
                exploded);
    }

    @Override
    public @NotNull DeploymentKind getKind() {
        return DeploymentKind.MODULE;
    }

    /** Copy with a different context path; module ref, output path, packaging and legacy name carry over. */
    @Override
    public @NotNull ModuleBackedDeployment withContextPath(@NotNull String contextPath) {
        return new ModuleBackedDeployment(moduleRef, outputPath, contextPath, exploded, legacyName);
    }

    @Override
    public @NotNull String getContextPath() {
        return contextPath;
    }

    @Override
    public @NotNull String getDisplayName() {
        return moduleRef.getModuleName();
    }

    @Override
    public @Nullable Path getResolvedPath() {
        return outputPath;
    }

    @Override
    public boolean isExploded() {
        return exploded;
    }

    /**
     * Always WAR: these are only built for modules that passed {@code isWebModule},
     * which requires war packaging or a webapp root. Not recomputed here — the resolved
     * view is rebuilt on the EDT, where build-file reads are prohibited.
     */
    @Override
    public @NotNull DeploymentArchive getArchive() {
        return DeploymentArchive.WAR;
    }

    @Override
    public boolean isValid() {
        // Both module and output dir must be present — they go stale independently
        // (module deletion vs. mvn clean).
        return moduleRef.getModule() != null && Files.exists(outputPath);
    }

    /**
     * The display name to persist back to the legacy record. Falls back to the
     * resolved module name when no distinct stored name was captured.
     */
    public @NotNull String getLegacyName() {
        return legacyName != null ? legacyName : moduleRef.getModuleName();
    }

    public @Nullable Module getModule() {
        return moduleRef.getModule();
    }

    public @NotNull String getModuleName() {
        return moduleRef.getModuleName();
    }

    public @NotNull Path getOutputPath() {
        return outputPath;
    }

    private static String normaliseContextPath(@NotNull String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "/";
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModuleBackedDeployment that)) return false;
        return exploded == that.exploded
                && moduleRef.getModuleName().equals(that.moduleRef.getModuleName())
                && outputPath.equals(that.outputPath)
                && contextPath.equals(that.contextPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleRef.getModuleName(), outputPath, contextPath, exploded);
    }

    @Override
    public String toString() {
        return "ModuleBackedDeployment{module=" + moduleRef.getModuleName()
                + ", output=" + outputPath
                + ", context=" + contextPath
                + ", exploded=" + exploded + '}';
    }
}
