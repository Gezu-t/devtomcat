package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.intellij.execution.Location;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
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

        if (!configureTomcatServer(configuration)) {
            return false;
        }

        configureRunConfiguration(configuration, module, webRoots);

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

        List<VirtualFile> webRoots = discoverWebRootsForContext(context.getLocation());
        return webRoots.stream().anyMatch(webRoot ->
                webRoot.getPath().equals(configuration.getDocBase()));
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

        // Structure-agnostic first: conventional roots, then the user's explicitly
        // configured Web Facet roots, then any directory that holds WEB-INF whatever
        // its name — so a custom layout is discovered without a fixed name list.
        webRoots.addAll(TomcatModuleUtils.findWebRoots(module));

        if (webRoots.isEmpty()) {
            webRoots.addAll(TomcatModuleUtils.findWebFacetRoots(module));
        }

        if (webRoots.isEmpty()) {
            webRoots.addAll(TomcatModuleUtils.findUnconventionalWebRoots(module));
        }

        // Convention fallbacks for layouts with no WEB-INF marker (Spring resource
        // dirs, static/SPA roots) and for an existing-but-empty conventional webapp.
        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverSpringBootWebRoots(module));
        }

        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverMavenGradleWebRoots(module));
        }

        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverAlternativeWebRoots(module));
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

    private void configureRunConfiguration(@NotNull TomcatRunConfiguration configuration,
                                           @NotNull Module module,
                                           @NotNull List<VirtualFile> webRoots) {
        String contextPath = deriveContextPath(module);
        String configName = buildConfigurationName(contextPath, module);
        configuration.setName(configName);

        configuration.setDocBase(webRoots.get(0).getPath());

        String normalizedContextPath = normalizeAndValidateContextPath(contextPath);
        configuration.setContextPath(normalizedContextPath);


        LOG.debug("Tomcat: Configuration setup complete - " + configName +
                " at " + normalizedContextPath);
    }

    private String deriveContextPath(@NotNull Module module) {
        String contextPath = TomcatModuleUtils.extractContextPath(module);

        if (contextPath == null || contextPath.trim().isEmpty() || contextPath.equals("/")) {
            contextPath = module.getName();

            contextPath = contextPath.replaceAll("[-_](web|webapp|app|main|server)$", "");
            contextPath = contextPath.replaceAll("^(web|webapp|app)-?", "");
        }

        return contextPath;
    }

    private String buildConfigurationName(@NotNull String contextPath, @NotNull Module module) {
        StringBuilder name = new StringBuilder(CONFIGURATION_PREFIX);
        name.append(contextPath);

        if (isSpringBootModule(module)) {
            name.append(" (Spring Boot)");
        } else if (isMavenModule(module)) {
            name.append(" (Maven Web)");
        } else if (isGradleModule(module)) {
            name.append(" (Gradle Web)");
        } else {
            name.append(" (Web Application)");
        }

        return name.toString();
    }

    private String normalizeAndValidateContextPath(@NotNull String contextPath) {
        if (contextPath.trim().isEmpty()) {
            return "/";
        }

        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }

        contextPath = contextPath.replaceAll("[^a-zA-Z0-9/_.~-]", "");

        if (contextPath.equals("/")) {
            LOG.debug("Tomcat: Using root context path for deployment");
        } else {
            LOG.debug("Tomcat: Context path configured: " + contextPath);
        }

        return contextPath;
    }



    /**
     * Whether {@code element}'s file is a web context — used only to rank DevTomcat
     * as the preferred run configuration for a context. Structural first: any file
     * that lives under a discovered web root (conventional, facet-configured, or any
     * {@code WEB-INF} holder) is web context, whatever its extension and however the
     * directory is named — so it adapts to custom layouts. As a secondary hint it
     * accepts a known web view/descriptor file by extension or exact name.
     *
     * <p>Deliberately does <em>not</em> substring-match the file name: the previous
     * {@code contains("servlet")}/{@code contains("controller")} fired on any file
     * whose name merely contained those words (e.g. {@code BaseControllerHelper},
     * {@code ServletMockTest}). Extension/name comparison is {@link Locale#ROOT}-folded
     * for consistency with the rest of the module-matching pipeline.
     */
    private boolean isWebModuleContext(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }

        com.intellij.psi.PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) return false;

        VirtualFile file = containingFile.getVirtualFile();
        if (file != null) {
            Module module = ModuleUtilCore.findModuleForPsiElement(element);
            if (module != null && TomcatModuleUtils.isUnderWebRoot(file, module)) {
                return true;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (WEB_DESCRIPTOR_FILE_NAMES.contains(name)) {
                return true;
            }
            String extension = file.getExtension();
            if (extension != null && WEB_CONTEXT_FILE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<VirtualFile> discoverSpringBootWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();

        for (VirtualFile sourceRoot : sourceRoots) {
            addIfExists(webRoots, sourceRoot.findFileByRelativePath("main/resources/static"));
            addIfExists(webRoots, sourceRoot.findFileByRelativePath("main/resources/public"));
            addIfExists(webRoots, sourceRoot.findFileByRelativePath("main/resources/templates"));
            addIfExists(webRoots, sourceRoot.findFileByRelativePath("main/resources/META-INF/resources"));
        }

        if (!webRoots.isEmpty()) {
            LOG.debug("Tomcat: Spring Boot web roots discovered");
        }

        return webRoots;
    }

    private List<VirtualFile> discoverMavenGradleWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            addIfExists(webRoots, contentRoot.findFileByRelativePath("src/main/webapp"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("src/main/web"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("web"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("webapp"));
            addIfExists(webRoots, contentRoot.findFileByRelativePath("WebContent"));
        }

        if (!webRoots.isEmpty()) {
            LOG.debug("Tomcat: Maven/Gradle web roots discovered");
        }

        return webRoots;
    }

    private List<VirtualFile> discoverAlternativeWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
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

    private boolean isSpringBootModule(@NotNull Module module) {
        // Authoritative + build-agnostic: a spring-boot artifact on the module's
        // runtime classpath. Immune to class-naming conventions and avoids the
        // old depth-5 EDT recursion over main/java hunting for "*Application.java"
        // filenames (which also false-matched any unrelated *Application class).
        for (VirtualFile root : OrderEnumerator.orderEntries(module)
                .runtimeOnly().recursively().classes().getRoots()) {
            if (root.getName().startsWith("spring-boot")) {
                return true;
            }
        }
        // Structural fallback for a not-yet-imported project with no resolved
        // classpath: the conventional Spring Boot config files.
        for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
            VirtualFile resourcesDir = sourceRoot.findFileByRelativePath("main/resources");
            if (resourcesDir != null && hasSpringBootResources(resourcesDir)) {
                return true;
            }
        }
        return false;
    }

    // The owning build tool comes from the resolved external-system model, not
    // from probing for a pom.xml / build.gradle file. This is build-agnostic
    // (any external system), immune to the case-sensitive findFileByRelativePath
    // fragility, and correctly attributes a module whose build file lives
    // elsewhere. getExternalSystemId() returns "MAVEN" / "GRADLE" (uppercase;
    // Maven is locale-folded) — so compare case-insensitively, never a literal.
    private boolean isMavenModule(@NotNull Module module) {
        return isOwnedByExternalSystem(module, "MAVEN");
    }

    private boolean isGradleModule(@NotNull Module module) {
        return isOwnedByExternalSystem(module, "GRADLE");
    }

    private static boolean isOwnedByExternalSystem(@NotNull Module module, @NotNull String systemId) {
        String id = ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId();
        return id != null && id.equalsIgnoreCase(systemId);
    }

    private boolean hasSpringBootResources(@NotNull VirtualFile resourcesDir) {
        VirtualFile applicationProps = resourcesDir.findFileByRelativePath("application.properties");
        VirtualFile applicationYml = resourcesDir.findFileByRelativePath("application.yml");
        VirtualFile applicationYaml = resourcesDir.findFileByRelativePath("application.yaml");
        VirtualFile bootstrapProps = resourcesDir.findFileByRelativePath("bootstrap.properties");
        VirtualFile bootstrapYml = resourcesDir.findFileByRelativePath("bootstrap.yml");

        return (applicationProps != null && applicationProps.exists()) ||
                (applicationYml != null && applicationYml.exists()) ||
                (applicationYaml != null && applicationYaml.exists()) ||
                (bootstrapProps != null && bootstrapProps.exists()) ||
                (bootstrapYml != null && bootstrapYml.exists());
    }


}
