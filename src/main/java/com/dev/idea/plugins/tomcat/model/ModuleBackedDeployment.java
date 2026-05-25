package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModulePointer;
import com.intellij.openapi.module.ModulePointerManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Deployment backed directly by an IntelliJ Module — for auto-detected webapps with no registered IntelliJ Artifact. Output path is user-supplied (Maven {@code target/<warname>/} or equivalent). */
public final class ModuleBackedDeployment implements Deployment {

    private final @NotNull ModulePointer modulePointer;
    private final @NotNull String contextPath;
    private final @NotNull Path outputPath;
    private final boolean exploded;

    public ModuleBackedDeployment(@NotNull ModulePointer modulePointer,
                                  @NotNull Path outputPath,
                                  @NotNull String contextPath,
                                  boolean exploded) {
        this.modulePointer = modulePointer;
        this.outputPath = outputPath;
        this.contextPath = normaliseContextPath(contextPath);
        this.exploded = exploded;
    }

    public static @NotNull ModuleBackedDeployment ofName(@NotNull Project project,
                                                         @NotNull String moduleName,
                                                         @NotNull Path outputPath,
                                                         @NotNull String contextPath,
                                                         boolean exploded) {
        return new ModuleBackedDeployment(
                ModulePointerManager.getInstance(project).create(moduleName),
                outputPath,
                contextPath,
                exploded);
    }

    @Override public @NotNull DeploymentKind getKind() { return DeploymentKind.MODULE; }

    @Override public @NotNull String getContextPath() { return contextPath; }

    @Override public @NotNull String getDisplayName() { return modulePointer.getModuleName(); }

    @Override
    public @Nullable Path getResolvedPath() {
        return outputPath;
    }

    @Override public boolean isExploded() { return exploded; }

    @Override
    public boolean isValid() {
        // Both module and output dir must be present — they go stale independently
        // (module deletion vs. mvn clean).
        return modulePointer.getModule() != null && Files.exists(outputPath);
    }

    public @NotNull ModulePointer getModulePointer() { return modulePointer; }

    public @Nullable Module getModule() { return modulePointer.getModule(); }

    public @NotNull String getModuleName() { return modulePointer.getModuleName(); }

    public @NotNull Path getOutputPath() { return outputPath; }

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
                && modulePointer.getModuleName().equals(that.modulePointer.getModuleName())
                && outputPath.equals(that.outputPath)
                && contextPath.equals(that.contextPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modulePointer.getModuleName(), outputPath, contextPath, exploded);
    }

    @Override
    public String toString() {
        return "ModuleBackedDeployment{module=" + modulePointer.getModuleName()
                + ", output=" + outputPath
                + ", context=" + contextPath
                + ", exploded=" + exploded + '}';
    }
}
