package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentResolver;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.setting.ProjectTomcatProfileScanner;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.update.DeploymentModuleResolver;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.execution.Location;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;
import com.intellij.openapi.diagnostic.Logger;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/**
 * Produces DevTomcat run configurations for web-oriented module contexts.
 *
 * <p>Produced configurations use the modern deployment model — a
 * {@link ModuleBackedDeployment} (exploded) pointing at the
 * module's WAR <em>build output</em> — exactly the shape the Setup action
 * creates and the validator accepts. Production therefore requires
 * {@link ProjectTomcatProfileScanner#scanModule} to detect a WAR-packaging
 * module: a discovered <em>source</em> web root alone is not deployable (the
 * class-sync pipeline writes into {@code WEB-INF/classes} under the deployment
 * path, so a source directory must never be one).
 *
 * <p>{@link #isConfigurationFromContext} recognizes an existing configuration
 * when one of its deployments resolves to the context module (typed model +
 * {@link DeploymentModuleResolver}), or via the legacy {@code docBase} field
 * for configs saved by older plugin versions. Without that recognition the
 * platform mints a temporary configuration on every run-from-context — showing
 * the user duplicate entries in the run-configuration widget for an app their
 * existing configuration already deploys.
 */
public class TomcatRunConfigurationProducer extends LazyRunConfigurationProducer<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationProducer.class);


    private static final String DEVTOMCAT_REGISTRY_KEY = "devTomcat.disableRunConfigurationProducer";
    private static final String CONFIGURATION_PREFIX = "DevTomcat: ";

    /** Web view-template / JSP file extensions (lowercase) treated as a web-context hint for run-config ranking. */
    private static final Set<String> WEB_CONTEXT_FILE_EXTENSIONS = Set.of(
            "jsp", "jspx", "jspf", "tag", "tagx", "xhtml", "html", "htm", "ftl", "ftlh", "vm", "gsp", "mustache"
    );

    /** Exact web descriptor file names (lowercase) treated as a web-context hint. */
    private static final Set<String> WEB_DESCRIPTOR_FILE_NAMES = Set.of("web.xml", "web-fragment.xml");

    @NotNull
    @Override
    public ConfigurationFactory getConfigurationFactory() {
        TomcatRunConfigurationType configurationType = ConfigurationTypeUtil.findConfigurationType(TomcatRunConfigurationType.class);
        ConfigurationFactory[] factories = configurationType.getConfigurationFactories();
        for (ConfigurationFactory factory : factories) {
            if (TomcatConstants.MODE_LOCAL.equals(factory.getName())) {
                return factory;
            }
        }
        return factories[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull TomcatRunConfiguration configuration,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        if (isProducerDisabled()) {
            return false;
        }

        Module module = context.getModule();
        if (module == null) {
            return false;
        }

        // Skip contexts that should stay as standard Application run configurations.
        PsiClass psiClass = ApplicationConfigurationType.getMainClass(context.getPsiLocation());
        if (psiClass != null) {
            return false;
        }

        List<VirtualFile> webRoots = discoverWebRootsForContext(context.getLocation());
        if (webRoots.isEmpty()) {
            return false;
        }

        // A deployable configuration needs a build-output path. A discovered
        // source web root is only the "this is a web context" signal — it must
        // never become the deployment path itself (the class-sync pipeline
        // writes into WEB-INF/classes under the deployment path).
        ProjectTomcatProfileScanner.DetectedWebappModule detected =
                ProjectTomcatProfileScanner.scanModule(module);
        if (detected == null) {
            LOG.debug("DevTomcat: No WAR build output detected for module '"
                    + module.getName() + "'; not producing a configuration");
            return false;
        }

        if (!configureTomcatServer(configuration)) {
            return false;
        }

        configureRunConfiguration(configuration, detected);

        LOG.debug("DevTomcat: Run configuration created for module: " + module.getName());
        return true;
    }

    @Override
    public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
        if (self.getConfiguration() instanceof TomcatRunConfiguration) {
            return isWebModuleContext(self.getSourceElement());
        }
        return false;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull TomcatRunConfiguration configuration,
                                              @NotNull ConfigurationContext context) {
        if (isProducerDisabled()) {
            return false;
        }

        // Same gate as setupConfigurationFromContext: a context with no
        // discoverable web root (incl. test sources) could not have produced a
        // configuration, so no existing one should be claimed for it either.
        List<VirtualFile> webRoots = discoverWebRootsForContext(context.getLocation());
        if (webRoots.isEmpty()) {
            return false;
        }

        // Legacy match first (cheap string compare): configs saved by older
        // plugin versions stored the discovered web root in docBase.
        String docBase = configuration.getDocBase();
        if (docBase != null && !docBase.isEmpty()
                && webRoots.stream().anyMatch(r -> r.getPath().equals(docBase))) {
            return true;
        }

        // Modern match: one of the configuration's deployments resolves to the
        // context module — the configuration already covers this webapp, so the
        // platform must reuse it instead of minting a temporary duplicate.
        // Runs per existing config on the (background, cancelable) action-update
        // path; resolveAll is index-free and PCE-safe. External file deployments
        // resolve to no modules and can never match.
        Module contextModule = context.getModule();
        if (contextModule == null) {
            return false;
        }
        Project project = contextModule.getProject();
        Boolean covers = TomcatReadActions.compute(() -> {
            for (Deployment deployment : configuration.getDeployments()) {
                java.util.Set<Module> modules = DeploymentModuleResolver.resolveAll(deployment, project);
                if (modules.contains(contextModule)) {
                    return true;
                }
                // A deployment the typed model can't resolve — a dangling
                // artifact pointer persisted before AUTO_DETECTED provenance
                // existed, or an in-project file added via the external picker —
                // still identifies its webapp by path: fall back to content-root
                // ownership. Out-of-project paths resolve to no module and
                // correctly never match.
                if (modules.isEmpty()) {
                    java.nio.file.Path path = deployment.getResolvedPath();
                    if (path != null && contextModule.equals(
                            DeploymentResolver.resolveOwningModule(
                                    project, deployment.getDisplayName(), path))) {
                        return true;
                    }
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(covers);
    }

    /**
     * Registry-backed feature toggle, safe against missing keys.
     */
    private boolean isProducerDisabled() {
        try {
            // Registry key is optional; default to enabled if missing.
            return Registry.is(DEVTOMCAT_REGISTRY_KEY);
        } catch (MissingResourceException ignore) {
            return false;
        }
    }

    private List<VirtualFile> discoverWebRootsForContext(@Nullable Location<?> location) {
        if (location == null) {
            return ContainerUtil.emptyList();
        }

        boolean isTestFile = TomcatModuleUtils.isTestSource(location);
        if (isTestFile) {
            LOG.debug("DevTomcat: Skipping test file location for web root discovery");
            return ContainerUtil.emptyList();
        }

        Module module = location.getModule();
        if (module == null) {
            return ContainerUtil.emptyList();
        }

        List<VirtualFile> webRoots = new ArrayList<>();

        // Structure-agnostic discovery, in the same authority order the deployment
        // pipeline (WebResourcesSync) uses so the auto-created docBase matches where
        // resources are mirrored: the user's explicitly configured Web Facet roots
        // first, then conventional roots, then any directory that holds WEB-INF
        // whatever its name — so a custom layout is discovered without a fixed list.
        webRoots.addAll(TomcatModuleUtils.findWebFacetRoots(module));

        if (webRoots.isEmpty()) {
            webRoots.addAll(TomcatModuleUtils.findWebRoots(module));
        }

        if (webRoots.isEmpty()) {
            webRoots.addAll(TomcatModuleUtils.findUnconventionalWebRoots(module));
        }

        // Convention fallbacks for web layouts with no WEB-INF marker that the
        // structural finders above therefore miss (Spring Boot resource dirs,
        // static-site roots).
        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverConventionFallbackRoots(module));
        }

        if (!webRoots.isEmpty()) {
            LOG.debug("DevTomcat: Web root discovery found " + webRoots.size() + " locations");
        }

        return webRoots;
    }

    private boolean configureTomcatServer(@NotNull TomcatRunConfiguration configuration) {
        List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();

        if (tomcatInfos.isEmpty()) {
            LOG.debug("Tomcat: No Tomcat servers configured; auto-creation requires server setup");
            return false;
        }

        TomcatInfo selectedServer = selectOptimalTomcatServer(tomcatInfos);
        configuration.setTomcatInfo(selectedServer);

        LOG.debug("Tomcat: Selected server - " + selectedServer.getName() +
                " " + selectedServer.getVersion());
        return true;
    }

    /**
     * Populates the configuration in the modern deployment model, mirroring the
     * Setup action's single-module case: one exploded
     * {@link ModuleBackedDeployment} at the detected WAR build output, default
     * context path, local server mode, and auto-bumping port defaults.
     */
    private void configureRunConfiguration(@NotNull TomcatRunConfiguration configuration,
                                           @NotNull ProjectTomcatProfileScanner.DetectedWebappModule detected) {
        // Name derives from the module's own Maven identity — no framework or
        // build-tool taxonomy. The prefix only marks the config as auto-created
        // so it is distinguishable from user-authored ones in the run widget.
        String configName = CONFIGURATION_PREFIX + detected.artifactId();
        configuration.setName(configName);

        // Module pointer binds by IntelliJ module name (NOT the Maven artifactId,
        // which names the build output, not the module).
        ModuleBackedDeployment deployment = ModuleBackedDeployment.ofName(
                configuration.getProject(), detected.moduleName(),
                java.nio.file.Path.of(detected.explodedPath()), detected.contextPath(), true);
        configuration.getConfigData().getDeploymentConfig().setDeployments(List.of(deployment));

        // Server mode (local) and ports (8080/8005, auto-bump) are the defaults a
        // fresh TomcatConfigurationData already carries — re-seeding them here would
        // only duplicate those defaults and override whatever the user set on the
        // DevTomcat run-config template. The artifact above is the only thing this
        // context actually determines.

        LOG.debug("Tomcat: Configuration setup complete - " + configName
                + " deploying " + detected.explodedPath()
                + " at " + detected.contextPath());
    }

    /**
     * Whether {@code element}'s file is a web context — used only to rank DevTomcat
     * as the preferred run configuration for a context.
     *
     * <p>Cheap signals first: a known web view/descriptor file by exact name or
     * extension ({@link Locale#ROOT}-folded {@code O(1)} set lookups), which also
     * works for non-physical/light PsiFiles whose {@code getVirtualFile()} is null
     * (scratch/injected files), where the file name is still available. Only if
     * those miss does it fall back to the structural check — the file lives under a
     * discovered web root (conventional, facet-configured, or any {@code WEB-INF}
     * holder), whatever its extension and however the directory is named, so it
     * adapts to custom layouts. The structural check runs last because it can walk
     * the VFS, and is skipped for test sources (a test webapp must not make
     * DevTomcat the preferred config), mirroring {@link #discoverWebRootsForContext}.
     *
     * <p>Deliberately does <em>not</em> substring-match the file name: the previous
     * {@code contains("servlet")}/{@code contains("controller")} fired on any file
     * whose name merely contained those words (e.g. {@code BaseControllerHelper},
     * {@code ServletMockTest}).
     */
    private boolean isWebModuleContext(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }

        com.intellij.psi.PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) return false;

        VirtualFile file = containingFile.getVirtualFile();
        // getName() is always available (even for light/scratch files where the
        // VirtualFile is null); the structural branch below needs the VirtualFile.
        String name = (file != null ? file.getName() : containingFile.getName()).toLowerCase(Locale.ROOT);
        if (WEB_DESCRIPTOR_FILE_NAMES.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && WEB_CONTEXT_FILE_EXTENSIONS.contains(name.substring(dot + 1))) {
            return true;
        }

        if (file != null) {
            Module module = ModuleUtilCore.findModuleForPsiElement(element);
            if (module != null
                    && !ProjectFileIndex.getInstance(module.getProject()).isInTestSourceContent(file)
                    && TomcatModuleUtils.isUnderWebRoot(file, module)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convention fallback for static-site / SPA layouts that carry web content but
     * no {@code WEB-INF} marker — so the structural finders in
     * {@link #discoverWebRootsForContext} (which need a valid web root or a
     * {@code WEB-INF} holder) miss them. These accept a directory on existence alone
     * (no validation), which is why they run only after every structural source came
     * back empty.
     *
     * <p>Spring Boot's {@code src/main/resources/static} is deliberately not probed
     * here: when it holds servable content {@code TomcatModuleUtils.findWebRoots}
     * already discovers it (it is in the validated convention-path list), and a
     * Spring Boot app on an external Tomcat serves static content from the classpath
     * ({@code classpath:/static}, {@code /public}, {@code /META-INF/resources}), not
     * from a webapp docBase — so those are not meaningful docBase roots. (The former
     * source-root probe for them was dead regardless: it called
     * {@code findFileByRelativePath("main/resources/static")} on leaf source roots
     * like {@code src/main/resources}, where that path can never resolve.)
     */
    private List<VirtualFile> discoverConventionFallbackRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
            addIfExists(webRoots, contentRoot.findFileByRelativePath("public"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("static"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("www"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("htdocs"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("docroot"));
        }
        return webRoots;
    }

    /**
     * Helper method to add directory if it exists
     */
    private void addIfExists(List<VirtualFile> list, VirtualFile file) {
        if (file != null && file.isDirectory()) {
            list.add(file);
        }
    }

    private TomcatInfo selectOptimalTomcatServer(List<TomcatInfo> servers) {
        return servers.stream()
                .max((s1, s2) -> compareSemanticVersions(s1.getVersion(), s2.getVersion()))
                .orElse(servers.get(0));
    }

    private static int compareSemanticVersions(@NotNull String v1, @NotNull String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) return Integer.compare(num1, num2);
        }
        return 0;
    }

    private static int parseVersionPart(@NotNull String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
