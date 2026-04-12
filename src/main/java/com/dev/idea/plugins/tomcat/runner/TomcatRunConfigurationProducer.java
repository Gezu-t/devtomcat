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
import com.intellij.openapi.util.registry.Registry;
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
 * Produces DevTomcat run configurations for web-oriented module contexts.
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



    private boolean isWebModuleContext(@Nullable PsiElement element) {
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
        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();

        for (VirtualFile sourceRoot : sourceRoots) {
            VirtualFile javaDir = sourceRoot.findFileByRelativePath("main/java");
            if (javaDir != null && hasSpringBootIndicators(javaDir)) {
                return true;
            }

            VirtualFile resourcesDir = sourceRoot.findFileByRelativePath("main/resources");
            if (resourcesDir != null && hasSpringBootResources(resourcesDir)) {
                return true;
            }
        }

        return false;
    }

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
