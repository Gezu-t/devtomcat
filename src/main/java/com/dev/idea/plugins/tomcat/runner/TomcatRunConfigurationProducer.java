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
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import com.intellij.openapi.diagnostic.Logger;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/**
 * Professional Enterprise DevTomcat Run Configuration Producer
 * Provides intelligent run configuration creation with comprehensive enterprise features
 *
 * Enterprise Production Features:
 * - Intelligent project type detection (Spring Boot, Maven, Gradle)
 * - Professional web root discovery with multiple fallback strategies
 * - Enterprise development mode optimization and smart defaults
 * - Advanced context path extraction with intelligent naming
 * - Professional environment variable setup for development
 * - Comprehensive validation and configuration optimization
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatRunConfigurationProducer extends LazyRunConfigurationProducer<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationProducer.class);


    private static final String DEVTOMCAT_REGISTRY_KEY = "devTomcat.disableRunConfigurationProducer";
    private static final String CONFIGURATION_PREFIX = "DevTomcat: ";

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

        // Professional conflict avoidance with Application run configurations
        PsiClass psiClass = ApplicationConfigurationType.getMainClass(context.getPsiLocation());
        if (psiClass != null) {
            return false;
        }

        // Professional web root discovery with enterprise intelligence
        List<VirtualFile> webRoots = discoverEnterpriseWebRoots(context.getLocation());
        if (webRoots.isEmpty()) {
            return false;
        }

        // Professional Tomcat server configuration and validation
        if (!setupEnterpriseServerConfiguration(configuration)) {
            return false;
        }

        // Professional configuration setup with enterprise features
        setupEnterpriseConfiguration(configuration, module, webRoots);

        LOG.debug("DevTomcat: Professional run configuration created for module: " + module.getName());
        return true;
    }

    @Override
    public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
        // DevTomcat configurations are preferred for enterprise web modules
        if (self.getConfiguration() instanceof TomcatRunConfiguration) {
            return isEnterpriseWebModuleContext(self.getSourceElement());
        }
        return false;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull TomcatRunConfiguration configuration,
                                              @NotNull ConfigurationContext context) {
        if (isProducerDisabled()) {
            return false;
        }

        List<VirtualFile> webRoots = discoverEnterpriseWebRoots(context.getLocation());
        return webRoots.stream().anyMatch(webRoot ->
                webRoot.getPath().equals(configuration.getDocBase()));
    }

    /**
     * Registry-backed feature toggle, safe against missing keys.
     */
    private boolean isProducerDisabled() {
        try {
            // Registry key is optional; default to enabled if missing.
            return com.intellij.openapi.util.registry.Registry.is(DEVTOMCAT_REGISTRY_KEY);
        } catch (MissingResourceException ignore) {
            return false;
        }
    }

    /**
     * Discover enterprise web roots with comprehensive intelligence
     * Supports Spring Boot, Maven, Gradle, and traditional web applications
     */
    private List<VirtualFile> discoverEnterpriseWebRoots(@Nullable Location<?> location) {
        if (location == null) {
            return ContainerUtil.emptyList();
        }

        // Professional test file exclusion
        boolean isTestFile = TomcatModuleUtils.isTestSource(location);
        if (isTestFile) {
            LOG.debug("DevTomcat: Skipping test file location for web root discovery");
            return ContainerUtil.emptyList();
        }

        Module module = location.getModule();
        if (module == null) {
            return ContainerUtil.emptyList();
        }

        // Professional multi-strategy web root discovery
        List<VirtualFile> webRoots = new ArrayList<>();

        webRoots.addAll(TomcatModuleUtils.findWebRoots(module));

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
            LOG.debug("DevTomcat: Professional web root discovery found " + webRoots.size() + " locations");
        }

        return webRoots;
    }

    /**
     * Setup enterprise Tomcat server configuration with validation
     */
    private boolean setupEnterpriseServerConfiguration(@NotNull TomcatRunConfiguration configuration) {
        List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();

        if (tomcatInfos.isEmpty()) {
            LOG.debug("Tomcat: No Tomcat servers configured - professional configuration creation requires server setup");
            return false;
        }

        // Professional server selection with optimization
        TomcatInfo selectedServer = selectOptimalTomcatServer(tomcatInfos);
        configuration.setTomcatInfo(selectedServer);

        LOG.debug("Tomcat: Professional server selected - " + selectedServer.getName() +
                " " + selectedServer.getVersion());
        return true;
    }

    /**
     * Setup enterprise configuration with comprehensive professional features
     */
    private void setupEnterpriseConfiguration(@NotNull TomcatRunConfiguration configuration,
                                              @NotNull Module module,
                                              @NotNull List<VirtualFile> webRoots) {

        // Professional context path extraction and validation
        String contextPath = extractEnterpriseContextPath(module);

        // Professional configuration naming with project type detection
        String configName = createProfessionalConfigurationName(contextPath, module);
        configuration.setName(configName);

        // Professional document base configuration
        configuration.setDocBase(webRoots.get(0).getPath());

        // Professional context path normalization and validation
        String normalizedContextPath = normalizeAndValidateContextPath(contextPath);
        configuration.setContextPath(normalizedContextPath);


        LOG.debug("Tomcat: Professional configuration setup complete - " + configName +
                " at " + normalizedContextPath);
    }

    /**
     * Extract enterprise context path with intelligent fallbacks
     */
    private String extractEnterpriseContextPath(@NotNull Module module) {
        // Professional context path extraction - FIXED: Using TomcatModuleUtils
        String contextPath = TomcatModuleUtils.extractContextPath(module);

        if (contextPath == null || contextPath.trim().isEmpty() || contextPath.equals("/")) {
            // Professional fallback strategy
            contextPath = module.getName();

            // Professional name cleaning and optimization
            contextPath = contextPath.replaceAll("[-_](web|webapp|app|main|server)$", "");
            contextPath = contextPath.replaceAll("^(web|webapp|app)-?", "");
        }

        return contextPath;
    }

    /**
     * Create professional configuration name with project type intelligence
     */
    private String createProfessionalConfigurationName(@NotNull String contextPath, @NotNull Module module) {
        StringBuilder name = new StringBuilder(CONFIGURATION_PREFIX);
        name.append(contextPath);

        // Professional project type detection and labeling
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

    /**
     * Normalize and validate context path with enterprise standards
     */
    private String normalizeAndValidateContextPath(@NotNull String contextPath) {
        if (contextPath.trim().isEmpty()) {
            return "/";
        }

        // Professional context path normalization
        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }

        // Professional validation and cleaning (preserve dots for paths like /api.v2)
        contextPath = contextPath.replaceAll("[^a-zA-Z0-9/_.~-]", "");

        if (contextPath.equals("/")) {
            LOG.debug("Tomcat: Using root context path for deployment");
        } else {
            LOG.debug("Tomcat: Professional context path configured: " + contextPath);
        }

        return contextPath;
    }



    /**
     * Check if this is an enterprise web module context
     */
    private boolean isEnterpriseWebModuleContext(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }

        com.intellij.psi.PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) return false;
        String fileName = containingFile.getName().toLowerCase();
        return fileName.endsWith(".jsp") ||
                fileName.endsWith(".jspx") ||
                fileName.endsWith(".html") ||
                fileName.endsWith(".xhtml") ||
                fileName.endsWith(".ftl") ||
                fileName.endsWith(".vm") ||
                fileName.contains("web.xml") ||
                fileName.contains("servlet") ||
                fileName.contains("controller");
    }

    /**
     * Discover Spring Boot web roots with enterprise intelligence
     */
    private List<VirtualFile> discoverSpringBootWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();

        for (VirtualFile sourceRoot : sourceRoots) {
            // Professional Spring Boot resource directory discovery
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

    /**
     * Discover Maven/Gradle web roots with enterprise patterns
     */
    private List<VirtualFile> discoverMavenGradleWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            // Professional Maven/Gradle web directory patterns
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

    /**
     * Discover alternative web roots with comprehensive patterns
     */
    private List<VirtualFile> discoverAlternativeWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            // Professional alternative web directory patterns
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

    /**
     * Select optimal Tomcat server for enterprise deployment
     */
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

    /**
     * Check if module is a Spring Boot module with enterprise detection
     */
    private boolean isSpringBootModule(@NotNull Module module) {
        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();

        for (VirtualFile sourceRoot : sourceRoots) {
            VirtualFile javaDir = sourceRoot.findFileByRelativePath("main/java");
            if (javaDir != null && hasSpringBootIndicators(javaDir)) {
                return true;
            }

            // Check for Spring Boot configuration files
            VirtualFile resourcesDir = sourceRoot.findFileByRelativePath("main/resources");
            if (resourcesDir != null && hasSpringBootResources(resourcesDir)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if module is a Maven module with professional detection
     */
    private boolean isMavenModule(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            VirtualFile pomXml = contentRoot.findFileByRelativePath("pom.xml");
            if (pomXml != null && pomXml.exists()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if module is a Gradle module with professional detection
     */
    private boolean isGradleModule(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            VirtualFile buildGradle = contentRoot.findFileByRelativePath("build.gradle");
            VirtualFile buildGradleKts = contentRoot.findFileByRelativePath("build.gradle.kts");

            if ((buildGradle != null && buildGradle.exists()) ||
                    (buildGradleKts != null && buildGradleKts.exists())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check for Spring Boot indicators in Java directory.
     * Limits recursion depth to avoid scanning very deep source trees on the EDT.
     */
    private boolean hasSpringBootIndicators(@NotNull VirtualFile directory) {
        return hasSpringBootIndicators(directory, 0);
    }

    private static final int MAX_SPRING_SCAN_DEPTH = 5;

    private boolean hasSpringBootIndicators(@NotNull VirtualFile directory, int depth) {
        if (depth > MAX_SPRING_SCAN_DEPTH) return false;
        VirtualFile[] children = directory.getChildren();

        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                if (hasSpringBootIndicators(child, depth + 1)) {
                    return true;
                }
            } else if (child.getName().endsWith(".java")) {
                String fileName = child.getName();
                if (fileName.contains("Application") ||
                        fileName.contains("SpringBoot")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check for Spring Boot resources with enterprise detection
     */
    private boolean hasSpringBootResources(@NotNull VirtualFile resourcesDir) {
        // Professional Spring Boot configuration file detection
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
