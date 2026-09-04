package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.utils.LaunchPathMapper;
import com.dev.idea.plugins.tomcat.utils.WslPathDetector;
import com.dev.idea.plugins.tomcat.utils.WslPathTranslator;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.wsl.WSLCommandLineOptions;
import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.execution.wsl.WSLUtil;
import com.intellij.execution.wsl.WslDistributionManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.util.PathsList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Experimental WSL launch mode: the registered Tomcat (and the JDK) live inside
 * a WSL2 distribution, reached from Windows through the {@code \\wsl$\<distro>}
 * mount. DevTomcat keeps its launch model unchanged — {@code catalina.base} is
 * assembled on the host, {@code Bootstrap} is started directly — and changes
 * only three things: every path handed to the JVM or written for Catalina is
 * translated by a {@link WslPathTranslator}, the classpath is joined with
 * {@code :}, and the resulting command runs inside the distribution through the
 * platform's {@code wsl.exe} wrapping ({@link #patchCommandLine}).
 *
 * <p>Decided once per launch, before any side-effectful pre-launch step. The
 * platform lookup is confined to {@link #resolve(String)}; everything else is
 * pure so it can be verified without a Windows machine.
 *
 * <p>Deliberately left as-is in this iteration: the host-side port registry and
 * orphan-process reclaim cannot see sockets or JVMs inside the distribution
 * (WSL2 localhost forwarding usually bridges the ports); the debugger attaches
 * to {@code 127.0.0.1} through that same forwarding — unverified; class/web
 * sync operates on the host filesystem and is unaffected. Termination is
 * host-side only: Stop destroys the {@code wsl.exe} process on Windows, and
 * nothing signals the JVM running inside the distribution — a shutdown-port or
 * {@code wsl.exe --terminate} path is still owed.
 */
public final class WslLaunchMode {

    private static final Logger LOG = Logger.getInstance(WslLaunchMode.class);

    /** The IDE's launcher agent, injected by {@code DefaultJavaProgramRunner}. */
    static final String LAUNCHER_AGENT_JAR = "idea_rt.jar";
    /** Launcher main-class swap used when the agent form is unavailable. */
    static final String LAUNCHER_MAIN_CLASS = "com.intellij.rt.execution.application.AppMainV2";
    private static final String LAUNCHER_PORT_PROPERTY = "idea.launcher.port";
    private static final String LAUNCHER_PREVIEW_PROPERTY = "idea.launcher.use.21.preview";

    private final WSLDistribution distribution;
    private final WslPathTranslator mapper;
    /** Cross-distribution references seen while translating, for the run console. */
    private final List<String> crossDistroWarnings;

    private WslLaunchMode(@NotNull WSLDistribution distribution, @NotNull WslPathTranslator mapper,
                          @NotNull List<String> crossDistroWarnings) {
        this.distribution = distribution;
        this.mapper = mapper;
        this.crossDistroWarnings = crossDistroWarnings;
    }

    // ---------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------

    /**
     * Platform entry point: {@code null} for a host launch; a resolved mode for a
     * WSL-hosted Tomcat; {@link ExecutionException} when the home is a WSL path
     * whose distribution cannot be resolved (not Windows, not installed, name
     * mismatch) — the honest fallback guard.
     */
    @Nullable
    static WslLaunchMode resolve(@NotNull String tomcatHome) throws ExecutionException {
        if (!WslPathDetector.isWslPath(tomcatHome)) return null;
        return resolve(tomcatHome, installedDistributions(), WslLaunchMode::mntRootOf);
    }

    /** Pure core of {@link #resolve(String)} with the platform lookups injected. */
    @Nullable
    static WslLaunchMode resolve(@NotNull String tomcatHome,
                                 @NotNull List<WSLDistribution> installed,
                                 @NotNull Function<WSLDistribution, String> mntRootLookup)
            throws ExecutionException {
        if (!WslPathDetector.isWslPath(tomcatHome)) return null;
        String name = WslPathDetector.distroOf(tomcatHome);
        WSLDistribution distribution = name == null ? null : findDistribution(installed, name);
        if (distribution == null) {
            throw new ExecutionException(WslPathDetector.unsupportedMessage(tomcatHome));
        }
        String mntRoot = mntRootLookup.apply(distribution);
        // The sink feeds the run console as well as idea.log: a path that names
        // another distribution is translated as if it were ours, and the user is
        // the only one who can tell whether that is what they meant.
        List<String> warnings = new CopyOnWriteArrayList<>();
        WslPathTranslator translator = new WslPathTranslator(
                distribution.getMsId(),
                mntRoot == null || mntRoot.isEmpty() ? WslPathTranslator.DEFAULT_MNT_ROOT : mntRoot,
                w -> {
                    LOG.warn(w);
                    if (!warnings.contains(w)) warnings.add(w);
                });
        return new WslLaunchMode(distribution, translator, warnings);
    }

    /** Case-insensitive match on the Microsoft id, then the platform id. */
    @Nullable
    static WSLDistribution findDistribution(@NotNull List<WSLDistribution> installed, @NotNull String name) {
        for (WSLDistribution d : installed) {
            if (name.equalsIgnoreCase(d.getMsId())) return d;
        }
        for (WSLDistribution d : installed) {
            if (name.equalsIgnoreCase(d.getId())) return d;
        }
        return null;
    }

    private static List<WSLDistribution> installedDistributions() {
        if (!WSLUtil.isSystemCompatible()) return List.of();
        try {
            return WslDistributionManager.getInstance().getInstalledDistributions();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Could not enumerate WSL distributions", e);
            return List.of();
        }
    }

    private static String mntRootOf(@NotNull WSLDistribution distribution) {
        try {
            return distribution.getMntRoot();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Could not read the mount root of '" + distribution.getMsId()
                    + "'; assuming " + WslPathTranslator.DEFAULT_MNT_ROOT, e);
            return WslPathTranslator.DEFAULT_MNT_ROOT;
        }
    }

    // ---------------------------------------------------------------------
    // Guards
    // ---------------------------------------------------------------------

    /** The JDK must live in the launch distribution — a Windows java.exe cannot run there. */
    void requireWslSideJdk(@Nullable Sdk jdk) throws ExecutionException {
        if (jdk == null) return;
        String home = jdk.getHomePath();
        if (isWslSideJdkHome(home, distroName())) return;
        String owner = WslPathDetector.distroOf(home);
        throw new ExecutionException(owner != null && !owner.equalsIgnoreCase(distroName())
                ? foreignJdkMessage(home, owner, distroName())
                : jdkMessage(distroName()));
    }

    /**
     * A Linux-form home, or a WSL UNC naming the launch distribution itself, is
     * inside that distribution. A UNC naming a <em>different</em> distribution is
     * not: that tree is unreachable from the launch distro, and translating it
     * only strips the distro segment — re-rooting the home onto whatever happens
     * to occupy the same absolute path here, or onto nothing at all.
     */
    static boolean isWslSideJdkHome(@Nullable String home, @NotNull String distro) {
        if (home == null) return false;
        String h = home.trim();
        String owner = WslPathDetector.distroOf(h);
        if (owner != null) return owner.equalsIgnoreCase(distro);
        // A WSL UNC with no distro segment names no distribution at all.
        if (WslPathDetector.isWslPath(h)) return false;
        return h.startsWith("/");
    }

    @NotNull
    static String jdkMessage(@NotNull String distro) {
        return "In WSL mode the JDK must be installed inside the WSL distribution '" + distro
                + "'. Select a WSL-side JDK for this run configuration.";
    }

    @NotNull
    static String foreignJdkMessage(@NotNull String home, @NotNull String jdkDistro,
                                    @NotNull String launchDistro) {
        return "The selected JDK '" + home + "' lives in WSL distribution '" + jdkDistro
                + "', but Tomcat runs inside '" + launchDistro + "'. One distribution cannot"
                + " execute another's files, and the path would silently resolve against '"
                + launchDistro + "' instead. Select a JDK installed in '" + launchDistro + "'.";
    }

    /**
     * Refuses launch shapes the {@code wsl.exe} wrapping cannot honour: a custom
     * startup script (shell scripts are not wrapped in this iteration) and the
     * Coverage executor (agent and output file live on the host).
     */
    static void requireSupportedLaunchShape(boolean useDefaultStartup,
                                            @Nullable String startupScript,
                                            @NotNull String executorId) throws ExecutionException {
        if (!useDefaultStartup && startupScript != null && !startupScript.isBlank()) {
            throw new ExecutionException(customStartupMessage());
        }
        if (com.dev.idea.plugins.tomcat.TomcatConstants.COVERAGE_MODE.equals(executorId)) {
            throw new ExecutionException(coverageMessage());
        }
    }

    /**
     * The Coverage refusal restated for the one caller that must decide it before
     * touching a running process. Keyed on the registered home alone — no
     * distribution lookup — because {@code handleCrossExecutorConflict} stops the
     * current session, and a refusal raised after that leaves the user with
     * nothing running and a server to restart by hand.
     */
    static void requireCoverageCapableHost(@Nullable String tomcatHome) throws ExecutionException {
        if (WslPathDetector.isWslPath(tomcatHome)) {
            throw new ExecutionException(coverageMessage());
        }
    }

    @NotNull
    static String customStartupMessage() {
        return "Custom startup scripts are not supported in WSL mode; use the default startup.";
    }

    @NotNull
    static String coverageMessage() {
        return "Coverage is not supported in WSL mode (experimental): the coverage agent and its"
                + " output live on the Windows host. Use Run or Debug.";
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    @NotNull
    LaunchPathMapper mapper() {
        return mapper;
    }

    @NotNull
    String distroName() {
        return distribution.getMsId();
    }

    /** One honest line for the run console, logged once per launch. */
    @NotNull
    String consoleMessage(@NotNull String hostCatalinaBase) {
        return "WSL mode (experimental): running Tomcat inside WSL distribution '" + distroName()
                + "' — catalina.base on the host is visible to the distro as "
                + mapper.toTarget(hostCatalinaBase)
                + ". Debugging and port-conflict detection across the WSL2 network boundary"
                + " are not yet verified.";
    }

    // ---------------------------------------------------------------------
    // Command-line wrapping (the only platform-bound step)
    // ---------------------------------------------------------------------

    /**
     * Points the command at the distro-side {@code java}, then hands it to the
     * platform's {@code wsl.exe} wrapping with the translated working directory.
     * Kept as one small method so the platform call is isolated.
     */
    @NotNull
    GeneralCommandLine patchCommandLine(@NotNull GeneralCommandLine commandLine,
                                        @Nullable Project project,
                                        @NotNull String hostWorkingDir,
                                        @NotNull String jdkHome) throws ExecutionException {
        commandLine.setExePath(javaExecutable(jdkHome, mapper));
        String remoteWorkingDir = mapper.toTarget(hostWorkingDir);
        requireDistroSidePath(remoteWorkingDir, hostWorkingDir, distroName());
        WSLCommandLineOptions options = new WSLCommandLineOptions()
                .setRemoteWorkingDirectory(remoteWorkingDir);
        return distribution.patchCommandLine(commandLine, project, options);
    }

    /**
     * The platform asserts a {@code /}-rooted remote working directory and raises
     * {@link AssertionError} otherwise. That is an {@code Error}, so the launch's
     * own {@code catch (ExecutionException | RuntimeException)} would not run:
     * ports claimed for this configuration would leak and the user would get a
     * raw IDE internal error instead of a DevTomcat message. Anything the
     * translator could not map to a distro-side path — a non-drive UNC base, a
     * relative pinned base — is refused here, on the path that does release ports.
     */
    static void requireDistroSidePath(@NotNull String targetPath, @NotNull String hostPath,
                                      @NotNull String distro) throws ExecutionException {
        if (!targetPath.startsWith("/")) {
            throw new ExecutionException(workingDirectoryMessage(hostPath, distro));
        }
    }

    @NotNull
    static String workingDirectoryMessage(@NotNull String hostPath, @NotNull String distro) {
        return "WSL mode cannot map the working directory '" + hostPath + "' into distribution '"
                + distro + "': only local drive paths (C:\\...) and paths inside the distribution"
                + " can be reached from it. Pin CATALINA_BASE to a local drive path.";
    }

    /** Drains cross-distribution warnings collected while translating paths. */
    @NotNull
    List<String> drainCrossDistroWarnings() {
        if (crossDistroWarnings.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(crossDistroWarnings);
        crossDistroWarnings.clear();
        return out;
    }

    /**
     * Carries the platform's sudo listener onto the handler; a no-op unless the
     * command line was built with sudo enabled, which DevTomcat never does. Kept
     * so the wrapping stays whatever the platform defines it to be — it does not
     * propagate termination into the distribution (see the class javadoc).
     */
    @NotNull
    <T extends ProcessHandler> T patchProcessHandler(@NotNull GeneralCommandLine commandLine,
                                                     @NotNull T handler) {
        return distribution.patchProcessHandler(commandLine, handler);
    }

    /** {@code <translated JDK home>/bin/java} — never the host's {@code java.exe}. */
    @NotNull
    static String javaExecutable(@NotNull String jdkHome, @NotNull LaunchPathMapper mapper) {
        String home = mapper.toTarget(jdkHome);
        while (home.length() > 1 && home.endsWith("/")) {
            home = home.substring(0, home.length() - 1);
        }
        return home + "/bin/java";
    }

    // ---------------------------------------------------------------------
    // Platform launcher proxy
    // ---------------------------------------------------------------------

    /**
     * Removes the IDE launcher hook that {@code DefaultJavaProgramRunner} adds
     * after our parameters are built: a {@code -javaagent:<IDE>\lib\idea_rt.jar}
     * (or, without agent support, an {@code AppMainV2} main-class swap). Both
     * reference host-only paths and dial back to the IDE over a loopback socket
     * the distribution cannot reach; the JVM would fail to start on the agent
     * path. Dropping the hook only loses the console's Exit/Dump-threads buttons.
     */
    static void neutralizeHostLauncherProxy(@NotNull JavaParameters params) {
        ParametersList vm = params.getVMParametersList();
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String p : vm.getList()) {
            if (isLauncherAgent(p)) {
                changed = true;
                continue;
            }
            kept.add(p);
        }
        if (changed) {
            vm.clearAll();
            vm.addAll(kept);
        }

        if (LAUNCHER_MAIN_CLASS.equals(params.getMainClass())) {
            ParametersList program = params.getProgramParametersList();
            List<String> args = new ArrayList<>(program.getList());
            if (!args.isEmpty()) {
                params.setMainClass(args.remove(0));
                program.clearAll();
                program.addAll(args);
            }
            PathsList classPath = params.getClassPath();
            for (String entry : new ArrayList<>(classPath.getPathList())) {
                if (entry.endsWith(LAUNCHER_AGENT_JAR)) classPath.remove(entry);
            }
            removeProperty(vm, LAUNCHER_PORT_PROPERTY);
            removeProperty(vm, LAUNCHER_PREVIEW_PROPERTY);
        }
    }

    private static boolean isLauncherAgent(@NotNull String vmParam) {
        if (!vmParam.startsWith("-javaagent:")) return false;
        int eq = vmParam.indexOf('=');
        String jar = eq < 0 ? vmParam : vmParam.substring(0, eq);
        return jar.endsWith(LAUNCHER_AGENT_JAR);
    }

    private static void removeProperty(@NotNull ParametersList vm, @NotNull String name) {
        String prefix = "-D" + name + "=";
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String p : vm.getList()) {
            if (p.startsWith(prefix) || p.equals("-D" + name)) {
                changed = true;
                continue;
            }
            kept.add(p);
        }
        if (changed) {
            vm.clearAll();
            vm.addAll(kept);
        }
    }
}
