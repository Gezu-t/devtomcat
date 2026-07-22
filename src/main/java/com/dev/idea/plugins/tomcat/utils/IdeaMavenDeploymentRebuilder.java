package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link DeploymentRebuilder} backed by the IDE's Maven run infrastructure.
 * Registered only in {@code devtomcat-maven.xml} (loaded with the optional
 * Maven dependency), so this class — and the Maven types it references — is
 * never classloaded on an install without Maven.
 *
 * <p>Runs the {@code package} goal for the module's own pom via
 * {@link MavenRunConfigurationType#runConfiguration}: passing no explicit
 * general/runner settings makes the launched configuration use the project's
 * Maven settings (home, user settings file, JVM options, properties), and the
 * {@link MavenRunnerParameters} carry the profiles currently enabled in the
 * Maven tool window — so the build is exactly what the user would get from the
 * Maven tool window's own run action, console output included.
 *
 * <p>Completion is observed through the run's {@link ProcessHandler}: a
 * terminated-with-zero exit invokes {@code onSuccess}, anything else (non-zero
 * exit, process never started) invokes {@code onFailure}. Both are delivered
 * on the EDT, per the {@link DeploymentRebuilder} contract. The calling thread
 * is never blocked.
 */
public final class IdeaMavenDeploymentRebuilder implements DeploymentRebuilder {

    private static final String PACKAGE_GOAL = "package";

    @Override
    public boolean canRebuild(@NotNull Project project, @NotNull Module module) {
        try {
            return mavenProject(project, module) != null;
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void rebuild(@NotNull Project project, @NotNull Module module,
                        @NotNull Runnable onSuccess, @NotNull Consumer<String> onFailure) {
        MavenProject mavenProject;
        try {
            mavenProject = mavenProject(project, module);
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            mavenProject = null;
        }
        if (mavenProject == null) {
            failOnEdt(onFailure, "module '" + module.getName() + "' is not a resolved Maven module");
            return;
        }

        // Working dir + pom of the module itself; profiles = the set currently
        // enabled in the Maven tool window.
        MavenRunnerParameters parameters = new MavenRunnerParameters(true,
                mavenProject.getDirectory(),
                mavenProject.getFile().getName(),
                List.of(PACKAGE_GOAL),
                MavenProjectsManager.getInstance(project).getExplicitProfiles());

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                onFailure.accept("project is disposed");
                return;
            }
            try {
                MavenRunConfigurationType.runConfiguration(project, parameters,
                        new com.intellij.execution.runners.ProgramRunner.Callback() {
                            @Override
                            public void processStarted(com.intellij.execution.ui.RunContentDescriptor descriptor) {
                                observeCompletion(descriptor != null ? descriptor.getProcessHandler() : null,
                                        onSuccess, onFailure);
                            }

                            @Override
                            public void processNotStarted(@Nullable Throwable error) {
                                failOnEdt(onFailure, "Maven build did not start"
                                        + (error != null ? ": " + error.getMessage() : ""));
                            }
                        });
            } catch (ProcessCanceledException pce) {
                throw pce;
            } catch (Exception e) {
                onFailure.accept("could not start the Maven build: " + e.getMessage());
            }
        });
    }

    /**
     * Attaches a termination listener and also checks the current state, guarded
     * by a once-flag — a process that terminated before the listener attached
     * would otherwise never deliver, and a race could deliver twice.
     */
    private static void observeCompletion(@Nullable ProcessHandler handler,
                                          @NotNull Runnable onSuccess,
                                          @NotNull Consumer<String> onFailure) {
        if (handler == null) {
            failOnEdt(onFailure, "Maven build did not produce a process");
            return;
        }
        AtomicBoolean delivered = new AtomicBoolean();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                deliver(delivered, event.getExitCode(), onSuccess, onFailure);
            }
        });
        if (handler.isProcessTerminated()) {
            Integer exitCode = handler.getExitCode();
            deliver(delivered, exitCode != null ? exitCode : -1, onSuccess, onFailure);
        }
    }

    private static void deliver(@NotNull AtomicBoolean delivered, int exitCode,
                                @NotNull Runnable onSuccess, @NotNull Consumer<String> onFailure) {
        if (!delivered.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (exitCode == 0) {
                onSuccess.run();
            } else {
                onFailure.accept("'mvn " + PACKAGE_GOAL + "' exited with code " + exitCode);
            }
        });
    }

    private static void failOnEdt(@NotNull Consumer<String> onFailure, @NotNull String message) {
        ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(message));
    }

    @Nullable
    private static MavenProject mavenProject(@NotNull Project project, @NotNull Module module) {
        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        return manager != null ? manager.findProject(module) : null;
    }
}
