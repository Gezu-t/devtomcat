package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModulePointer;
import com.intellij.openapi.module.ModulePointerManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * DevTomcat's own by-name handle on a module: the name always, the live
 * {@link Module} only when a project is behind it.
 *
 * <p>The model needs this two-state shape because a {@code TomcatConfigurationData}
 * is also built without a project — data-level validation and config export read
 * a deployment's module name with nothing to resolve it against. The platform's
 * {@link ModulePointer} covers only the live case: it is
 * {@code @ApiStatus.NonExtendable}, so a project-free stand-in cannot be a
 * {@code ModulePointer} without implementing an interface JetBrains reserves
 * (the plugin verifier reports it, and a future default method would break us).
 *
 * <p>So the live case {@linkplain #of(ModulePointer) delegates to} a real
 * platform pointer — keeping the rename tracking that is the whole reason to
 * hold a pointer rather than a string — and the project-free case is an honest
 * name that never pretends to resolve.
 */
public interface ModuleRef {

    /** The module name; rename-tracked when this ref is platform-backed. */
    @NotNull
    String getModuleName();

    /** The live module, or {@code null} when detached or currently unresolvable. */
    @Nullable
    Module getModule();

    /** Rename-tracked ref backed by the platform's pointer. */
    @NotNull
    static ModuleRef of(@NotNull ModulePointer pointer) {
        return new PlatformModuleRef(pointer);
    }

    /** Rename-tracked ref for {@code moduleName} in {@code project}. */
    @NotNull
    static ModuleRef of(@NotNull Project project, @NotNull String moduleName) {
        return of(ModulePointerManager.getInstance(project).create(moduleName));
    }

    /** Rename-tracked ref for a module already in hand. */
    @NotNull
    static ModuleRef of(@NotNull Project project, @NotNull Module module) {
        return of(ModulePointerManager.getInstance(project).create(module));
    }

    /** Name-only ref that never resolves — for project-free typed views. */
    @NotNull
    static ModuleRef detached(@NotNull String moduleName) {
        return new DetachedModuleRef(moduleName);
    }

    /** Delegating wrapper; the platform keeps the name current across renames. */
    record PlatformModuleRef(@NotNull ModulePointer pointer) implements ModuleRef {
        @Override
        public @NotNull String getModuleName() {
            return pointer.getModuleName();
        }

        @Override
        public @Nullable Module getModule() {
            return pointer.getModule();
        }

        @Override
        public @NotNull String toString() {
            return getModuleName();
        }
    }

    /** No project, so nothing to resolve against — and it says so. */
    record DetachedModuleRef(@NotNull String moduleName) implements ModuleRef {
        @Override
        public @NotNull String getModuleName() {
            return moduleName;
        }

        @Override
        public @Nullable Module getModule() {
            return null;
        }

        @Override
        public @NotNull String toString() {
            return moduleName;
        }
    }
}
