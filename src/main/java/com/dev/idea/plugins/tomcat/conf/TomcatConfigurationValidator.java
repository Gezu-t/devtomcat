package com.dev.idea.plugins.tomcat.conf;

        import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
        import com.dev.idea.plugins.tomcat.model.PortConfig;
        import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
        import com.dev.idea.plugins.tomcat.model.ValidationResult;
        import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
        import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
        import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
        import com.dev.idea.plugins.tomcat.utils.PortValidator;
        import com.intellij.execution.configurations.RuntimeConfigurationException;
        import com.intellij.execution.configurations.RuntimeConfigurationWarning;
        import com.intellij.openapi.application.ReadAction;
        import com.intellij.openapi.diagnostic.Logger;
        import com.intellij.openapi.util.text.StringUtil;
        import com.intellij.packaging.artifacts.Artifact;
        import com.intellij.packaging.artifacts.ArtifactManager;
        import org.jetbrains.annotations.NotNull;

        import java.io.File;
        import java.util.*;

        public final class TomcatConfigurationValidator {

            private static final Logger LOG = Logger.getInstance(TomcatConfigurationValidator.class);

            private TomcatConfigurationValidator() {}

            /**
             * Validates a full run configuration (handles name defaulting + data validation).
             * Called by {@link TomcatRunConfiguration#checkConfiguration()}.
             */
            public static void validate(@NotNull TomcatRunConfiguration config) throws RuntimeConfigurationException {
                Objects.requireNonNull(config, "Configuration cannot be null");

                try {
                    LOG.debug("Validating configuration: " + config.getName());

                    validateConfigurationName(config);
                    TomcatConfigurationData data = config.getConfigData();
                    validate(data);
                    validateTomcatServerRegistration(data);
                    validateArtifactReferences(config);

                    LOG.debug("Configuration validation passed: " + config.getName());
                } catch (RuntimeConfigurationException e) {
                    LOG.debug("Validation failed for: " + config.getName() + " - " + e.getLocalizedMessage());
                    throw e;
                } catch (Exception e) {
                    LOG.error("Unexpected error during validation: " + config.getName(), e);
                    throw new RuntimeConfigurationException("Validation error: " + e.getLocalizedMessage(), e);
                }
            }

            /**
             * Validates configuration data without requiring a TomcatRunConfiguration.
             * Testable without IntelliJ Project.
             */
            public static void validate(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
                Objects.requireNonNull(data, "Configuration data cannot be null");
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
             * Enforces that the config's embedded {@link TomcatInfo} snapshot resolves
             * to a registered server. Called only from the
             * {@link #validate(TomcatRunConfiguration)} overload because it touches the
             * application-level {@link TomcatServerManagerState} service — the pure
             * {@link #validate(TomcatConfigurationData)} overload remains service-free
             * and is still callable from headless unit tests.
             *
             * <p>This is the hard gate that blocks toolbar Run when a config references
             * a Tomcat that isn't registered. Previously the path-exists heuristic let
             * an embedded snapshot launch even with no registration, which surprised
             * users who expected registration to be required.
             */
            private static void validateTomcatServerRegistration(@NotNull TomcatConfigurationData data)
                    throws RuntimeConfigurationException {
                TomcatInfo persisted = data.getTomcatInfo();
                if (persisted == null) return; // already caught by validateTomcatServer
                TomcatServerManagerState state;
                try {
                    state = TomcatServerManagerState.getInstance();
                } catch (Throwable t) {
                    // No Application service — headless test path. Pure data validator
                    // already succeeded; runtime strictness is applied inside
                    // TomcatJavaParametersBuilder.getCatalinaHome() as a second gate.
                    LOG.debug("Skipping registration check: service unavailable", t);
                    return;
                }
                TomcatInfo resolved = state.resolve(persisted);
                if (resolved != null) return;

                String name = persisted.getName();
                String path = persisted.getPath();
                String displayName = !name.isEmpty() ? name : (!path.isEmpty() ? path : "(unnamed)");
                throw new RuntimeConfigurationException(
                        "Tomcat server '" + displayName + "' is not registered."
                                + " Open the run configuration and select a server from Application Servers,"
                                + " or add one via Configure.");
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
            }

            private static void validateDeploymentArtifacts(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
                List<DeploymentArtifact> artifacts = data.getDeploymentConfig().getArtifacts();
                if (artifacts.isEmpty()) return;

                // Validate artifact paths exist
                for (DeploymentArtifact artifact : artifacts) {
                    if (artifact == null) continue;
                    String path = artifact.getPath();
                    if (StringUtil.isEmpty(path)) {
                        throw new RuntimeConfigurationWarning(
                                "Deployment artifact '" + artifact.getDisplayName() +
                                "' has no path configured. Remove it or reconfigure in the Deployment tab.");
                    }
                    File artifactFile = new File(path);
                    if (!artifactFile.exists()) {
                        String hint = DeploymentArtifact.TYPE_WAR.equals(artifact.getType())
                                ? " Build the project (Build → Build Artifacts) to generate the WAR file."
                                : " Build the project first — the output directory will be created by the 'Build Artifact' Before Launch task.";
                        throw new RuntimeConfigurationWarning(
                                "Artifact output not found: " + path + "." + hint +
                                " This warning will clear once the artifact is built.");
                    }
                }

                // Check for duplicate context paths
                if (artifacts.size() < 2) return;
                Set<String> seen = new HashSet<>();
                for (DeploymentArtifact artifact : artifacts) {
                    if (artifact == null) continue;
                    String ctx = artifact.getContextPath();
                    if (ctx == null || ctx.isEmpty()) ctx = "/";
                    if (!seen.add(ctx)) {
                        throw new RuntimeConfigurationWarning(
                                "Duplicate context path '" + ctx + "' — multiple artifacts " +
                                "deployed to the same path will conflict. Change the context path " +
                                "of '" + artifact.getDisplayName() + "' in the Deployment tab.");
                    }
                }

                Map<String, DeploymentArtifact> seenByPath = new HashMap<>();
                Set<String> seenBaseNames = new HashSet<>();
                for (DeploymentArtifact artifact : artifacts) {
                    if (artifact == null) continue;

                    String baseName = ContextPathUtils.extractBaseModuleName(artifact.getName());
                    if (!baseName.isEmpty() && !seenBaseNames.add(baseName)) {
                        throw new RuntimeConfigurationWarning(
                                "Duplicate deployment for module '" + baseName + "' — the same application " +
                                "appears more than once in the Deployment tab. Remove the extra WAR/exploded " +
                                "variant to avoid Tomcat redeploy loops and JSP scratchDir errors.");
                    }

                    String normalizedPath = normalizeArtifactPath(artifact.getPath());
                    if (normalizedPath == null) continue;

                    DeploymentArtifact existing = seenByPath.putIfAbsent(normalizedPath, artifact);
                    if (existing != null) {
                        throw new RuntimeConfigurationWarning(
                                "Multiple deployments point to the same artifact output: " + normalizedPath +
                                ". Remove either '" + existing.getDisplayName() + "' or '" +
                                artifact.getDisplayName() + "' to avoid duplicate docBase deployment.");
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
         * Warns when non-external deployment artifacts cannot be resolved to any
         * IntelliJ artifact. This is a safety net: the {@link ArtifactReferenceRefresher}
         * runs first and fixes what it can, but if an artifact was deleted (not just renamed)
         * or renamed beyond recognition, this validation catches it.
         *
         * <p>Only runs when ArtifactManager is available (Ultimate and some CE configurations).
         * Silently skips on environments where the packaging module is not loaded.
         */
        private static void validateArtifactReferences(@NotNull TomcatRunConfiguration config)
                throws RuntimeConfigurationException {
            List<DeploymentArtifact> artifacts = config.getConfigData().getDeploymentConfig().getArtifacts();
            if (artifacts.isEmpty()) return;

            // ArtifactManager.getInstance() and getArtifacts() access the project
            // model and require a read action. Extract artifact names under the lock,
            // then validate outside.
            Set<String> platformArtifactNames;
            try {
                platformArtifactNames = ReadAction.compute(() -> {
                    ArtifactManager artifactManager =
                            ArtifactManager.getInstance(config.getProject());
                    Artifact[] platformArtifacts = artifactManager.getArtifacts();
                    Set<String> names = new HashSet<>(platformArtifacts.length);
                    for (Artifact pa : platformArtifacts) {
                        names.add(pa.getName());
                    }
                    return names;
                });
            } catch (NoClassDefFoundError | Exception e) {
                // ArtifactManager not available — skip this validation
                return;
            }

            if (platformArtifactNames.isEmpty()) return;

            for (DeploymentArtifact artifact : artifacts) {
                if (artifact == null) continue;
                // Skip EXTERNAL artifacts (user-picked files/directories) — they are
                // by definition not IntelliJ-managed, so flagging them as "not in
                // platform artifacts" would be a false positive. Source is the
                // authoritative marker; the legacy TYPE_EXTERNAL string check has
                // been retired along with the overloaded type field.
                if (artifact.getSource() == DeploymentArtifact.Source.EXTERNAL) continue;

                String name = artifact.getName();
                if (name.isEmpty()) continue;

                if (!platformArtifactNames.contains(name)) {
                    // The refresher already ran and couldn't resolve this — it's truly orphaned
                    throw new RuntimeConfigurationWarning(
                            "Deployment artifact '" + artifact.getDisplayName() +
                            "' does not match any IntelliJ artifact. It may have been renamed or " +
                            "removed. Reconfigure it in the Deployment tab, or remove and re-add it.");
                }
            }
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
