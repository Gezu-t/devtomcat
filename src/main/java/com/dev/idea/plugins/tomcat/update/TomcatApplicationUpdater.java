package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatDeploymentPaths;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.update.RunningApplicationUpdater;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.dev.idea.plugins.tomcat.utils.CompilerSupport;
import com.dev.idea.plugins.tomcat.utils.DeploymentRebuilder;
import com.dev.idea.plugins.tomcat.utils.ProcessStopSupport;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Executes "Update Running Application" actions for DevTomcat.
 *
 * <p>Handles the four update actions configured via {@link UpdateConfig}:
 * <ul>
 *   <li>{@code UPDATE_RESOURCES} / {@code UPDATE_CLASSES_AND_RESOURCES} — compile + auto-reload</li>
 *   <li>{@code REDEPLOY} — compile + force context reload</li>
 *   <li>{@code RESTART_SERVER} — stop process + re-execute configuration</li>
 * </ul>
 *
 * <p>Triggered by Ctrl+F10 (via {@link TomcatRunningApplicationUpdaterProvider})
 * or on frame deactivation (via {@link TomcatFrameDeactivationListener}).
 */
public class TomcatApplicationUpdater implements RunningApplicationUpdater {

    private static final Logger LOG = Logger.getInstance(TomcatApplicationUpdater.class);

    private final Project project;
    private final TomcatProcessHandler processHandler;
    private final TomcatRunConfiguration configuration;
    private final String action;

    public TomcatApplicationUpdater(@NotNull Project project,
                                     @NotNull TomcatProcessHandler processHandler,
                                     @NotNull TomcatRunConfiguration configuration,
                                     @NotNull String action) {
        this.project = project;
        this.processHandler = processHandler;
        this.configuration = configuration;
        this.action = action;
    }

    @Override
    public String getDescription() {
        return displayForAction(action, "Update application");
    }

    @Override
    public String getShortName() {
        return "DevTomcat";
    }

    @Nullable
    @Override
    public Icon getIcon() {
        return switch (action) {
            case UpdateConfig.RESTART_SERVER -> AllIcons.Actions.Restart;
            case UpdateConfig.REDEPLOY       -> AllIcons.Actions.Rerun;
            default                          -> AllIcons.Actions.Compile;
        };
    }

    @Override
    public void performUpdate(@NotNull AnActionEvent event) {
        UpdateConfig updateConfig = configuration.getConfigData().getUpdateConfig();
        if (updateConfig.isShowUpdateDialog()) {
            boolean isLocal = !configuration.isRemoteMode();
            TomcatUpdateDialog dialog = new TomcatUpdateDialog(project, configuration.getName(), action, isLocal);
            if (!dialog.showAndGet()) return;
            executeUpdate(dialog.getSelectedAction());
        } else {
            executeUpdate(action);
        }
    }

    /**
     * Executes the configured update action using the action passed at construction.
     */
    public void executeUpdate() {
        executeUpdate(action);
    }

    public void executeUpdate(@NotNull String selectedAction) {
        TomcatDeploymentLogger logger = processHandler.getDeploymentLogger();
        logger.logServerInfo("Update triggered: " + selectedAction);

        // Save documents — must run on EDT. invokeAndWait deadlocks if already on EDT
        // (e.g. called from TomcatRunner.doExecute), so dispatch appropriately.
        Runnable saveAll = () -> FileDocumentManager.getInstance().saveAllDocuments();
        if (ApplicationManager.getApplication().isDispatchThread()) {
            saveAll.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(saveAll);
        }

        switch (selectedAction) {
            case UpdateConfig.UPDATE_RESOURCES ->
                    doUpdateResourcesOnly(logger);
            case UpdateConfig.UPDATE_CLASSES_AND_RESOURCES ->
                    doUpdateClassesAndResources(logger);
            case UpdateConfig.REDEPLOY ->
                    doRedeploy(logger);
            case UpdateConfig.RESTART_SERVER ->
                    doRestart(logger);
            default ->
                    logger.logServerWarning("Unknown update action: " + selectedAction);
        }
    }

    /**
     * Syncs static resources (JSPs, HTML, CSS, XML, etc.) to the deployed artifact.
     *
     * <p>Triggers an incremental build via {@link CompilerManager#make} so IntelliJ
     * copies changed resource files from the source tree to the artifact output directory.
     * If only resource files changed (no Java), the compiler finds nothing to recompile
     * and the build completes in milliseconds.
     *
     * <p>For exploded artifacts Tomcat serves files directly from {@code docBase}, so
     * once the resource is in the artifact output directory it is live on the next request.
     * WAR artifacts are not re-copied since the incremental build does not repackage them;
     * use "Redeploy" for WAR-based deployments.
     */
    private void doUpdateResourcesOnly(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(configuration, logger);
        CompilerSupport.compileAndThen(project, logger,
                "Syncing resources...",
                "Build aborted; resource sync cancelled",
                "Build failed",
                DeploymentCompileScope.resolve(project, deployments, logger),
                warnings -> {
                    logger.logServerInfo("Resources synced" + warningSuffix(warnings));
                    syncBothPipelines(deployments, logger);
                });
    }

    /**
     * Runs both mirror pipelines over {@code deployments}, classes first.
     *
     * <p>Class sync mirrors module output (which includes resource roots —
     * Maven puts {@code src/main/resources/*} into {@code target/classes/},
     * Gradle into {@code build/resources/main/}) into each exploded
     * deployment's {@code WEB-INF/classes/}; without it the Make task produces
     * fresh files in {@code target/classes/} but Tomcat keeps serving the
     * previous {@code mvn package}'d copy — the ".properties sometimes stale"
     * symptom. Web-resources sync then mirrors webapp source files (JSP, JS,
     * CSS, HTML, images, taglibs) into the exploded artifact root — IntelliJ's
     * Make never copies {@code src/main/webapp/}; only Maven's
     * {@code prepare-package} does, which Update never triggers — so without
     * it JSP edits silently never reach Tomcat.
     *
     * @return the class-sync report (callers use {@code didAnything()} to
     *         choose hot-swap vs context restart)
     */
    private DeployedClassesSync.SyncReport syncBothPipelines(@NotNull List<Deployment> deployments,
                                                             @NotNull TomcatDeploymentLogger logger) {
        DeployedClassesSync.SyncReport classReport =
                DeployedClassesSync.syncDeployments(project, deployments, logger);
        WebResourcesSync.syncDeployments(project, deployments, logger);
        return classReport;
    }

    /**
     * Triggers incremental compilation then applies changes to the running server.
     *
     * <p>For exploded artifacts, resource changes (JSP, HTML, CSS) are served directly
     * from {@code docBase} on the next request. Java class changes require a classloader
     * reload — achieved by touching the context XML descriptor, which Tomcat's deployer
     * watches and triggers an undeploy/redeploy cycle with a fresh classloader.
     *
     * <p>For WAR artifacts the WAR file is re-copied to webapps after compilation.
     */
    private void doUpdateClassesAndResources(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(configuration, logger);
        CompilerSupport.compileAndThen(project, logger,
                "Compiling project...",
                "Compilation aborted",
                "Compilation failed",
                DeploymentCompileScope.resolve(project, deployments, logger),
                warnings -> {
                    logger.logServerInfo("Compilation successful" + warningSuffix(warnings));
                    // Both syncs run BEFORE touching context.xml — the deployer's
                    // reload trigger should see the new bytes already in place.
                    DeployedClassesSync.SyncReport classReport =
                            syncBothPipelines(deployments, logger);
                    redeployWarArtifacts(logger);

                    // When Tomcat is running under the IDE debugger, redefine the
                    // changed classes in the live JVM instead of forcing a context
                    // reload. A method-body change applies in place — no new
                    // classloader, no app re-init, no lost HTTP sessions or
                    // in-memory caches, near-instant. DebugHotSwap runs the context
                    // restart fallback (touchExplodedContextXml) automatically when
                    // the change is structural and cannot be redefined, so a change
                    // is never silently dropped. When not debugging, restart directly.
                    DebuggerSession session = DebugHotSwap.findHotSwappableSession(project, processHandler);
                    if (session != null) {
                        DebugHotSwap.reloadThenMaybeRestart(project, session,
                                classReport.didAnything(), logger,
                                () -> touchExplodedContextXml(logger));
                    } else {
                        touchExplodedContextXml(logger);
                    }
                });
    }

    /**
     * Triggers compilation then forces redeployment of all artifacts:
     * rewrites context.xml for exploded dirs, re-copies WAR files.
     */
    private void doRedeploy(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(configuration, logger);
        Runnable compileAndDeploy = () -> CompilerSupport.compileAndThen(project, logger,
                "Compiling and redeploying...",
                "Compilation aborted",
                "Compilation failed",
                DeploymentCompileScope.resolve(project, deployments, logger),
                warnings -> {
                    logger.logServerInfo("Compilation successful" + warningSuffix(warnings) + ", redeploying artifacts...");
                    // Fresh bytes land before the forced redeploy (context.xml
                    // rewrite below) hands them a fresh classloader.
                    syncBothPipelines(deployments, logger);
                    redeployAllArtifacts(logger);
                });

        // The opt-in gate lives INSIDE rebuildThenContinue (it reads the
        // UpdateConfig itself) so the flag-to-behavior connection is pinned by
        // tests; the module resolution is a lazy supplier, evaluated only when
        // the option is on. Rebuild completion arrives async (EDT-delivered by
        // the rebuilder), so nothing here blocks.
        DeploymentRebuilder rebuilder = DeploymentRebuilder.getInstance();
        rebuildThenContinue(configuration.getConfigData().getUpdateConfig(),
                () -> rebuildableWarModules(deployments,
                        d -> com.intellij.openapi.application.ReadAction.compute(
                                () -> DeploymentModuleResolver.resolve(d, project)),
                        rebuilder == null ? null : m -> rebuilder.canRebuild(project, m),
                        logger),
                rebuilder != null ? moduleInvoker(rebuilder, logger) : null,
                logger, compileAndDeploy);
    }

    /**
     * The modules behind valid WAR (non-exploded) deployments, in deployment
     * order — filtered through {@code canRebuild} when a rebuilder is present
     * (a WAR module the build tool cannot package gets a console warning, not a
     * doomed build attempt). With {@code canRebuild == null} (no rebuilder) the
     * raw modules are returned so {@link #rebuildThenContinue} can name the
     * missing integration. Duplicates are left in; the chain dedupes.
     * Package-visible with resolution and capability injected so tests pin the
     * exploded-skip, the unresolved-skip, and the filter-with-warning.
     */
    @NotNull
    static List<com.intellij.openapi.module.Module> rebuildableWarModules(
            @NotNull List<Deployment> deployments,
            @NotNull Function<Deployment, com.intellij.openapi.module.Module> resolve,
            @Nullable java.util.function.Predicate<com.intellij.openapi.module.Module> canRebuild,
            @NotNull TomcatDeploymentLogger logger) {
        List<com.intellij.openapi.module.Module> modules = new ArrayList<>();
        for (Deployment d : deployments) {
            if (!d.isValid() || d.isExploded()) continue;
            com.intellij.openapi.module.Module m = resolve.apply(d);
            if (m != null) modules.add(m);
        }
        if (canRebuild == null || modules.isEmpty()) return modules;
        List<com.intellij.openapi.module.Module> rebuildable = new ArrayList<>();
        for (com.intellij.openapi.module.Module m : modules) {
            if (canRebuild.test(m)) {
                rebuildable.add(m);
            } else {
                logger.logServerWarning("Rebuild before redeploy: module '" + m.getName()
                        + "' is not a resolved Maven module — package step skipped for it.");
            }
        }
        return rebuildable;
    }

    /** Adapts the platform rebuilder to the platform-free chain seam, with a per-module console line. */
    @NotNull
    private RebuildInvoker<com.intellij.openapi.module.Module> moduleInvoker(
            @NotNull com.dev.idea.plugins.tomcat.utils.DeploymentRebuilder rebuilder,
            @NotNull TomcatDeploymentLogger logger) {
        return (module, onSuccess, onFailure) -> {
            logger.logServerInfo("Rebuilding module '" + module.getName()
                    + "' (build-tool package via the IDE's Maven integration)...");
            rebuilder.rebuild(project, module, onSuccess, onFailure);
        };
    }

    /** Rebuild seam: packages one module, then calls exactly one callback. Platform-free for tests. */
    interface RebuildInvoker<M> {
        void rebuild(@NotNull M module, @NotNull Runnable onSuccess,
                     @NotNull java.util.function.Consumer<String> onFailure);
    }

    /**
     * The rebuild-before-redeploy gate. Reads the opt-in flag off the
     * {@code UpdateConfig} ITSELF (so the flag-to-behavior connection is
     * test-pinned, not glue) and evaluates {@code warModules} lazily — the
     * (read-action) module resolution never runs for the default-off case.
     * When disabled, no modules resolved, or nothing rebuildable: runs
     * {@code continuation} directly — exactly today's behavior. When enabled
     * with modules but no {@code rebuilder}: warns on the console (Maven
     * integration absent) and continues. Otherwise rebuilds the deduped
     * modules sequentially and runs {@code continuation} only after ALL
     * succeed; the first failure logs a console error and stops — nothing is
     * deployed after a failed build. Package-visible and platform-free
     * (modules generic, invoker injected) so tests pin the gate, the laziness,
     * the ordering, the failure stop, and the dedupe.
     */
    static <M> void rebuildThenContinue(@NotNull UpdateConfig updateConfig,
                                        @NotNull java.util.function.Supplier<List<M>> warModules,
                                        @Nullable RebuildInvoker<M> rebuilder,
                                        @NotNull TomcatDeploymentLogger logger,
                                        @NotNull Runnable continuation) {
        if (!updateConfig.isRebuildBeforeRedeploy()) {
            continuation.run();
            return;
        }
        List<M> modules = warModules.get();
        if (modules.isEmpty()) {
            continuation.run();
            return;
        }
        if (rebuilder == null) {
            logger.logServerWarning("Rebuild before redeploy is enabled, but the IDE's Maven"
                    + " integration is unavailable — package step skipped, redeploying as-is.");
            continuation.run();
            return;
        }
        runRebuildChain(modules, rebuilder, logger, continuation);
    }

    /**
     * The shared rebuild chain: dedupes (first occurrence wins), rebuilds
     * sequentially, runs {@code onAllSucceeded} only after every module
     * packaged; the first failure logs the abort error and stops. Used by the
     * redeploy gate and the "Rebuild and Deploy" balloon remedy.
     */
    static <M> void runRebuildChain(@NotNull List<M> modules,
                                    @NotNull RebuildInvoker<M> rebuilder,
                                    @NotNull TomcatDeploymentLogger logger,
                                    @NotNull Runnable onAllSucceeded) {
        List<M> deduped = new ArrayList<>(new java.util.LinkedHashSet<>(modules));
        rebuildModulesSequentially(deduped, 0, rebuilder, onAllSucceeded,
                message -> logger.logServerError(
                        "Build-tool package failed — redeploy aborted, nothing deployed: " + message));
    }

    /** Sequential rebuild chain: index {@code i} onward, success-continues, first failure stops. */
    private static <M> void rebuildModulesSequentially(@NotNull List<M> modules, int i,
                                                       @NotNull RebuildInvoker<M> rebuilder,
                                                       @NotNull Runnable onAllSucceeded,
                                                       @NotNull java.util.function.Consumer<String> onFailure) {
        if (i >= modules.size()) {
            onAllSucceeded.run();
            return;
        }
        rebuilder.rebuild(modules.get(i),
                () -> rebuildModulesSequentially(modules, i + 1, rebuilder, onAllSucceeded, onFailure),
                onFailure);
    }

    /**
     * Compiles the project, then stops and re-executes the run configuration so
     * the restarted Tomcat picks up the latest class files.
     */
    private void doRestart(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(configuration, logger);
        String originalExecutorId = processHandler.getExecutorId();

        CompilerSupport.compileAndThen(project, logger,
                "Compiling before restart...",
                "Compilation aborted; restart cancelled",
                "Compilation failed; restart cancelled",
                DeploymentCompileScope.resolve(project, deployments, logger),
                warnings -> {
            logger.logServerInfo("Compilation successful" + warningSuffix(warnings) + ", restarting Tomcat...");

            // No pre-stop class/web sync here: the relaunch builds fresh
            // JavaParameters through TomcatJavaParametersBuilder.setupDeploymentArtifacts,
            // which mirrors the freshly-compiled classes and webapp resources into each
            // exploded deployment before the new Tomcat starts. Mirroring here too —
            // into the same exploded dirs, moments before stopping the old process —
            // would just repeat that (potentially multi-second) work for nothing. See
            // DeployedClassesSync javadoc for why the launch path performs the mirror.

            // Capture before destroy — see ProcessStopSupport javadoc for race rationale
            Executor resolvedExecutor = ExecutorRegistry.getInstance().getExecutorById(originalExecutorId);
            if (resolvedExecutor == null) {
                LOG.warn("Executor '" + originalExecutorId + "' not found — falling back to Run mode");
                logger.logServerWarning("Could not restore executor '" + originalExecutorId + "'. Restarting in Run mode.");
                resolvedExecutor = DefaultRunExecutor.getRunExecutorInstance();
            }
            RunContentDescriptor descriptor = ProcessStopSupport.findDescriptor(project, processHandler);
            final Executor capturedExecutor = resolvedExecutor;

            // Carry the running process's resolved ports (and JDWP port, if debugging) to
            // the relaunch. Without this the new launch re-runs port conflict detection,
            // sees the just-released socket in OS TIME_WAIT state, and bumps the port
            // upward — the same "port keeps increasing" symptom the rerun path already
            // guards against in TomcatRunnerDelegate.stopAndRelaunch.
            com.dev.idea.plugins.tomcat.model.PortConfig carriedPorts = processHandler.getResolvedPorts();
            int carriedDebugPort = processHandler.getResolvedDebugPort();
            // Prefer the handler's original launch settings, the same stable identity the
            // runner delegate uses for descriptor lookup. findSettings(configuration) is a
            // fallback for handlers launched outside the normal editor flow.
            RunnerAndConfigurationSettings preferredSettings = processHandler.getLaunchSettings();

            ProcessStopSupport.stopCleanAndThen(project, processHandler, descriptor, capturedExecutor, () -> {
                try {
                    RunnerAndConfigurationSettings settings = preferredSettings != null
                            ? preferredSettings
                            : RunManager.getInstance(project).findSettings(configuration);
                    if (settings != null) {
                        com.intellij.execution.runners.ExecutionEnvironment newEnv =
                                com.intellij.execution.runners.ExecutionEnvironmentBuilder
                                        .create(capturedExecutor, settings).build();
                        if (carriedPorts != null) {
                            newEnv.putUserData(
                                    com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState.CARRIED_PORTS_KEY,
                                    carriedPorts);
                        }
                        if (carriedDebugPort > 0) {
                            newEnv.putUserData(
                                    com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState.CARRIED_DEBUG_PORT_KEY,
                                    carriedDebugPort);
                        }
                        newEnv.getRunner().execute(newEnv);
                        LOG.info("Tomcat restarted in " + capturedExecutor.getActionName() + " mode"
                                + (carriedPorts != null ? " (reusing ports)" : ""));
                    } else {
                        logger.logServerError("Could not find run configuration settings for restart");
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to restart Tomcat: " + configuration.getName(), e);
                    ProcessStopSupport.purgeTerminatedDescriptors(project);
                    logger.logServerError("Failed to restart: " + e.getMessage());
                    notifyRestartFailed(project, configuration.getName(), e.getMessage());
                }
            });
        }); // CompilerSupport.compileAndThen
    }

    /**
     * Re-copies WAR artifacts to the webapps directory after compilation.
     * Exploded artifacts are skipped — Tomcat handles their reload automatically.
     *
     * <p>A WAR whose deployed copy is already up to date (same size, target
     * mtime not older — see {@link TomcatProjectUtils#isUpToDateCopy}) is NOT
     * re-copied: Tomcat redeploys a context whenever the WAR's timestamp
     * advances, so an unconditional copy of identical bytes restarted the
     * context (dropping sessions) for nothing.
     */
    private void redeployWarArtifacts(@NotNull TomcatDeploymentLogger logger) {
        Path webappsDir = TomcatProjectUtils.getWebappsDirectory(configuration, processHandler.getRunId());
        if (webappsDir == null) {
            LOG.warn("No webapps directory resolved; WAR artifacts will not be updated");
            logger.logServerWarning("Cannot locate webapps directory; WAR update skipped");
            return;
        }
        List<Deployment> blocked = redeployWarArtifactsInto(configuration.getDeployments(),
                webappsDir, logger, d -> DeploymentStaleness.evaluate(project, d));
        notifyBlockedStaleWars(blocked, webappsDir, logger);
    }

    /**
     * The WAR re-copy loop of {@link #redeployWarArtifacts}, over an explicit
     * deployment list and webapps directory. Package-visible and platform-free
     * (verdicts injected) so tests can pin both skips: an unchanged WAR must
     * NOT be re-copied (Tomcat restarts the context on any mtime advance), and
     * a {@linkplain DeploymentStaleness stale} WAR must NOT be deployed
     * silently — it is blocked and reported to the caller for the
     * "Deploy Anyway" offer.
     *
     * <p>Order matters: the up-to-date check runs BEFORE the verdict, so a WAR
     * that would not be copied anyway is never "blocked" (and costs no
     * staleness evaluation).
     *
     * @return the STALE deployments whose copy was blocked
     */
    @NotNull
    static List<Deployment> redeployWarArtifactsInto(@NotNull List<Deployment> deployments,
                                                     @NotNull Path webappsDir,
                                                     @NotNull TomcatDeploymentLogger logger,
                                                     @NotNull Function<Deployment, DeploymentStaleness.Verdict> verdicts) {
        List<Deployment> blocked = new ArrayList<>();
        for (Deployment deployment : deployments) {
            if (!deployment.isValid() || deployment.isExploded()) continue;
            Path source = deployment.getResolvedPath();
            if (source == null) continue;

            try {
                String contextName = resolveContextName(deployment.getContextPath());
                Path target = TomcatDeploymentPaths.warFile(webappsDir, contextName);
                if (TomcatProjectUtils.isUpToDateCopy(source, target)) {
                    logger.logServerInfo("WAR unchanged since last deploy — copy skipped"
                            + " (no context restart): " + deployment.getDisplayName());
                    continue;
                }
                DeploymentStaleness.Verdict verdict = verdicts.apply(deployment);
                if (verdict.isStale()) {
                    logStaleWarBlocked(deployment.getDisplayName(), verdict, logger);
                    blocked.add(deployment);
                    continue;
                }
                TomcatProjectUtils.atomicCopy(source, target);
                logger.logServerInfo("Re-deployed WAR: " + deployment.getDisplayName());
            } catch (IOException e) {
                LOG.warn("Failed to re-deploy WAR: " + source, e);
                logger.logServerError("Failed to re-deploy WAR '" +
                        deployment.getDisplayName() + "': " + e.getMessage());
            }
        }
        return blocked;
    }

    /** Console evidence line for a blocked stale-WAR copy — fired on every action, never gated. */
    private static void logStaleWarBlocked(@NotNull String name,
                                           @NotNull DeploymentStaleness.Verdict verdict,
                                           @NotNull TomcatDeploymentLogger logger) {
        logger.logServerWarning("Stale WAR not deployed: '" + name
                + "' predates the compiled output of module '" + verdict.moduleName()
                + "' (" + verdict.newerOutput() + " is "
                + DeploymentStaleness.describeAge(verdict.newerByMillis())
                + " newer). Deploying it would ship old code. Rebuild the WAR with"
                + " 'mvn package' / 'gradle war', then update again — or click"
                + " 'Deploy Anyway' in the notification to deploy it as-is.");
    }

    /**
     * Once-per-session balloon for blocked stale WARs with the explicit
     * "Deploy Anyway" override. Platform glue — the testable wiring lives in
     * {@link #notifyBlockedStaleWarDeployments}.
     */
    private void notifyBlockedStaleWars(@NotNull List<Deployment> blocked,
                                        @NotNull Path webappsDir,
                                        @NotNull TomcatDeploymentLogger logger) {
        if (blocked.isEmpty() || project == null || project.isDisposed()) return;
        notifyBlockedStaleWarDeployments(blocked, logger,
                SessionNotificationGate.INSTANCE,
                project.getLocationHash() + "|" + configuration.getName(),
                new UpdateNotifier() {
                    @Override
                    public void infoWithAction(@NotNull String title, @NotNull String content,
                                               @NotNull String actionLabel, @NotNull Runnable action) {
                        TomcatNotifier.notifyWithAction(project, title, content,
                                com.intellij.notification.NotificationType.WARNING,
                                actionLabel, action);
                    }

                    @Override
                    public void infoWithActions(@NotNull String title, @NotNull String content,
                                                @NotNull String actionLabel, @NotNull Runnable action,
                                                @NotNull String secondActionLabel, @NotNull Runnable secondAction) {
                        TomcatNotifier.notifyWithActions(project, title, content,
                                com.intellij.notification.NotificationType.WARNING,
                                actionLabel, action, secondActionLabel, secondAction);
                    }

                    @Override
                    public void warning(@NotNull String title, @NotNull String content) {
                        TomcatNotifier.warning(project, title, content);
                    }
                },
                () -> com.intellij.openapi.application.ApplicationManager.getApplication()
                        .executeOnPooledThread(() -> {
                            // Explicit override: copy exactly the blocked set, verdicts
                            // bypassed. The up-to-date skip still applies — re-copying
                            // bytes already deployed would only restart the context.
                            redeployWarArtifactsInto(blocked, webappsDir, logger,
                                    d -> DeploymentStaleness.Verdict.fresh());
                            logger.logServerInfo("Deploy Anyway: deployed "
                                    + blocked.size() + " stale WAR(s) as last built.");
                        }),
                rebuildAndDeployAction(blocked, webappsDir, logger));
    }

    /**
     * The "Rebuild and Deploy" balloon remedy: packages the blocked WARs'
     * modules through the build-tool integration, then re-runs the blocked copy
     * through {@link #redeployBlockedWithReVerdict}. {@code null} — no second
     * button — when no rebuilder is available or no blocked deployment
     * resolves to a rebuildable module (platform glue; the decision itself is
     * the test-pinned {@link #buildRebuildAndDeployRemedy}).
     */
    @Nullable
    private Runnable rebuildAndDeployAction(@NotNull List<Deployment> blocked,
                                            @NotNull Path webappsDir,
                                            @NotNull TomcatDeploymentLogger logger) {
        com.dev.idea.plugins.tomcat.utils.DeploymentRebuilder rebuilder =
                com.dev.idea.plugins.tomcat.utils.DeploymentRebuilder.getInstance();
        List<com.intellij.openapi.module.Module> modules = rebuilder == null ? List.of()
                : rebuildableWarModules(blocked,
                        d -> com.intellij.openapi.application.ReadAction.compute(
                                () -> DeploymentModuleResolver.resolve(d, project)),
                        m -> rebuilder.canRebuild(project, m), logger);
        return buildRebuildAndDeployRemedy(
                rebuilder != null ? moduleInvoker(rebuilder, logger) : null, modules, logger,
                () -> com.intellij.openapi.application.ApplicationManager.getApplication()
                        .executeOnPooledThread(() ->
                                redeployBlockedWithReVerdict(project, blocked, webappsDir, logger)));
    }

    /**
     * The remedy-availability decision + wiring: a runnable that rebuilds
     * {@code modules} and, only after the whole chain succeeds, runs
     * {@code reVerdictDeploy}; {@code null} (no second balloon button) when no
     * invoker is available or nothing is rebuildable. Package-visible and
     * platform-free so tests pin both null-guards and the chain-then-deploy
     * ordering.
     */
    @Nullable
    static <M> Runnable buildRebuildAndDeployRemedy(@Nullable RebuildInvoker<M> invoker,
                                                    @NotNull List<M> modules,
                                                    @NotNull TomcatDeploymentLogger logger,
                                                    @NotNull Runnable reVerdictDeploy) {
        if (invoker == null || modules.isEmpty()) return null;
        return () -> runRebuildChain(modules, invoker, logger, reVerdictDeploy);
    }

    /**
     * Re-runs the blocked copy WITH verdicts re-evaluated against the live
     * project model — after a real rebuild the WAR is fresh and deploys; a
     * somehow-still-stale WAR stays blocked. This path NEVER bypasses
     * verdicts; "Deploy Anyway" is the only bypass. Package-visible (called
     * off the EDT) so a platform test can pin the re-evaluation itself —
     * swapping the evaluator for a constant would ship stale code silently.
     */
    static void redeployBlockedWithReVerdict(@NotNull Project project,
                                             @NotNull List<Deployment> blocked,
                                             @NotNull Path webappsDir,
                                             @NotNull TomcatDeploymentLogger logger) {
        List<Deployment> stillBlocked = redeployWarArtifactsInto(blocked, webappsDir, logger,
                d -> DeploymentStaleness.evaluate(project, d));
        if (stillBlocked.isEmpty()) {
            logger.logServerInfo("Rebuild and Deploy: rebuilt and redeployed "
                    + blocked.size() + " WAR(s).");
        } else {
            logger.logServerWarning("Rebuild and Deploy: " + stillBlocked.size()
                    + " WAR(s) still predate their module's compiled output"
                    + " after the rebuild — left blocked.");
        }
    }

    /**
     * The blocked-stale-WAR notification wiring: balloon once per
     * (run configuration, blocked set) per IDE session, "Deploy Anyway" action
     * passed through. Collaborators injected — package-visible so tests pin
     * the gating and the action plumbing without the platform. Console
     * evidence is already logged per-deployment by the copy loop.
     */
    static void notifyBlockedStaleWarDeployments(@NotNull List<Deployment> blocked,
                                                 @NotNull TomcatDeploymentLogger logger,
                                                 @NotNull SessionNotificationGate gate,
                                                 @NotNull String scopeId,
                                                 @NotNull UpdateNotifier notifier,
                                                 @NotNull Runnable deployAnyway) {
        notifyBlockedStaleWarDeployments(blocked, logger, gate, scopeId, notifier, deployAnyway, null);
    }

    /**
     * Overload with the optional "Rebuild and Deploy" remedy: when
     * {@code rebuildAndDeploy} is non-null (a build-tool rebuilder can package
     * the blocked WARs' modules) the balloon offers it FIRST, alongside
     * "Deploy Anyway" — rebuild-then-redeploy is the correct fix; deploying old
     * bytes is the fallback. Gating and console evidence unchanged.
     */
    static void notifyBlockedStaleWarDeployments(@NotNull List<Deployment> blocked,
                                                 @NotNull TomcatDeploymentLogger logger,
                                                 @NotNull SessionNotificationGate gate,
                                                 @NotNull String scopeId,
                                                 @NotNull UpdateNotifier notifier,
                                                 @NotNull Runnable deployAnyway,
                                                 @Nullable Runnable rebuildAndDeploy) {
        if (blocked.isEmpty()) return;
        java.util.Set<String> key = new java.util.HashSet<>();
        StringBuilder names = new StringBuilder();
        for (Deployment d : blocked) {
            if (names.length() > 0) names.append(", ");
            names.append(d.getDisplayName());
            key.add(d.getDisplayName());
        }
        if (!gate.shouldNotify("stale-war-blocked|" + scopeId, key)) return;

        String plural = blocked.size() == 1 ? "WAR is" : "WARs are";
        String title = blocked.size() == 1
                ? "Stale WAR not deployed" : blocked.size() + " stale WARs not deployed";
        if (rebuildAndDeploy != null) {
            notifier.infoWithActions(title,
                    names + ": the " + plural + " older than the modules' compiled output —"
                            + " deploying would ship old code. Rebuild the WAR and deploy it,"
                            + " or deploy the old WAR as-is.",
                    "Rebuild and Deploy", rebuildAndDeploy,
                    "Deploy Anyway", deployAnyway);
        } else {
            notifier.infoWithAction(title,
                    names + ": the " + plural + " older than the modules' compiled output —"
                            + " deploying would ship old code. Rebuild with 'mvn package' /"
                            + " 'gradle war' and update again, or deploy the old WAR as-is.",
                    "Deploy Anyway", deployAnyway);
        }
    }

    /**
     * Touches context XML descriptors for exploded artifacts, triggering Tomcat's
     * deployer to undeploy and redeploy with a fresh classloader. This makes
     * compiled Java class changes visible without a full server restart.
     *
     * <p>WAR artifacts are skipped — their reload is handled by {@link #redeployWarArtifacts}.
     */
    private void touchExplodedContextXml(@NotNull TomcatDeploymentLogger logger) {
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration, processHandler.getRunId());
        if (catalinaBase == null) return;

        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);

        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) continue;

            String contextName = resolveContextName(deployment.getContextPath());
            Path contextFile = TomcatDeploymentPaths.contextDescriptor(contextXmlDir, contextName);
            if (Files.exists(contextFile)) {
                try {
                    Files.setLastModifiedTime(contextFile,
                            FileTime.fromMillis(System.currentTimeMillis()));
                    logger.logServerInfo("Context reload triggered: " + deployment.getDisplayName());
                } catch (IOException e) {
                    LOG.warn("Failed to touch context XML: " + contextFile, e);
                    logger.logServerWarning("Could not trigger context reload for " +
                            deployment.getDisplayName());
                }
            }
        }
    }

    /**
     * Forces redeployment of all artifacts.
     * <ul>
     *   <li>Exploded: rewrites context.xml to force Tomcat undeploy + redeploy</li>
     *   <li>WAR: re-copies the WAR file to webapps</li>
     * </ul>
     *
     * <p><b>Deliberate semantics:</b> a WAR whose deployed copy is already up
     * to date is NOT re-copied, so an explicit Redeploy of an unchanged WAR no
     * longer forces a context restart — re-copying identical bytes only
     * dropped live sessions to redeploy the same old code. A new WAR only
     * comes from the build tool ({@code mvn package} / {@code gradle war});
     * once it exists, Redeploy picks it up.
     */
    private void redeployAllArtifacts(@NotNull TomcatDeploymentLogger logger) {
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration, processHandler.getRunId());
        if (catalinaBase == null) {
            logger.logServerError("Could not determine CATALINA_BASE directory");
            return;
        }

        Path webappsDir = catalinaBase.resolve(DIR_WEBAPPS);
        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);
        boolean preserveSessions = configuration.getConfigData()
                .getDeploymentConfig().isPreserveSessions();
        List<Deployment> blockedStale = new ArrayList<>();

        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid()) continue;
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;

            String contextName = resolveContextName(deployment.getContextPath());

            try {
                if (deployment.isExploded()) {
                    // Generate full context XML (PostResources for unpackaged
                    // library JARs included). Matches initial deployment so
                    // the redeployed context configuration is consistent.
                    // Pass the configured TomcatInfo so the generator can omit
                    // the <Resources> block on Tomcat 7 (PostResources is a
                    // Tomcat 8 element).
                    Path contextFile = TomcatDeploymentPaths.contextDescriptor(contextXmlDir, contextName);
                    // Reuse the seam this run launched with, so the rewritten
                    // descriptor matches what the running server already reads.
                    // Taken from the handler — resolving it here would block the
                    // UI thread this update runs on.
                    String contextXml = com.dev.idea.plugins.tomcat.runner.LocalDeploymentStrategy.buildContextXml(
                            deployment, artifactPath, preserveSessions, project,
                            configuration.getTomcatInfo(), logger,
                            processHandler.getLaunchPathMapper());
                    TomcatProjectUtils.atomicWriteString(contextFile, contextXml);
                    logger.logServerInfo("Redeployed (context rewrite): " + deployment.getDisplayName());
                } else {
                    boolean staleBlocked = redeployWarDeployment(deployment, artifactPath,
                            webappsDir, logger, d -> DeploymentStaleness.evaluate(project, d));
                    if (staleBlocked) blockedStale.add(deployment);
                }
            } catch (IOException e) {
                LOG.warn("Failed to redeploy: " + artifactPath, e);
                logger.logServerError("Failed to redeploy '" +
                        deployment.getDisplayName() + "': " + e.getMessage());
            }
        }
        notifyBlockedStaleWars(blockedStale, webappsDir, logger);
    }

    /**
     * The WAR branch of {@link #redeployAllArtifacts}: copies the WAR into
     * webapps unless the deployed copy is already up to date (the deliberate
     * no-restart semantics documented there) or the WAR is
     * {@linkplain DeploymentStaleness stale} — an explicit Redeploy of a stale
     * WAR is blocked exactly like the update path; "Deploy Anyway" is the
     * intentional-override door. Package-visible, verdicts injected, so tests
     * pin both skips.
     *
     * @return {@code true} when the copy was blocked as stale
     */
    static boolean redeployWarDeployment(@NotNull Deployment deployment,
                                         @NotNull Path artifactPath,
                                         @NotNull Path webappsDir,
                                         @NotNull TomcatDeploymentLogger logger,
                                         @NotNull Function<Deployment, DeploymentStaleness.Verdict> verdicts) throws IOException {
        String contextName = resolveContextName(deployment.getContextPath());
        Path target = TomcatDeploymentPaths.warFile(webappsDir, contextName);
        if (TomcatProjectUtils.isUpToDateCopy(artifactPath, target)) {
            logger.logServerInfo("WAR unchanged since last deploy — copy skipped"
                    + " (no context restart): " + deployment.getDisplayName()
                    + ". Rebuild it with the build tool ('mvn package' /"
                    + " 'gradle war') to produce a new WAR to redeploy.");
            return false;
        }
        DeploymentStaleness.Verdict verdict = verdicts.apply(deployment);
        if (verdict.isStale()) {
            logStaleWarBlocked(deployment.getDisplayName(), verdict, logger);
            return true;
        }
        TomcatProjectUtils.atomicCopy(artifactPath, target);
        logger.logServerInfo("Redeployed WAR: " + deployment.getDisplayName());
        return false;
    }

    @NotNull
    private static String resolveContextName(@Nullable String contextPath) {
        return ContextPathUtils.resolveContextNameSafe(contextPath, LOG);
    }

    /**
     * Shows the Update dialog and executes the selected action.
     * Shared entry point for Services panel actions and re-run interception in runners.
     */
    public static void showDialogAndExecute(@NotNull Project project,
                                             @NotNull TomcatProcessHandler handler,
                                             @NotNull TomcatRunConfiguration config) {
        String defaultAction = config.getConfigData().getUpdateConfig().getOnUpdate();
        boolean isLocal = !config.isRemoteMode();
        TomcatUpdateDialog dialog = new TomcatUpdateDialog(project, config.getName(), defaultAction, isLocal);
        if (!dialog.showAndGet()) return;
        new TomcatApplicationUpdater(project, handler, config, dialog.getSelectedAction()).executeUpdate();
    }

    /**
     * Shows a balloon notification when a restart fails after the old process has already
     * been stopped. The console log entry may be scrolled past — a balloon ensures the user
     * sees that Tomcat is no longer running and must be started manually.
     */
    private static void notifyRestartFailed(@NotNull Project project,
                                            @NotNull String configName,
                                            @Nullable String errorMessage) {
        // Short balloon — old text repeated the config name verbatim and ended
        // with a wordy "start the configuration manually to resume" sentence.
        // The user already knows which config they were on (it's in the run
        // toolbar), so the balloon just needs to flag the stop-without-restart
        // state. Console has the full stack.
        String content = errorMessage != null
                ? "Stopped without restart: " + errorMessage
                : "Stopped without restart. Start manually.";
        TomcatNotifier.error(project, "Restart failed", content);
    }

    /** Returns {@code " (N warning(s))"} when warnings &gt; 0, empty string otherwise. */
    @NotNull
    private static String warningSuffix(int warnings) {
        return warnings > 0 ? " (" + warnings + " warning(s))" : "";
    }

    /**
     * Logs a single aggregated warning when one or more deployment artifacts in
     * the config are WAR-packaged (not exploded). Hot class/web sync skips WAR
     * artifacts because their content lives inside a ZIP that the plugin cannot
     * safely mutate — so without this warning, users editing JSP/Java would see
     * "skipped: type is war" lines scroll past per-artifact and wonder why
     * their change didn't take effect.
     *
     * <p>One line per launch / per update action, fires only when at least
     * one WAR artifact is present, names every offender, and offers the two
     * concrete ways forward (build-tool repackage, or switch the artifact to
     * exploded in the Deployment tab). Public + static so the launch path
     * ({@code TomcatJavaParametersBuilder}) can call it too.
     *
     * <p>Additionally pops a balloon with a one-click "Switch to exploded"
     * action for every WAR deployment whose sibling exploded directory exists
     * on disk: Maven's {@code maven-war-plugin} produces both
     * {@code target/<finalName>.war} and {@code target/<finalName>/} during
     * {@code mvn package} (Gradle's {@code war} task likewise), so almost every
     * accidentally-picked WAR deployment has a sibling that would work for hot
     * reload. The balloon does not fire when no fixable candidates exist, so
     * users who can't benefit from the fix don't see a misleading prompt.
     */
    public static void warnAboutWarDeploymentsIfPresent(@NotNull TomcatRunConfiguration configuration,
                                                        @NotNull TomcatDeploymentLogger logger) {
        java.util.List<String> warNames = new java.util.ArrayList<>();
        for (Deployment d : configuration.getDeployments()) {
            if (!d.isExploded()) warNames.add(d.getDisplayName());
        }
        if (!warNames.isEmpty()) {
            String plural = warNames.size() == 1 ? "artifact is" : "artifacts are";
            logger.logServerWarning(
                    warNames.size() + " WAR " + plural + " in this run config: "
                            + String.join(", ", warNames)
                            + ". Hot class/resource sync (Ctrl+F10) only applies to exploded deployments,"
                            + " so changes to these won't take effect until you rebuild the WAR with"
                            + " 'mvn package' / 'gradle war' — or change the artifact type to 'exploded'"
                            + " in the Deployment tab (the exploded directory lives at"
                            + " target/<finalName>/ for Maven, build/libs/exploded/ for Gradle).");
        }
        offerWarToExplodedFix(configuration, logger);
    }

    /**
     * Balloon-surface seam: the platform balloons the sync-gap notifications
     * fire through. Package-visible so tests can record fired balloons and pin
     * the wiring (call order, session gating, titles) without the platform.
     */
    interface UpdateNotifier {
        void infoWithAction(@NotNull String title, @NotNull String content,
                            @NotNull String actionLabel, @NotNull Runnable action);
        void warning(@NotNull String title, @NotNull String content);

        /**
         * Balloon with two alternative resolutions. Default keeps existing
         * implementors compiling; the production notifier overrides it with a
         * real two-button balloon.
         */
        default void infoWithActions(@NotNull String title, @NotNull String content,
                                     @NotNull String actionLabel, @NotNull Runnable action,
                                     @NotNull String secondActionLabel, @NotNull Runnable secondAction) {
            infoWithAction(title, content, actionLabel, action);
        }
    }

    private static void offerWarToExplodedFix(@NotNull TomcatRunConfiguration configuration,
                                              @NotNull TomcatDeploymentLogger logger) {
        Project project = configuration.getProject();
        if (project == null || project.isDisposed()) return;

        java.util.List<Deployment> deployments =
                configuration.getConfigData().getDeploymentConfig().getDeployments(project);
        java.util.List<WarToExplodedQuickFix.FixCandidate> candidates =
                WarToExplodedQuickFix.findFixableArtifacts(project, deployments);
        notifySyncGaps(deployments, candidates, logger,
                SessionNotificationGate.INSTANCE,
                project.getLocationHash() + "|" + configuration.getName(),
                new UpdateNotifier() {
                    @Override
                    public void infoWithAction(@NotNull String title, @NotNull String content,
                                               @NotNull String actionLabel, @NotNull Runnable action) {
                        TomcatNotifier.notifyWithAction(project, title, content,
                                com.intellij.notification.NotificationType.INFORMATION,
                                actionLabel, action);
                    }

                    @Override
                    public void warning(@NotNull String title, @NotNull String content) {
                        TomcatNotifier.warning(project, title, content);
                    }
                },
                () -> {
                    int applied = WarToExplodedQuickFix.applyAll(configuration, candidates);
                    if (applied > 0) {
                        logger.logServerInfo("Reclaimed " + applied
                                + " deployment(s) as module-owned — Ctrl+F10 will now pick up changes without rebuilding.");
                    }
                });
    }

    /**
     * The complete sync-gap notification sequence: unsyncable-external warning
     * (console + gated balloon) first, then the reclaim offer (console + gated
     * balloon with the fix action). All collaborators injected — package-visible
     * so tests pin the wiring itself, not just the pure selectors: removing the
     * unsyncable pass, either session gate, or a balloon fire fails the tests.
     */
    static void notifySyncGaps(@NotNull java.util.List<Deployment> deployments,
                               @NotNull java.util.List<WarToExplodedQuickFix.FixCandidate> candidates,
                               @NotNull TomcatDeploymentLogger logger,
                               @NotNull SessionNotificationGate gate,
                               @NotNull String scopeId,
                               @NotNull UpdateNotifier notifier,
                               @NotNull Runnable reclaimAction) {
        notifyUnsyncableExternalDeployments(deployments, candidates, logger, gate, scopeId, notifier);
        if (candidates.isEmpty()) return;

        // Log every candidate to the console BEFORE popping the balloon. The
        // run console is the authoritative diagnostic surface — balloons can be
        // dismissed, hidden, or arrive after the user has moved on. Naming each
        // artifact + its target module makes it possible for the user to act
        // even if the balloon never gets clicked. Deliberately NOT gated: the
        // console line repeats on every action; only the balloon is
        // once-per-session below.
        StringBuilder mapping = new StringBuilder();
        java.util.Set<String> candidateKey = new java.util.HashSet<>();
        for (WarToExplodedQuickFix.FixCandidate c : candidates) {
            if (mapping.length() > 0) mapping.append(", ");
            mapping.append(c.deployment().getDisplayName())
                   .append(" → module '")
                   .append(c.moduleName())
                   .append("'");
            candidateKey.add(c.deployment().getDisplayName()
                    + "|" + c.explodedDirectory() + "|" + c.moduleName());
        }
        logger.logServerWarning(
                "Hot-reload reclaim available for " + candidates.size()
                        + " deployment(s): " + mapping + ". Class sync skips these because they"
                        + " resolve as ExternalFileDeployment (no module link). Click 'Reclaim "
                        + (candidates.size() == 1 ? "Deployment" : "All")
                        + "' in the notification to fix in place — or delete + re-add the"
                        + " deployments in the Deployment tab.");

        // Balloon once per (run configuration, candidate set) per IDE session —
        // a changed set re-notifies; an unchanged one stays console-only.
        if (!gate.shouldNotify("reclaim|" + scopeId, candidateKey)) {
            return;
        }

        String title = candidates.size() == 1
                ? "Deployment can be reclaimed as module-owned"
                : candidates.size() + " deployments can be reclaimed as module-owned";
        String content = "Hot reload (Ctrl+F10) needs the deployment "
                + (candidates.size() == 1 ? "to point" : "to point")
                + " at the exploded webapp directory of a project module. "
                + "Reclaiming switches WAR-typed entries to their sibling "
                + "exploded directory and updates external-source entries to "
                + "the module they already live in.";
        String actionLabel = candidates.size() == 1 ? "Reclaim Deployment" : "Reclaim All";

        notifier.infoWithAction(title, content, actionLabel, reclaimAction);
    }

    /**
     * External-path deployments that no auto-fix applies to: class sync cannot
     * mirror into them (an {@link ExternalFileDeployment} has no module link)
     * and {@code findFixableArtifacts} produced no reclaim candidate for them.
     * Pure and package-visible for tests. Identity comparison on purpose —
     * candidates hold the same instances the deployment list yielded.
     */
    @NotNull
    static java.util.List<Deployment> findUnsyncableExternalDeployments(
            @NotNull java.util.List<Deployment> deployments,
            @NotNull java.util.List<WarToExplodedQuickFix.FixCandidate> candidates) {
        java.util.Set<Deployment> fixable =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WarToExplodedQuickFix.FixCandidate c : candidates) {
            fixable.add(c.deployment());
        }
        java.util.List<Deployment> out = new java.util.ArrayList<>();
        for (Deployment d : deployments) {
            if (d instanceof ExternalFileDeployment && !fixable.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Surfaces external-path deployments for which hot reload is silently OFF:
     * class/resource sync needs an owning module to pull output from, and a
     * plain external path has none — previously the only trace was low-key
     * per-artifact skip lines in the class-sync console output. Console line on
     * every action (authoritative surface); balloon once per (run
     * configuration, deployment set) per IDE session, same gating as the
     * reclaim balloon. No action button on purpose: nothing here is
     * auto-fixable (those cases get the reclaim balloon instead), and a button
     * that cannot deliver would be dishonest — the remedy is pointing the
     * deployment at a module's build output (or re-adding it in the Deployment
     * tab so it resolves module-owned).
     */
    private static void notifyUnsyncableExternalDeployments(
            @NotNull java.util.List<Deployment> deployments,
            @NotNull java.util.List<WarToExplodedQuickFix.FixCandidate> candidates,
            @NotNull TomcatDeploymentLogger logger,
            @NotNull SessionNotificationGate gate,
            @NotNull String scopeId,
            @NotNull UpdateNotifier notifier) {
        java.util.List<Deployment> unsyncable =
                findUnsyncableExternalDeployments(deployments, candidates);
        if (unsyncable.isEmpty()) return;

        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Set<String> candidateKey = new java.util.HashSet<>();
        for (Deployment d : unsyncable) {
            names.add(d.getDisplayName());
            candidateKey.add(d.getDisplayName() + "|" + d.getResolvedPath());
        }
        String joined = String.join(", ", names);
        logger.logServerWarning("Hot reload (class/resource sync) is OFF for "
                + unsyncable.size() + " deployment(s): " + joined
                + " — they point at a plain external path with no owning module,"
                + " so there is no module output to sync from. Point each at a module's"
                + " build output, or re-add it via the Deployment tab so it resolves"
                + " module-owned.");

        if (!gate.shouldNotify("external-no-sync|" + scopeId, candidateKey)) {
            return;
        }
        String title = unsyncable.size() == 1
                ? "Hot reload is off for an external-path deployment"
                : "Hot reload is off for " + unsyncable.size() + " external-path deployments";
        notifier.warning(title,
                joined + ": external paths have no owning module, so class/resource"
                + " sync cannot update them. Point the deployment at a module's build"
                + " output, or re-add it via the Deployment tab.");
    }

    /** Maps an {@link UpdateConfig} action constant to a user-visible display string; unrecognised actions echo back. */
    @NotNull
    public static String mapActionToDisplay(@NotNull String action) {
        return displayForAction(action, action);
    }

    /**
     * Source-of-truth for the action-id → display-string mapping. Two callers
     * differ only in the fallback they want for an unrecognised action — the
     * platform-facing {@code getDescription} returns a generic "Update application",
     * while the diagnostic / log-line {@code mapActionToDisplay} echoes the input
     * back so an unknown action ID stays inspectable rather than being silently
     * relabelled.
     */
    @NotNull
    private static String displayForAction(@NotNull String action, @NotNull String fallback) {
        return switch (action) {
            case UpdateConfig.UPDATE_RESOURCES -> ACTION_UPDATE_RESOURCES;
            case UpdateConfig.UPDATE_CLASSES_AND_RESOURCES -> ACTION_UPDATE_CLASSES_AND_RESOURCES;
            case UpdateConfig.REDEPLOY -> ACTION_REDEPLOY;
            case UpdateConfig.RESTART_SERVER -> ACTION_RESTART_SERVER;
            default -> fallback;
        };
    }
}
