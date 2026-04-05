package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Local deployment strategy: deploys artifacts to the CATALINA_BASE filesystem.
 *
 * <p>Exploded artifacts get a context XML descriptor in {@code conf/Catalina/localhost/};
 * packaged WARs are copied to {@code webapps/}. Multi-module projects get
 * {@code <PostResources>} entries so the webapp classloader sees all module outputs.
 */
final class LocalDeploymentStrategy implements DeploymentStrategy {

    private static final Logger LOG = Logger.getInstance(LocalDeploymentStrategy.class);

    // --- Tomcat extra resources (context.xml overlay) ---
    private static final String RESOURCE_CLASS_DIR = "org.apache.catalina.webresources.DirResourceSet";
    private static final String RESOURCE_CLASS_FILE = "org.apache.catalina.webresources.FileResourceSet";
    private static final String WEBAPP_MOUNT_CLASSES = "/WEB-INF/classes";
    private static final String WEBAPP_MOUNT_LIB = "/WEB-INF/lib/";

    // Class output dirs are PreResources so they shadow the (potentially stale) WEB-INF/classes
    // inside the exploded artifact's docBase. Tomcat resolves: Pre → docBase → Post.
    // If we used PostResources here, docBase's WEB-INF/classes would always win and freshly
    // compiled target/classes/ would never be seen by the classloader.
    private static final String PRE_RESOURCE_TEMPLATE =
            "\n    <PreResources className=\"%s\"\n                   base=\"%s\" webAppMount=\"%s\" />";

    // JAR files go to PostResources — they extend WEB-INF/lib with entries not already packaged
    // in the artifact, so there is no shadowing conflict with docBase content.
    private static final String POST_RESOURCE_TEMPLATE =
            "\n    <PostResources className=\"%s\"\n                    base=\"%s\" webAppMount=\"%s\" />";

    /**
     * Container-provided libraries must not be injected into a webapp deployed to
     * an external Tomcat. Doing so causes duplicate classes/web fragments when the
     * artifact already contains app-managed variants.
     */
    private static final String[] CONTAINER_PROVIDED_JAR_PREFIXES = {
            "tomcat-",
            "tomcat-embed-",
            "jakarta.servlet",
            "jakarta.servlet-api",
            "javax.servlet",
            "servlet-api",
            "jsp-api",
            "jakarta.jsp",
            "jakarta.el",
            "el-api",
            "ecj-"
    };

    @Override
    public void configureDeployment(@NotNull JavaParameters params,
                                    @NotNull Path catalinaBase,
                                    @NotNull TomcatRunConfiguration configuration,
                                    @NotNull Project project,
                                    @Nullable TomcatDeploymentLogger logger) throws ExecutionException {
        Path webappsDir = catalinaBase.resolve(DIR_WEBAPPS);
        Path confCatalinaLocalhost = catalinaBase.resolve(CONTEXT_XML_DIR);

        try {
            Files.createDirectories(webappsDir);
            Files.createDirectories(confCatalinaLocalhost);
            cleanStaleDeployments(webappsDir, confCatalinaLocalhost);
        } catch (IOException e) {
            throw new ExecutionException("Failed to create deployment directories", e);
        }

        boolean hotDeploy = configuration.getConfigData().getDeploymentConfig().isHotDeploymentEnabled();
        boolean preserveSessions = configuration.getConfigData().getDeploymentConfig().isPreserveSessions();

        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;

            String contextPath = artifact.getContextPath();
            String contextName;
            if (contextPath == null || contextPath.isEmpty() || DEFAULT_CONTEXT_PATH.equals(contextPath)) {
                contextName = ROOT_CONTEXT_NAME;
            } else {
                contextName = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
            }

            Path artifactPath = Paths.get(artifact.getPath());
            if (!Files.exists(artifactPath)) {
                throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
            }

            try {
                if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                        || Files.isDirectory(artifactPath)) {
                    String contextXml = buildContextXml(artifact, artifactPath, hotDeploy, preserveSessions, project, logger);
                    Path contextFile = confCatalinaLocalhost.resolve(contextName + ".xml");
                    Files.writeString(contextFile, contextXml);
                    LOG.info("Deployed exploded artifact via context.xml: " + contextFile);
                } else {
                    Path targetWar = webappsDir.resolve(contextName + ".war");
                    Files.copy(artifactPath, targetWar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Deployed WAR artifact: " + targetWar);
                }
            } catch (IOException e) {
                throw new ExecutionException("Failed to deploy artifact: " + artifact.getPath(), e);
            }
        }
    }

    @NotNull
    private String buildContextXml(@NotNull DeploymentArtifact artifact,
                                   @NotNull Path artifactPath,
                                   boolean hotDeploy,
                                   boolean preserveSessions,
                                   @NotNull Project project,
                                   @Nullable TomcatDeploymentLogger logger) {
        String extraResources = buildExtraResourcesXml(artifact, artifactPath, project, logger);
        String jarScanFilter = buildJarScanFilter(artifactPath);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Context docBase=\"").append(escapeXmlAttribute(artifactPath.toString()));
        // Always set reloadable="false". Tomcat's background class-modification scanner
        // (WebappLoader.backgroundProcess) runs every 10 seconds when reloadable="true" and
        // throws NoSuchFileException for any JARs removed from ~/.m2/repository (e.g. after
        // mvn clean or version upgrades), flooding catalina.log with stack traces.
        // Updates are handled by TomcatApplicationUpdater (Ctrl+F10) which is more reliable.
        xml.append("\" reloadable=\"false\">");

        if (preserveSessions) {
            xml.append("\n  <Manager pathname=\"SESSIONS.ser\" />");
        }
        if (!extraResources.isEmpty()) {
            xml.append("\n  <Resources allowLinking=\"true\">");
            xml.append(extraResources);
            xml.append("\n  </Resources>");
        }
        if (!jarScanFilter.isEmpty()) {
            xml.append("\n  <JarScanner>");
            xml.append("\n    <JarScanFilter pluggabilitySkip=\"").append(jarScanFilter).append("\" />");
            xml.append("\n  </JarScanner>");
        }

        xml.append("\n</Context>\n");
        return xml.toString();
    }

    /**
     * Scans WEB-INF/lib for container-provided jars that could cause duplicate
     * web-fragment errors (e.g. tomcat-jasper + tomcat-embed-jasper both declaring
     * org_apache_jasper). Returns a comma-separated skip pattern for JarScanFilter.
     */
    @NotNull
    private String buildJarScanFilter(@NotNull Path artifactPath) {
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (!Files.isDirectory(webInfLib)) return "";

        List<String> skipPatterns = new ArrayList<>();
        try (var stream = Files.list(webInfLib)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                  .forEach(p -> {
                      String jarName = p.getFileName().toString();
                      if (isContainerProvidedJar(jarName)) {
                          skipPatterns.add(jarName);
                      }
                  });
        } catch (IOException e) {
            LOG.debug("Could not scan WEB-INF/lib for container jars: " + e.getMessage());
        }

        if (skipPatterns.isEmpty()) return "";

        LOG.info("JarScanFilter will skip " + skipPatterns.size() +
                " container-provided jars in WEB-INF/lib: " + skipPatterns);
        return String.join(",", skipPatterns);
    }

    private void cleanStaleDeployments(@NotNull Path webappsDir, @NotNull Path confDir) {
        // Remove previous context XML descriptors to prevent conflicts with new deployments
        try (var stream = Files.list(confDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".xml"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException e) { LOG.debug("Failed to clean: " + p, e); }
                  });
        } catch (IOException e) {
            LOG.debug("Could not clean conf directory: " + confDir, e);
        }

        // Remove previous WAR files to prevent WAR/context XML conflicts
        try (var stream = Files.list(webappsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".war"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException e) { LOG.debug("Failed to clean: " + p, e); }
                  });
        } catch (IOException e) {
            LOG.debug("Could not clean webapps directory: " + webappsDir, e);
        }
    }

    static String escapeXmlAttribute(@NotNull String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    /**
     * Builds extra resource entries for an exploded artifact's context XML:
     * <ul>
     *   <li>Class output directories → {@code <PreResources>} so freshly compiled classes
     *       shadow the (potentially stale) {@code WEB-INF/classes} inside the artifact.</li>
     *   <li>Dependency JARs → {@code <PostResources>} extending {@code WEB-INF/lib} with
     *       entries not already packaged in the artifact.</li>
     * </ul>
     *
     * <p>Project module output directories are identified via {@link com.intellij.openapi.roots.ProjectFileIndex}
     * and are always included — this prevents a name-collision false positive where a
     * third-party {@code api-1.0.jar} in WEB-INF/lib would otherwise suppress
     * a project module also named {@code api}.
     */
    @NotNull
    private String buildExtraResourcesXml(@NotNull DeploymentArtifact artifact,
                                          @NotNull Path artifactPath,
                                          @NotNull Project project,
                                          @Nullable TomcatDeploymentLogger logger) {
        Module module = findModuleForArtifact(artifact, project);
        if (module == null) {
            LOG.info("No module found for artifact '" + artifact.getName() + "', skipping extra classpath");
            return "";
        }

        // Collect existing JARs in WEB-INF/lib to avoid duplicates
        Set<String> existingLibJars = new HashSet<>();
        Set<String> coveredModuleNames = new HashSet<>();
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (Files.isDirectory(webInfLib)) {
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                      .forEach(p -> {
                          String jarName = p.getFileName().toString();
                          existingLibJars.add(jarName);
                          // Track module names covered by packaged JARs so we can skip
                          // their target/classes dirs and avoid duplicate class loading
                          String moduleName = stripJarVersion(jarName);
                          if (moduleName != null) {
                              coveredModuleNames.add(moduleName.toLowerCase(Locale.ROOT));
                          }
                      });
            } catch (IOException e) {
                LOG.debug("Could not list WEB-INF/lib: " + e.getMessage());
            }
        }

        // Normalize artifact paths for cross-platform comparison
        String artifactAbsPath = artifactPath.toAbsolutePath().toString().replace('\\', '/');
        String webInfClassesPath = artifactPath.resolve(WEB_INF).resolve(WEB_INF_CLASSES)
                .toAbsolutePath().toString().replace('\\', '/');

        // Precompute project module output paths using OrderEnumerator (no file-index access).
        // This replaces the ProjectFileIndex.getModuleForFile() lookup that was triggering
        // SlowOperations violations on EDT — module output paths are in the workspace model,
        // not the file index, so they can be read without an expensive index traversal.
        Set<String> moduleOutputPaths = new HashSet<>();
        for (VirtualFile moduleRoot : OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .withoutLibraries()
                .classes()
                .getRoots()) {
            moduleOutputPaths.add(moduleRoot.getPath());
        }

        // Get all runtime classpath entries from the module (recursively includes dependencies)
        List<String> extraDirs = new ArrayList<>();
        List<String> extraJars = new ArrayList<>();
        List<String> skippedModules = new ArrayList<>();

        VirtualFile[] classesRoots = OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .classes()
                .getRoots();

        for (VirtualFile root : classesRoots) {
            String rootPath = root.getPath();
            // Strip trailing !/ from JAR URLs
            if (rootPath.endsWith("!/")) {
                rootPath = rootPath.substring(0, rootPath.length() - 2);
            }

            // Skip entries already under the artifact's docBase
            if (rootPath.startsWith(artifactAbsPath)) {
                continue;
            }

            // Convert to OS-native path for File operations and context XML
            String nativePath = rootPath.replace('/', File.separatorChar);
            File file = new File(nativePath);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                // Class output directory — skip if it IS the artifact's WEB-INF/classes
                if (rootPath.equals(webInfClassesPath)) continue;

                // For project module output directories, always include as PreResources.
                // PreResources take priority over the artifact's docBase (WEB-INF/classes),
                // so even if a same-named JAR is in WEB-INF/lib the freshly compiled classes win.
                // Skipping based on JAR name alone triggers false positives: a module named "api"
                // would be incorrectly blocked by a third-party "api-1.0.jar" in WEB-INF/lib.
                if (moduleOutputPaths.contains(root.getPath())) {
                    extraDirs.add(nativePath);
                    continue;
                }

                // Not a recognised project module directory — apply name-based duplicate guard
                // as a safety net for unusual classpath layouts.
                String moduleName = extractModuleName(nativePath);
                if (moduleName != null && coveredModuleNames.contains(moduleName.toLowerCase(Locale.ROOT))) {
                    LOG.debug("Skipping non-module class dir '" + nativePath + "' — already packaged as JAR in WEB-INF/lib");
                    skippedModules.add(moduleName);
                    continue;
                }
                extraDirs.add(nativePath);
            } else if (rootPath.endsWith(".jar")) {
                // JAR file — skip container-provided libs and jars already packaged in WEB-INF/lib
                String jarName = file.getName();
                if (isContainerProvidedJar(jarName)) continue;
                if (existingLibJars.contains(jarName)) continue;
                extraJars.add(nativePath);
            }
        }

        if (extraDirs.isEmpty() && extraJars.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String dir : extraDirs) {
            sb.append(String.format(PRE_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_DIR, escapeXmlAttribute(dir), WEBAPP_MOUNT_CLASSES));
        }
        for (String jar : extraJars) {
            String jarName = new File(jar).getName();
            sb.append(String.format(POST_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_FILE, escapeXmlAttribute(jar),
                    WEBAPP_MOUNT_LIB + escapeXmlAttribute(jarName)));
        }

        LOG.info("Added " + extraDirs.size() + " class dirs and " + extraJars.size() +
                " JARs as extra resources for artifact '" + artifact.getName() + "'");

        // Single consolidated warning instead of one message per module to keep the console clean
        if (!skippedModules.isEmpty() && logger != null) {
            logger.logServerInfo(
                    "Hot reload skipped for " + skippedModules.size() + " module(s) already packaged as JARs in WEB-INF/lib: "
                    + skippedModules + ". Changes to these modules require Redeploy, not just Build.");
        }

        return sb.toString();
    }

    /**
     * Strips the version suffix from a JAR filename.
     * e.g. "foo-bar-1.2.3.jar" → "foo-bar", "foo-bar-1.2.3-SNAPSHOT.jar" → "foo-bar"
     * Returns null if the name cannot be parsed.
     */
    @Nullable
    static String stripJarVersion(@NotNull String jarName) {
        if (!jarName.endsWith(".jar")) return null;
        String base = jarName.substring(0, jarName.length() - 4);
        // Remove -<version> suffix: version starts with a digit (1.2.3) or is a bare SNAPSHOT
        return base.replaceAll("-(\\d+.*|SNAPSHOT)$", "");
    }

    /**
     * Extracts the module/project name from a class output directory path.
     * Supports Maven ({@code .../module/target/classes}) and common Gradle layouts
     * ({@code .../module/build/classes/java/main} etc.).
     * Returns null if the path does not match a known pattern.
     *
     * <p>Used only as a fallback guard for non-module class directories.
     * Project module output directories are identified directly via
     * {@link com.intellij.openapi.roots.ProjectFileIndex} in the caller.
     */
    @Nullable
    static String extractModuleName(@NotNull String classesDir) {
        String normalized = classesDir.replace('\\', '/');
        // Maven
        if (normalized.endsWith("/target/classes")) {
            String parent = normalized.substring(0, normalized.length() - "/target/classes".length());
            int slash = parent.lastIndexOf('/');
            return slash >= 0 ? parent.substring(slash + 1) : parent;
        }
        // IntelliJ IDEA default compiler output: .../out/production/ModuleName
        int outIdx = normalized.lastIndexOf("/out/production/");
        if (outIdx >= 0) {
            String after = normalized.substring(outIdx + "/out/production/".length());
            int slash = after.indexOf('/');
            String candidate = slash >= 0 ? after.substring(0, slash) : after;
            if (!candidate.isEmpty()) return candidate;
        }

        // Gradle
        String[] gradlePatterns = {
                "/build/classes/java/main",
                "/build/classes/kotlin/main",
                "/build/classes/groovy/main",
                "/build/classes/scala/main"
        };
        for (String pattern : gradlePatterns) {
            if (normalized.endsWith(pattern)) {
                String parent = normalized.substring(0, normalized.length() - pattern.length());
                int slash = parent.lastIndexOf('/');
                return slash >= 0 ? parent.substring(slash + 1) : parent;
            }
        }
        return null;
    }

    static boolean isContainerProvidedJar(@NotNull String jarName) {
        String normalized = jarName.toLowerCase(Locale.ROOT);
        for (String prefix : CONTAINER_PROVIDED_JAR_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the IntelliJ Module associated with a deployment artifact.
     * Tries: artifact name match via ArtifactManager, name-based module lookup,
     * path-based matching, and web module fallback.
     */
    @Nullable
    private Module findModuleForArtifact(@NotNull DeploymentArtifact artifact,
                                         @NotNull Project project) {
        try {
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            String name = artifact.getName();

            // 1. ArtifactManager lookup — works on Ultimate where users configure artifacts.
            //    Wrapped in try/catch so it degrades silently on Community Edition where
            //    the packaging plugin may not be loaded (NoClassDefFoundError).
            try {
                com.intellij.packaging.artifacts.ArtifactManager artifactManager =
                        com.intellij.packaging.artifacts.ArtifactManager.getInstance(project);
                if (artifactManager != null) {
                    for (com.intellij.packaging.artifacts.Artifact a : artifactManager.getArtifacts()) {
                        if (name.equals(a.getName())) {
                            String moduleName = a.getName().replaceAll(":war.*$", "").trim();
                            Module m = moduleManager.findModuleByName(moduleName);
                            if (m != null) return m;
                            break;
                        }
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {
                // ArtifactManager not available in this IDE edition — fall through
            }

            // 2. Direct name-based lookup (strip suffixes)
            String baseName = name.replaceAll(":war.*$", "")
                                  .replaceAll("\\.war$", "")
                                  .replaceAll("\\s*\\(.*\\)$", "")
                                  .trim();
            Module module = moduleManager.findModuleByName(baseName);
            if (module != null) return module;

            // 3. Path-based: find module whose content root contains the deployment path
            String deploymentPath = artifact.getPath();
            if (!deploymentPath.isEmpty()) {
                for (Module m : moduleManager.getModules()) {
                    for (VirtualFile contentRoot : ModuleRootManager.getInstance(m).getContentRoots()) {
                        if (deploymentPath.startsWith(contentRoot.getPath())) {
                            if (TomcatModuleUtils.isWebModule(m)) {
                                return m;
                            }
                        }
                    }
                }
            }

            // 4. Single web module fallback
            List<Module> webModules = new ArrayList<>();
            for (Module m : moduleManager.getModules()) {
                if (TomcatModuleUtils.isWebModule(m)) {
                    webModules.add(m);
                }
            }
            if (webModules.size() == 1) return webModules.get(0);

            // 5. Partial name match against web modules
            for (Module m : webModules) {
                String mName = m.getName().toLowerCase();
                String lowerBase = baseName.toLowerCase();
                if (mName.contains(lowerBase) || lowerBase.contains(mName)) {
                    return m;
                }
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Failed to find module for artifact '" + artifact.getName() + "': " + e.getMessage());
            return null;
        }
    }
}
