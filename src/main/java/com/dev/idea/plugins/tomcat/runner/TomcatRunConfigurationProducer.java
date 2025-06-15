package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.utils.PluginUtils;
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
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    private static final String DEVTOMCAT_REGISTRY_KEY = "devTomcat.disableRunConfigurationProducer";
    private static final String CONFIGURATION_PREFIX = "DevTomcat: ";

    @NotNull
    @Override
    public ConfigurationFactory getConfigurationFactory() {
        TomcatRunConfigurationType configurationType = ConfigurationTypeUtil.findConfigurationType(TomcatRunConfigurationType.class);
        return configurationType.getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull TomcatRunConfiguration configuration,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        // Check if DevTomcat configuration producer is disabled
        if (Registry.is(DEVTOMCAT_REGISTRY_KEY)) {
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

        System.out.println("DevTomcat: Professional run configuration created for module: " + module.getName());
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
        if (Registry.is(DEVTOMCAT_REGISTRY_KEY)) {
            return false;
        }

        List<VirtualFile> webRoots = discoverEnterpriseWebRoots(context.getLocation());
        return webRoots.stream().anyMatch(webRoot ->
                webRoot.getPath().equals(configuration.getDocBase()));
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
        boolean isTestFile = PluginUtils.isUnderTestSources(location);
        if (isTestFile) {
            System.out.println("DevTomcat: Skipping test file location for web root discovery");
            return ContainerUtil.emptyList();
        }

        Module module = location.getModule();
        if (module == null) {
            return ContainerUtil.emptyList();
        }

        // Professional multi-strategy web root discovery
        List<VirtualFile> webRoots = new ArrayList<>();

        // Strategy 1: Original plugin web root discovery
        webRoots.addAll(PluginUtils.findWebRoots(module));

        // Strategy 2: Spring Boot web root discovery
        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverSpringBootWebRoots(module));
        }

        // Strategy 3: Maven/Gradle web root discovery
        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverMavenGradleWebRoots(module));
        }

        // Strategy 4: Alternative web directory patterns
        if (webRoots.isEmpty()) {
            webRoots.addAll(discoverAlternativeWebRoots(module));
        }

        if (!webRoots.isEmpty()) {
            System.out.println("DevTomcat: Professional web root discovery found " + webRoots.size() + " locations");
        }

        return webRoots;
    }

    /**
     * Setup enterprise Tomcat server configuration with validation
     */
    private boolean setupEnterpriseServerConfiguration(@NotNull TomcatRunConfiguration configuration) {
        List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();

        if (tomcatInfos.isEmpty()) {
            System.out.println("DevTomcat: No Tomcat servers configured - professional configuration creation requires server setup");
            return false;
        }

        // Professional server selection with optimization
        TomcatInfo selectedServer = selectOptimalTomcatServer(tomcatInfos);
        configuration.setTomcatInfo(selectedServer);

        System.out.println("DevTomcat: Professional server selected - " + selectedServer.getName() +
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

        // Professional development mode optimization
        enableDevelopmentModeOptimizations(configuration, module);

        System.out.println("DevTomcat: Professional configuration setup complete - " + configName +
                " at " + normalizedContextPath);
    }

    /**
     * Extract enterprise context path with intelligent fallbacks
     */
    private String extractEnterpriseContextPath(@NotNull Module module) {
        // Professional context path extraction
        String contextPath = PluginUtils.extractContextPath(module);

        if (contextPath == null || contextPath.trim().isEmpty()) {
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

        // Professional validation and cleaning
        contextPath = contextPath.replaceAll("[^a-zA-Z0-9/_-]", "");

        if (contextPath.equals("/")) {
            System.out.println("DevTomcat: Using root context path for deployment");
        } else {
            System.out.println("DevTomcat: Professional context path configured: " + contextPath);
        }

        return contextPath;
    }

    /**
     * Enable development mode optimizations for enterprise development
     */
    private void enableDevelopmentModeOptimizations(@NotNull TomcatRunConfiguration configuration,
                                                    @NotNull Module module) {

        // Professional hot deployment optimization
        configuration.setHotDeploymentEnabled(true);
        configuration.setUpdateClassesAndResources(true);

        // Professional development environment variables
        configuration.getEnvironmentVariables().put("JAVA_OPTS",
                "-Xmx1024m -Xms512m -XX:+UseG1GC -Dfile.encoding=UTF-8");
        configuration.getEnvironmentVariables().put("CATALINA_OPTS",
                "-Ddevelopment=true -Dspring.profiles.active=dev");

        // Professional JMX setup for development monitoring
        if (!configuration.isJmxEnabled()) {
            configuration.setJmxEnabled(true);
            configuration.setJmxPort(1099);
        }

        System.out.println("DevTomcat: Professional development mode optimizations enabled");

        // Professional project-specific optimizations
        if (isSpringBootModule(module)) {
            configuration.getEnvironmentVariables().put("SPRING_DEVTOOLS_RESTART_ENABLED", "true");
            System.out.println("DevTomcat: Spring Boot development optimizations applied");
        }
    }

    /**
     * Check if this is an enterprise web module context
     */
    private boolean isEnterpriseWebModuleContext(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }

        // Professional web file pattern detection
        String fileName = element.getContainingFile().getName().toLowerCase();
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
            System.out.println("DevTomcat: Spring Boot web roots discovered");
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
            System.out.println("DevTomcat: Maven/Gradle web roots discovered");
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
        // Professional server selection strategy (prefer latest version)
        return servers.stream()
                .max((s1, s2) -> s1.getVersion().compareTo(s2.getVersion()))
                .orElse(servers.get(0));
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
     * Check for Spring Boot indicators in Java directory
     */
    private boolean hasSpringBootIndicators(@NotNull VirtualFile directory) {
        VirtualFile[] children = directory.getChildren();

        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                if (hasSpringBootIndicators(child)) {
                    return true;
                }
            } else if (child.getName().endsWith(".java")) {
                // Professional Spring Boot class name patterns
                String fileName = child.getName();
                if (fileName.contains("Application") ||
                        fileName.contains("SpringBoot") ||
                        fileName.endsWith("App.java") ||
                        fileName.endsWith("Main.java")) {
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

    /**
     * Get professional configuration summary for reporting
     */
    public String getConfigurationSummary(@NotNull TomcatRunConfiguration configuration) {
        StringBuilder summary = new StringBuilder();
        summary.append("DevTomcat Professional Configuration Summary:\n");
        summary.append("- Name: ").append(configuration.getName()).append("\n");
        summary.append("- Context Path: ").append(configuration.getContextPath()).append("\n");
        summary.append("- Document Base: ").append(configuration.getDocBase()).append("\n");
        summary.append("- Server: ").append(configuration.getTomcatInfo() != null ?
                configuration.getTomcatInfo().getName() : "Default").append("\n");
        summary.append("- Hot Deployment: ").append(configuration.isHotDeploymentEnabled() ? "Enabled" : "Disabled").append("\n");
        summary.append("- JMX Monitoring: ").append(configuration.isJmxEnabled() ?
                "Enabled(" + configuration.getJmxPort() + ")" : "Disabled").append("\n");
        summary.append("- Environment Variables: ").append(configuration.getEnvironmentVariables().size()).append("\n");

        return summary.toString();
    }

    /**
     * Get professional optimization recommendations
     */
    public List<String> getOptimizationRecommendations(@NotNull TomcatRunConfiguration configuration) {
        List<String> recommendations = new ArrayList<>();

        if (!configuration.isHotDeploymentEnabled()) {
            recommendations.add("Enable hot deployment for faster development cycles");
        }

        if (!configuration.isJmxEnabled()) {
            recommendations.add("Enable JMX monitoring for professional development insights");
        }

        if (configuration.getEnvironmentVariables().isEmpty()) {
            recommendations.add("Configure development environment variables for optimal performance");
        }

        String vmOptions = configuration.getVmOptions();
        if (vmOptions == null || !vmOptions.contains("-Xmx")) {
            recommendations.add("Configure heap size for optimal memory usage");
        }

        if (configuration.getLogFileConfigurations().isEmpty()) {
            recommendations.add("Add log file monitoring for comprehensive development tracking");
        }

        return recommendations;
    }

    /**
     * Get enterprise feature analysis
     */
    public String getEnterpriseFeatureAnalysis(@NotNull TomcatRunConfiguration configuration) {
        StringBuilder analysis = new StringBuilder();
        analysis.append("DevTomcat Enterprise Feature Analysis: ");

        int featureCount = 0;

        if (configuration.isJmxEnabled()) {
            analysis.append("JMX-Professional ");
            featureCount++;
        }

        if (configuration.isHotDeploymentEnabled()) {
            analysis.append("HotDeploy-Enterprise ");
            featureCount++;
        }

        if (!configuration.getEnvironmentVariables().isEmpty()) {
            analysis.append("EnvConfig-Advanced ");
            featureCount++;
        }

        if (!configuration.getLogFileConfigurations().isEmpty()) {
            analysis.append("LogMonitoring-Professional ");
            featureCount++;
        }

        analysis.append("(").append(featureCount).append(" enterprise features active)");

        return analysis.toString();
    }

    /**
     * Validate professional configuration standards
     */
    public boolean validateProfessionalStandards(@NotNull TomcatRunConfiguration configuration) {
        boolean isValid = true;
        List<String> issues = new ArrayList<>();

        // Professional validation checks
        if (configuration.getContextPath() == null || configuration.getContextPath().trim().isEmpty()) {
            issues.add("Context path is required for professional deployment");
            isValid = false;
        }

        if (configuration.getDocBase() == null || configuration.getDocBase().trim().isEmpty()) {
            issues.add("Document base is required for professional configuration");
            isValid = false;
        }

        if (configuration.getTomcatInfo() == null) {
            issues.add("Tomcat server selection is required for professional deployment");
            isValid = false;
        }

        // Report validation results
        if (!issues.isEmpty()) {
            System.out.println("DevTomcat: Professional validation issues found:");
            issues.forEach(issue -> System.out.println("  - " + issue));
        } else {
            System.out.println("DevTomcat: Professional configuration validation successful");
        }

        return isValid;
    }

    /**
     * Get professional project type analysis
     */
    public String getProjectTypeAnalysis(@NotNull Module module) {
        StringBuilder analysis = new StringBuilder();
        analysis.append("DevTomcat Project Analysis: ");

        if (isSpringBootModule(module)) {
            analysis.append("Spring Boot Framework ");
        }

        if (isMavenModule(module)) {
            analysis.append("Maven Build System ");
        }

        if (isGradleModule(module)) {
            analysis.append("Gradle Build System ");
        }

        analysis.append("(Module: ").append(module.getName()).append(")");

        return analysis.toString();
    }
}