package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.PortValidator;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public final class TomcatConfigurationValidator {

    private static final Logger LOG = Logger.getInstance(TomcatConfigurationValidator.class);

    private TomcatConfigurationValidator() {
    }

    /**
     * Validates a full run configuration (handles name defaulting + data validation).
     * Called by {@link TomcatRunConfiguration#checkConfiguration()}.
     */
    public static void validate(@NotNull TomcatRunConfiguration config) throws RuntimeConfigurationException {

        try {
            LOG.debug("Validating configuration: " + config.getName());

            validateConfigurationName(config);
            TomcatConfigurationData data = config.getConfigData();
            // Reconcile BEFORE path-level validation so an imported config whose
            // persisted snapshot has a stale path but resolves to a registered
            // server by ID/path/name is accepted — matching the UI and runtime,
            // which both upgrade to the resolved instance first. Without this
            // ordering, a VCS-imported config with a unique registered name but
            // a dead persisted path would fail toolbar Run even though runtime
            // launch would have reconciled and succeeded.
            reconcileTomcatServerRegistration(data);
            validate(data);
            validateArtifactReferences(config);

            LOG.debug("Configuration validation passed: " + config.getName());
        } catch (RuntimeConfigurationException e) {
            LOG.debug("Validation failed for: " + config.getName() + " - " + e.getLocalizedMessage());
            throw e;
        } catch (Exception e) {
            LOG.error("Unexpected error during validation: " + config.getName(), e);
            // Include the configuration name so a user with multiple
            // Tomcat configurations can identify which one triggered
            // the unexpected exception. Bare "Validation error: ..."
            // gives no signal when the IDE has 3+ Tomcat run configs.
            String name = config.getName();
            String prefix = (name != null && !name.isBlank())
                    ? "Validation error in '" + name + "': "
                    : "Validation error: ";
            throw new RuntimeConfigurationException(prefix + e.getLocalizedMessage(), e);
        }
    }

    /**
     * Validates configuration data without requiring a TomcatRunConfiguration.
     * Testable without IntelliJ Project.
     */
    public static void validate(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
        validateTomcatServer(data);
        validatePortConfiguration(data);
        validateContextPath(data);
        validateDeploymentArtifacts(data);
    }

    private static void validateConfigurationName(@NotNull TomcatRunConfiguration config) {
        if (StringUtil.isEmpty(config.getName())) {
            config.setName("Tomcat");
            LOG.debug("Configuration name was empty; defaulted to 'Tomcat'");
        }
    }

    /**
     * Reconciles the embedded {@link TomcatInfo} snapshot against the
     * registered list via {@link TomcatServerManagerState#resolve} and
     * upgrades {@code data.tomcatInfo} to the resolved canonical instance
     * when they differ. Throws if no registered server matches.
     *
     * <p>Runs before {@link #validate(TomcatConfigurationData)} so that
     * downstream path validation checks the <b>resolved</b> path, not the
     * persisted snapshot's. This mirrors the runtime path in
     * {@link com.dev.idea.plugins.tomcat.runner.TomcatJavaParametersBuilder}
     * and the UI in
     * {@link com.dev.idea.plugins.tomcat.ui.server.sections.ApplicationServerSection}
     * — an imported config whose persisted path is stale but whose name or
     * ID still matches a registered server should launch, since both
     * other gates already reconcile it.
     *
     * <p>Called only from the {@link #validate(TomcatRunConfiguration)}
     * overload because it touches the application-level
     * {@link TomcatServerManagerState} service. The pure
     * {@link #validate(TomcatConfigurationData)} overload stays
     * service-free for headless unit tests.
     */
    private static void reconcileTomcatServerRegistration(@NotNull TomcatConfigurationData data)
            throws RuntimeConfigurationException {
        TomcatInfo persisted = data.getTomcatInfo();
        if (persisted == null) return; // already caught by validateTomcatServer
        TomcatServerManagerState state;
        try {
            state = TomcatServerManagerState.getInstance();
        } catch (Throwable t) {
            // No Application service — headless test path. Pure data validator
            // still runs; runtime strictness is applied inside
            // TomcatJavaParametersBuilder.getCatalinaHome() as a second gate.
            LOG.debug("Skipping registration check: service unavailable", t);
            return;
        }
        TomcatInfo resolved = state.resolveOrAutoRegister(persisted);
        if (resolved == null) {
            String name = persisted.getName();
            String path = persisted.getPath();
            String displayName = !name.isEmpty() ? name : (!path.isEmpty() ? path : "(unnamed)");
            throw new RuntimeConfigurationException(
                    "Tomcat server '" + displayName + "' is not registered."
                            + " Open the run configuration and select a server from Application Servers,"
                            + " or add one via Configure.");
        }

        if (resolved != persisted) {
            LOG.info("Validator reconciled drifted persisted reference"
                    + " (id=" + persisted.getId() + ", path=" + persisted.getPath() + ")"
                    + " to registered server (id=" + resolved.getId()
                    + ", path=" + resolved.getPath() + ")");
            data.setTomcatInfo(resolved);
        }
    }

    private static void validateTomcatServer(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
        TomcatInfo tomcatInfo = data.getTomcatInfo();
        if (tomcatInfo == null) {
            throw new RuntimeConfigurationException("No Tomcat server selected. Please configure a Tomcat instance.");
        }
        if (StringUtil.isEmpty(tomcatInfo.getName())) {
            throw new RuntimeConfigurationException("Tomcat server name is empty");
        }
        if (StringUtil.isEmpty(tomcatInfo.getPath())) {
            throw new RuntimeConfigurationException("Tomcat server path is not configured for: " + tomcatInfo.getName());
        }
        // A WSL UNC home ('\\wsl$\...' / '\\wsl.localhost\...') passes the
        // File.isDirectory() check below on Windows (the 9P mount is reachable),
        // so intercept it first with an honest message — DevTomcat runs Tomcat
        // as a local host process and cannot yet launch one inside WSL.
        if (com.dev.idea.plugins.tomcat.utils.WslPathDetector.isWslPath(tomcatInfo.getPath())) {
            throw new RuntimeConfigurationException(
                    com.dev.idea.plugins.tomcat.utils.WslPathDetector.unsupportedMessage(tomcatInfo.getPath()));
        }
        File tomcatDir = new File(tomcatInfo.getPath());
        if (!tomcatDir.isDirectory()) {
            // Matches the UI validator (ApplicationServerSection) and the runtime
            // (TomcatJavaParametersBuilder.getCatalinaHome). Previously only logged,
            // so the toolbar accepted the config and we failed loudly at execution
            // time instead of up front in the same warning popup the dialog shows.
            throw new RuntimeConfigurationException(
                    "Tomcat home directory does not exist: " + tomcatInfo.getPath()
                            + ". Update the path in Application Servers settings.");
        }
        if (StringUtil.isEmpty(tomcatInfo.getVersion())) {
            LOG.warn(String.format("Tomcat server version not set for: %s", tomcatInfo.getName()));
        }
    }

    private static void validatePortConfiguration(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
        PortConfig ports = data.getPortConfig();
        if (ports == null) {
            throw new RuntimeConfigurationException("Port configuration is missing");
        }

        PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                .httpPort(ports.getHttp())
                .shutdownPort(ports.getShutdown())
                .httpsPort(ports.getHttps())
                .httpsEnabled(ports.isHttpsEnabled())
                .jmxPort(ports.getJmx())
                .jmxEnabled(ports.isJmxEnabled())
                .build();

        ValidationResult result = PortValidator.validate(portConfig);
        if (result.hasErrors()) {
            throw new RuntimeConfigurationException(result.getErrorMessage());
        }
        if (result.hasWarnings()) {
            LOG.debug("Port validation warnings: " + result.getWarningMessage());
        }

        // Port-drift warning
        int preferredHttp = ports.getPreferredHttp();
        if (preferredHttp > 0 && preferredHttp != ports.getHttp()) {
            throw new RuntimeConfigurationWarning(
                    "HTTP port drifted from " + preferredHttp + " to " + ports.getHttp()
                            + " (auto-resolved by DevTomcat after a prior conflict). "
                            + "External config files that hardcode " + preferredHttp
                            + " (e.g. backendUrl in application.properties) may now point at "
                            + "the wrong port. Reset to " + preferredHttp + " in the Server tab "
                            + "if the original conflict is gone, or update the dependent configs.");
        }
        int preferredShutdown = ports.getPreferredShutdown();
        if (preferredShutdown > 0 && preferredShutdown != ports.getShutdown()) {
            throw new RuntimeConfigurationWarning(
                    "Shutdown port drifted from " + preferredShutdown + " to "
                            + ports.getShutdown() + " (auto-resolved). Reset in the Server tab "
                            + "if the original conflict is gone.");
        }
    }

    private static void validateDeploymentArtifacts(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
        // Detached typed view: this overload is project-free by contract, so
        // pointers stay name-only — path/name/context checks need no resolution.
        List<Deployment> deployments = data.getDeploymentConfig().getDeployments();
        if (deployments.isEmpty()) {
            // Blocking error. Previously this was a non-blocking warning, which
            // mirrored the post-launch warning in LocalDeploymentStrategy but
            // still let the user click Run and wait for Tomcat to come up with
            // nothing deployed — the exact misconfiguration the warning was
            // supposed to catch. A launch with zero deployments is never the
            // happy path: Tomcat starts but the user's app isn't there.
            // Failing the validation gate forces the user to add a deployment
            // first; the run-config editor shows a red stripe at edit time and
            // the Run button is disabled until at least one deployment exists.
            throw new RuntimeConfigurationException(
                    "No deployments configured. Add a deployment in the Deployment tab "
                            + "before launching — otherwise Tomcat starts with nothing to serve.");
        }

        // Validate artifact paths exist
        for (Deployment deployment : deployments) {
            java.nio.file.Path resolved = deployment.getResolvedPath();
            String path = resolved == null ? "" : resolved.toString();
            if (StringUtil.isEmpty(path)) {
                throw new RuntimeConfigurationWarning(
                        "Deployment artifact '" + deployment.getDisplayName() +
                                "' has no path configured. Remove it or reconfigure in the Deployment tab.");
            }
            File artifactFile = new File(path);
            if (!artifactFile.exists()) {
                // An exploded deployment's directory is produced by a before-launch
                // step — DevTomcat assembles it from the module on Community, the
                // platform's Build Artifacts task builds it on Ultimate — so a
                // missing directory before the first build is expected, not a
                // problem to flag. (The Verify before-launch task is the real gate,
                // and it assembles first.) Only a WAR genuinely needs a manual
                // package step, so keep the warning for that.
                if (!deployment.isExploded()) {
                    throw new RuntimeConfigurationWarning(
                            "WAR not found: " + path + ". Build the project "
                                    + "(e.g. mvn package / gradle war) to generate it. "
                                    + "This warning clears once the WAR is built.");
                }
            }
        }

        // Single pass over artifacts validates each context path AND tracks
        // for collisions. Keyed by the resolved Tomcat context name (which
        // matches LocalDeploymentStrategy's on-disk file name and Tomcat's
        // actual deployment behaviour). Raw-string comparison would miss
        // equivalent paths that normalise to the same target:
        //   "/foo" + "/foo/"           — trailing slash variant
        //   ""     + "/"               — empty vs default both → ROOT
        //   null   + "/"               — null vs explicit default both → ROOT
        // Tomcat resolves all of these to the same context.xml file on disk
        // and serves only the last write. Catching the collision in the
        // validator surfaces it in the run-config editor with a yellow
        // border so the user fixes it before the launch silently drops
        // half their artifacts.
        //
        // The traversal-check branch runs for any artifact count (even one),
        // because LocalDeploymentStrategy would throw at deploy time and we
        // want the editor's Apply button to refuse it earlier with a clear
        // attribution to the offending artifact.
        // Duplicate detection only matters when there are 2+ artifacts to compare.
        // The traversal/path-validity branch above runs unconditionally so even a
        // single-artifact config rejects '..' / '\' / ':' at Apply time.
        final boolean canHaveDuplicates = deployments.size() >= 2;
        Map<String, Deployment> seenByContextName = new HashMap<>();
        for (Deployment deployment : deployments) {
            String resolvedName;
            try {
                resolvedName = ContextPathUtils.resolveContextName(deployment.getContextPath());
            } catch (IllegalArgumentException e) {
                // Invalid characters in the context path (.., \, :) — hard
                // error, surface as RuntimeConfigurationException so Apply
                // refuses the bad path early instead of letting it through
                // to a less informative ExecutionException at deploy time.
                throw new RuntimeConfigurationException(
                        "Invalid context path on artifact '" + deployment.getDisplayName()
                                + "': " + e.getMessage());
            }
            if (!canHaveDuplicates) continue;
            Deployment previous = seenByContextName.putIfAbsent(resolvedName, deployment);
            if (previous != null) {
                String displayPath = resolvedName.equals(TomcatConstants.ROOT_CONTEXT_NAME)
                        ? "/ (ROOT)"
                        : "/" + resolvedName;
                throw new RuntimeConfigurationWarning(
                        "Duplicate context path " + displayPath + ": artifacts '"
                                + previous.getDisplayName() + "' and '"
                                + deployment.getDisplayName() + "' both deploy here. "
                                + "Tomcat will only serve one — change the context path "
                                + "of one in the Deployment tab.");
            }
        }

        Map<String, Deployment> seenByPath = new HashMap<>();
        Set<String> seenBaseNames = new HashSet<>();
        for (Deployment deployment : deployments) {
            String baseName = ContextPathUtils.extractBaseModuleName(deployment.getDisplayName());
            if (!baseName.isEmpty() && !seenBaseNames.add(baseName)) {
                throw new RuntimeConfigurationWarning(
                        "Duplicate deployment for module '" + baseName + "': the same application " +
                                "appears more than once in the Deployment tab. Remove the extra WAR/exploded " +
                                "variant to avoid Tomcat redeploy loops and JSP scratchDir errors.");
            }

            java.nio.file.Path resolved = deployment.getResolvedPath();
            String normalizedPath = normalizeArtifactPath(resolved == null ? null : resolved.toString());
            if (normalizedPath == null) continue;

            Deployment existing = seenByPath.putIfAbsent(normalizedPath, deployment);
            if (existing != null) {
                throw new RuntimeConfigurationWarning(
                        "Multiple deployments point to the same artifact output: " + normalizedPath +
                                ". Remove either '" + existing.getDisplayName() + "' or '" +
                                deployment.getDisplayName() + "' to avoid duplicate docBase deployment.");
            }
        }
    }

    private static void validateContextPath(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
        String contextPath = data.getContextPath();
        if (StringUtil.isEmpty(contextPath)) {
            data.setContextPath("/");
            return;
        }
        if (!contextPath.startsWith("/")) {
            throw new RuntimeConfigurationException("Context path must start with '/': " + contextPath);
        }
        if (contextPath.contains(" ")) {
            throw new RuntimeConfigurationException("Context path cannot contain spaces: " + contextPath);
        }
        if (contextPath.contains("\\")) {
            throw new RuntimeConfigurationException("Context path cannot contain backslashes: " + contextPath);
        }
    }

    private static String normalizeArtifactPath(String path) {
        if (StringUtil.isEmpty(path)) {
            return null;
        }
        return new File(path).getAbsoluteFile().toPath().normalize().toString();
    }

    /**
     * Warns when an {@link ArtifactBackedDeployment}'s {@code ArtifactPointer}
     * no longer resolves to a live IntelliJ artifact (the artifact was
     * removed or renamed beyond what the pointer's rename-tracking can
     * recover). Module-backed and external deployments don't participate
     * in this check.
     */
    private static void validateArtifactReferences(@NotNull TomcatRunConfiguration config)
            throws RuntimeConfigurationException {
        List<Deployment> deployments;
        try {
            // ArtifactPointer.getArtifact() resolves through the platform
            // ArtifactManager and needs a read action; collect orphan names
            // inside the action, then throw outside.
            deployments = TomcatReadActions.compute(config::getDeployments);
        } catch (NoClassDefFoundError | Exception e) {
            // Platform model not available — skip this validation.
            return;
        }
        if (deployments.isEmpty()) return;

        List<String> orphans = TomcatReadActions.compute(() -> {
            List<String> names = new ArrayList<>();
            for (Deployment d : deployments) {
                if (d instanceof ArtifactBackedDeployment a
                        && a.getArtifactPointer().getArtifact() == null) {
                    names.add(a.getDisplayName());
                }
            }
            return names;
        });

        if (orphans.isEmpty()) return;
        throw new RuntimeConfigurationWarning(
                "Deployment artifact '" + orphans.get(0) +
                        "' does not match any IntelliJ artifact. It may have been renamed or " +
                        "removed. Reconfigure it in the Deployment tab, or remove and re-add it.");
    }

    public static String getValidationError(@NotNull TomcatRunConfiguration config) {
        try {
            validate(config);
            return null;
        } catch (RuntimeConfigurationException e) {
            return e.getLocalizedMessage();
        }
    }
}
