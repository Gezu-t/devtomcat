package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

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
     *
     * <p>Every prefix in this list is a {@code String#startsWith} match against
     * the lower-cased JAR file name. Prefixes intentionally end with a hyphen,
     * {@code -api}, or a full {@code .jar} filename so they cannot swallow an
     * application library whose Maven coordinate happens to share the head of
     * a Tomcat name. The regression that motivated this list is JSTL: the
     * artifacts {@code jakarta.servlet.jsp.jstl-api-*.jar} and
     * {@code jakarta.servlet.jsp.jstl-*.jar} both start with the bare literals
     * {@code "jakarta.servlet"} and {@code "jakarta.jsp"}. If those bare
     * prefixes were listed here, JSTL would be silently excluded from
     * {@code WEB-INF/lib} resource injection and the webapp would throw
     * {@code ClassNotFoundException: jakarta.servlet.jsp.jstl.core.Config}
     * on the first {@code <c:*>} tag.
     *
     * <p>Coverage targets every JAR Tomcat 7 through 11 ships in {@code lib/}:
     * <ul>
     *   <li>Tomcat internals: {@code tomcat-*}, {@code catalina-*},
     *       {@code catalina.jar}, {@code jasper*}, {@code ecj-*},
     *       {@code bootstrap.jar}, {@code commons-daemon-*}.</li>
     *   <li>Servlet/JSP/EL API: {@code jakarta.*-api} and the legacy
     *       {@code javax.*-api} forms, plus the bare {@code servlet-api-*},
     *       {@code jsp-api-*}, {@code el-api-*} naming used by older Tomcats.</li>
     *   <li>EL implementation: {@code jakarta.el-} (matches both API and the
     *       Glassfish-derived impl JAR).</li>
     *   <li>Annotation API: {@code jakarta.annotation-api},
     *       {@code javax.annotation-api}, legacy {@code annotations-api}.</li>
     *   <li>WebSocket API: {@code jakarta.websocket-},
     *       {@code javax.websocket-}, plus legacy {@code websocket-api},
     *       {@code websocket-client-api}.</li>
     *   <li>JASPIC (auth): {@code jaspic-api},
     *       {@code jakarta.security.auth.message-api}.</li>
     * </ul>
     *
     * <p>Bias: prefer false negatives (an app-provided JAR slipping through and
     * causing a duplicate-class warning at startup) over false positives (a
     * container JAR mistakenly identified as app-provided, which would cause
     * a hard {@code ClassNotFoundException} at runtime). Bare prefixes that
     * could collide with longer Maven coordinates are not on this list.
     */
    private static final String[] CONTAINER_PROVIDED_JAR_PREFIXES = {
            // Tomcat internals
            "tomcat-",                          // tomcat-api, tomcat-coyote, tomcat-juli, tomcat-util,
                                                // tomcat-websocket, tomcat-jdbc, tomcat-dbcp, tomcat-jni,
                                                // tomcat-i18n-*, tomcat-jasper, tomcat-servlet-api, etc.
            "catalina-",                        // catalina-ant, catalina-ha, catalina-ssi,
                                                // catalina-storeconfig, catalina-tribes
            "catalina.jar",                     // bare catalina core
            "jasper-", "jasper.jar",            // JSP engine (jasper.jar, jasper-el.jar)
            "ecj-",                             // Eclipse JDT compiler
            "bootstrap.jar",                    // catalina.sh / catalina.bat bootstrap
            "commons-daemon-",                  // jsvc/procrun launcher

            // Servlet API. Hyphen on "-api" disambiguates from
            // jakarta.servlet.jsp.jstl-*.jar.
            "jakarta.servlet-api",
            "javax.servlet-api",
            "servlet-api",

            // JSP API. Hyphen on "-api" disambiguates from any future
            // jakarta.jsp.jstl-*.jar variant.
            "jakarta.jsp-api",
            "javax.jsp-api",
            "jsp-api",

            // Expression Language. The "-" on "jakarta.el-" matches both
            // jakarta.el-api-*.jar (API) and jakarta.el-*.jar (Glassfish impl).
            "jakarta.el-",
            "javax.el-api",
            "el-api",

            // Annotation API
            "jakarta.annotation-api",
            "javax.annotation-api",
            "annotations-api",                  // legacy Tomcat 8/9 naming

            // WebSocket API
            "jakarta.websocket-",                // jakarta.websocket-api, jakarta.websocket-client-api
            "javax.websocket-",
            "websocket-api",
            "websocket-client-api",

            // JASPIC (Java Authentication SPI for Containers)
            "jakarta.security.auth.message-api",
            "jaspic-api"
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
            // Stale-deployment cleanup is destructive (deletes every .war and every
            // descriptor .xml). Only safe inside the IDE-managed system directory.
            // When the user has pinned an explicit CATALINA_BASE (e.g. their real
            // Tomcat install at /opt/tomcat), those files are theirs to manage —
            // wiping them on launch would erase hand-deployed apps.
            if (isIdeManagedCatalinaBase(catalinaBase, configuration)) {
                cleanStaleDeployments(webappsDir, confCatalinaLocalhost);
            } else if (logger != null) {
                logger.logServerInfo(
                        "Skipping stale-deployment cleanup: CATALINA_BASE is user-pinned ("
                                + catalinaBase + "). Manage existing deployments yourself.");
            }
        } catch (IOException e) {
            throw new ExecutionException("Failed to create deployment directories", e);
        }

        boolean preserveSessions = configuration.isPreserveSessions();
        TomcatInfo tomcatInfo = configuration.getTomcatInfo();

        // JAR-scan compatibility on Tomcat versions whose ContextRuleSet has
        // no rule for Context/JarScanner/JarScanFilter (Tomcat 7.x and 8.0.x,
        // and the BCEL-affected 8.5.<51 / 9.0.<31). On those releases the
        // per-context <JarScanFilter> element is silently dropped with a
        // "No rules found" warning, so any JAR the launcher wants skipped
        // (modular JARs that would crash the BCEL parser AND container-
        // provided JARs that would otherwise duplicate web fragments) has to
        // route through catalina.properties instead, which is loaded into
        // System properties at JVM startup and is honoured by every affected
        // version. Modern Tomcats (10+, 11+, 8.5.51+, 9.0.31+) keep the
        // per-context XML behavior; only the BCEL-affected branch needs the
        // module-info workaround anyway.
        boolean affected = BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(tomcatInfo);
        if (affected) {
            java.util.LinkedHashSet<String> jarsToSkip = new java.util.LinkedHashSet<>();
            // Modular JARs trigger the BCEL bug; without these the run
            // console floods with 'Invalid byte tag in constant pool: 19'
            // SEVERE messages.
            jarsToSkip.addAll(collectModularJarsAcrossDeployments(configuration));
            // Container-provided JARs would otherwise be silently dropped
            // from the per-context filter on these Tomcats. Putting them in
            // catalina.properties keeps the duplicate web-fragment guard
            // in effect across versions.
            jarsToSkip.addAll(collectContainerProvidedJarsAcrossDeployments(configuration));
            if (!jarsToSkip.isEmpty()) {
                JarSkipListInjector.applyToCatalinaProperties(
                        catalinaBase,
                        new ArrayList<>(jarsToSkip),
                        BcelModuleInfoCompat.REASON_HEADER + "\n"
                                + "Container-provided JARs (servlet-api, jsp-api, etc.) found in\n"
                                + "WEB-INF/lib are also routed through this channel because the\n"
                                + "per-context <JarScanFilter> element is not honoured on this\n"
                                + "Tomcat version (the Digester rule was added in 8.5).",
                        logger);
            }
        }

        // ECJ/class-file compatibility: surface a clear pre-launch warning
        // when Tomcat's bundled Eclipse JDT compiler is too old to read the
        // webapp's class files. The deployment-logger warning is informational
        // (in-console diagnostic). The notification with an actionable "Swap
        // ECJ JAR..." button (see EcjJarSwapPrompt) is the persistent IDE-side
        // surface the user can act on when convenient. Without this pair, the
        // user sees a cryptic flood of
        // 'org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException'
        // SEVERE messages at JSP-request time with no hint at the cause.
        if (tomcatInfo != null && !tomcatInfo.getPath().isEmpty()) {
            EcjVersionCompat.Mismatch mismatch = EcjVersionCompat.check(
                    Paths.get(tomcatInfo.getPath()),
                    collectWebInfDirsAcrossDeployments(configuration),
                    logger);
            if (mismatch.isMismatch()) {
                EcjJarSwapPrompt.show(project, mismatch);
            }
        }

        // Tomcat EOL warning. Fired once per IDE session per install so
        // legacy-Tomcat users get a periodic nudge to upgrade without
        // being spammed every launch. Non-blocking; the launch continues.
        TomcatCompatibilityPrompt.showEolWarningOnce(project, tomcatInfo);

        for (DeploymentArtifact artifact : configuration.getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;

            String contextName;
            try {
                contextName = ContextPathUtils.resolveContextName(artifact.getContextPath());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException(e.getMessage());
            }

            Path artifactPath = Paths.get(artifact.getPath());
            if (!Files.exists(artifactPath)) {
                throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
            }

            try {
                if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                        || Files.isDirectory(artifactPath)) {
                    String contextXml = buildContextXml(artifact, artifactPath, preserveSessions,
                            project, configuration.getTomcatInfo(), logger);
                    Path contextFile = confCatalinaLocalhost.resolve(contextName + ".xml");
                    TomcatProjectUtils.atomicWriteString(contextFile, contextXml);
                    LOG.info("Deployed exploded artifact via context.xml: " + contextFile);
                } else {
                    Path targetWar = webappsDir.resolve(contextName + ".war");
                    TomcatProjectUtils.atomicCopy(artifactPath, targetWar);
                    LOG.info("Deployed WAR artifact: " + targetWar);
                }
            } catch (IOException e) {
                throw new ExecutionException("Failed to deploy artifact: " + artifact.getPath(), e);
            }
        }
    }

    @NotNull
    static String buildContextXml(@NotNull DeploymentArtifact artifact,
                                  @NotNull Path artifactPath,
                                  boolean preserveSessions,
                                  @NotNull Project project,
                                  @Nullable TomcatInfo tomcatInfo,
                                  @Nullable TomcatDeploymentLogger logger) {
        String extraResources = buildExtraResourcesXml(artifact, artifactPath, project, tomcatInfo, logger);
        String jarScanFilter = buildJarScanFilter(artifactPath, tomcatInfo, logger);

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
            xml.append("\n    <JarScanFilter pluggabilitySkip=\"").append(escapeXmlAttribute(jarScanFilter)).append("\" />");
            xml.append("\n  </JarScanner>");
        }

        xml.append("\n</Context>\n");
        return xml.toString();
    }

    /**
     * Builds the {@code <JarScanFilter pluggabilitySkip="...">} value for
     * the artifact's context descriptor on Tomcats whose Digester
     * recognises that element (8.5+). On older Tomcats the element is
     * silently dropped, so {@code configureDeployment} routes container-
     * provided JARs through {@code catalina.properties} via
     * {@link JarSkipListInjector} instead and this method returns the
     * empty string.
     *
     * <p>Returns the empty string when nothing needs skipping; the caller
     * then omits the {@code <JarScanner>} element entirely so user-
     * supplied scanner configuration in {@code conf/} stays in effect.
     */
    @NotNull
    private static String buildJarScanFilter(@NotNull Path artifactPath,
                                             @Nullable TomcatInfo tomcatInfo,
                                             @Nullable TomcatDeploymentLogger logger) {
        // On Tomcat versions where the per-context filter element is
        // silently dropped, the container-provided skip already went
        // through catalina.properties at configureDeployment time. Emit
        // nothing here so the descriptor stays clean and Tomcat's Digester
        // does not log a "No rules found" warning.
        if (BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(tomcatInfo)) {
            return "";
        }

        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (!Files.isDirectory(webInfLib)) return "";

        // LinkedHashSet for deterministic order in the generated XML.
        java.util.LinkedHashSet<String> skip = new java.util.LinkedHashSet<>();
        try (var stream = Files.list(webInfLib)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                  .forEach(p -> {
                      String jarName = p.getFileName().toString();
                      if (isContainerProvidedJar(jarName)) {
                          skip.add(jarName);
                      }
                  });
        } catch (IOException e) {
            LOG.debug("Could not scan WEB-INF/lib for container jars: " + e.getMessage());
        }

        if (skip.isEmpty()) return "";

        LOG.info("JarScanFilter pluggabilitySkip (" + skip.size() + " jar(s)): " + skip);
        return String.join(",", skip);
    }

    /**
     * Walks every deployment's {@code WEB-INF/lib} once and returns the union
     * of JAR file names that contain a {@code module-info.class} entry. Used
     * by the BCEL/module-info compatibility shim to populate the global
     * {@code jarsToSkip} list in {@code catalina.properties}; we collect
     * across artifacts because that property is process-wide.
     *
     * <p>Order is deterministic (alphabetical, deduplicated) so subsequent
     * launches produce byte-identical {@code catalina.properties} appendices,
     * which keeps the IDE-managed sandbox tree free of unnecessary churn.
     */
    @NotNull
    private static List<String> collectModularJarsAcrossDeployments(
            @NotNull TomcatRunConfiguration configuration) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        for (DeploymentArtifact artifact : configuration.getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) {
                // Packaged WARs are scanned by Tomcat after extraction; we do
                // not pre-extract here. The .war's own content is on disk in
                // webapps/, but inspecting it would require unzipping the WAR
                // first. Keep the scope narrow: exploded artifacts only.
                continue;
            }
            Path artifactPath = Paths.get(artifact.getPath());
            Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
            all.addAll(BcelModuleInfoCompat.findJarsContainingModuleInfo(webInfLib));
        }
        return new ArrayList<>(all);
    }

    /**
     * Walks every exploded artifact's {@code WEB-INF/lib} once and returns
     * the union of JAR file names that match {@link #isContainerProvidedJar}.
     * Used on BCEL-affected Tomcats where the per-context
     * {@code <JarScanFilter>} element is silently dropped, so we have to
     * route container-provided JARs through {@code catalina.properties}
     * instead.
     */
    @NotNull
    private static List<String> collectContainerProvidedJarsAcrossDeployments(
            @NotNull TomcatRunConfiguration configuration) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        for (DeploymentArtifact artifact : configuration.getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) continue;
            Path webInfLib = Paths.get(artifact.getPath()).resolve(WEB_INF).resolve(WEB_INF_LIB);
            if (!Files.isDirectory(webInfLib)) continue;
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .map(p -> p.getFileName().toString())
                        .filter(LocalDeploymentStrategy::isContainerProvidedJar)
                        .forEach(all::add);
            } catch (IOException e) {
                LOG.debug("Could not scan " + webInfLib + " for container-provided jars: " + e.getMessage());
            }
        }
        return new ArrayList<>(all);
    }

    /**
     * Collects the {@code WEB-INF} directories of every exploded artifact in
     * the configuration. Used by {@link EcjVersionCompat#check} to sample
     * class file versions across the entire deployment in one pass.
     */
    @NotNull
    private static List<Path> collectWebInfDirsAcrossDeployments(
            @NotNull TomcatRunConfiguration configuration) {
        List<Path> dirs = new ArrayList<>();
        for (DeploymentArtifact artifact : configuration.getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) continue;
            Path webInf = Paths.get(artifact.getPath()).resolve(WEB_INF);
            if (Files.isDirectory(webInf)) {
                dirs.add(webInf);
            }
        }
        return dirs;
    }

    /**
     * True when {@code catalinaBase} is inside the IDE's managed system directory
     * (the standard fallback path), so destructive cleanup of {@code webapps/} and
     * {@code conf/Catalina/localhost/} is safe. False when the user has pinned an
     * explicit {@code CATALINA_BASE} — those files are user-managed and must not
     * be wiped between launches.
     */
    static boolean isIdeManagedCatalinaBase(@NotNull Path catalinaBase,
                                            @NotNull TomcatRunConfiguration configuration) {
        String pinned = configuration.getConfigData() != null
                ? configuration.getConfigData().getCatalinaBase()
                : null;
        return pinned == null || pinned.isBlank();
    }

    private void cleanStaleDeployments(@NotNull Path webappsDir, @NotNull Path confDir) {
        // Remove previous context XML descriptors to prevent conflicts with new deployments
        try (var stream = Files.list(confDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".xml"))
                  .forEach(p -> TomcatProjectUtils.safeDelete(p, LOG));
        } catch (IOException e) {
            LOG.debug("Could not clean conf directory: " + confDir, e);
        }

        // Remove previous WAR files to prevent WAR/context XML conflicts
        try (var stream = Files.list(webappsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".war"))
                  .forEach(p -> TomcatProjectUtils.safeDelete(p, LOG));
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
     * <p>Project module output directories are identified via {@link #collectModelSnapshot},
     * which walks the IntelliJ module dependency graph and uses the Maven artifactId (when
     * available) for reliable JAR-name matching. This prevents false positives where a
     * third-party {@code api-1.0.jar} in WEB-INF/lib would suppress a project module also
     * named {@code api}, and correctly handles modules whose directory name differs from
     * their Maven artifactId.
     */
    @NotNull
    private static String buildExtraResourcesXml(@NotNull DeploymentArtifact artifact,
                                          @NotNull Path artifactPath,
                                          @NotNull Project project,
                                          @Nullable TomcatInfo tomcatInfo,
                                          @Nullable TomcatDeploymentLogger logger) {
        // PreResources/PostResources are Tomcat 8+; Tomcat 7's Digester emits
        // 'No rules found matching Context/Resources/PreResources' and drops them.
        // Major version 0 = unknown — treat as modern (don't accidentally regress modern users).
        if (tomcatInfo != null
                && tomcatInfo.getMajorVersion() > 0
                && tomcatInfo.getMajorVersion() < 8) {
            if (logger != null) {
                logger.logServerInfo(
                        "Tomcat " + tomcatInfo.getMajorVersion()
                                + " does not support <PreResources>/<PostResources> (added in Tomcat 8). "
                                + "Multi-module classpath additions for '" + artifact.getName()
                                + "' will not be applied. Package any required JARs into "
                                + "WEB-INF/lib if your application depends on them.");
            }
            return "";
        }

        // Phase 1, Model access: collect all IntelliJ project model data under a single
        // read action so the snapshot is internally consistent. After this call every value
        // is a plain Java object (Module reference + String maps/lists); no further model
        // access is needed and no threading constraint applies to the rest of this method.
        ArtifactModelSnapshot snapshot = TomcatReadActions.compute(
                () -> collectModelSnapshot(artifact, project));
        if (snapshot == null) {
            LOG.info("No module found for artifact '" + artifact.getName() + "', skipping extra classpath");
            return "";
        }

        // Phase 2 — File I/O: scan WEB-INF/lib once to build the JAR name index (Guard 1)
        // and the pre-scanned JarMeta index (Guard 2). No model access — pure filesystem I/O.
        Set<String> existingLibJars = new HashSet<>();
        Set<String> coveredModuleNames = new HashSet<>();
        List<JarMeta> jarIndex = new ArrayList<>();
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (Files.isDirectory(webInfLib)) {
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                      .forEach(p -> {
                          String jarName = p.getFileName().toString();
                          existingLibJars.add(jarName);
                          String baseName = stripJarVersion(jarName);
                          if (baseName != null) {
                              coveredModuleNames.add(baseName.toLowerCase(Locale.ROOT));
                          }
                          jarIndex.add(scanJar(p, baseName));
                      });
            } catch (IOException e) {
                LOG.debug("Could not list WEB-INF/lib: " + e.getMessage());
            }
        }

        // Phase 3 — Processing: use the snapshot to drive the context XML build.
        // All values below are plain Java objects — no IntelliJ model access, no threading constraint.

        // Normalize artifact paths for cross-platform comparison
        String artifactAbsPath = artifactPath.toAbsolutePath().toString().replace('\\', '/');
        String webInfClassesPath = artifactPath.resolve(WEB_INF).resolve(WEB_INF_CLASSES)
                .toAbsolutePath().toString().replace('\\', '/');

        List<String> extraDirs = new ArrayList<>();
        List<String> extraJars = new ArrayList<>();
        List<String> skippedModules = new ArrayList<>();

        // Track artifact names whose output dirs are injected as PreResources.
        // Their JARs must NOT be added as PostResources — having both the dir AND the JAR
        // on the classpath causes duplicate resource entries that break Liquibase, CDI, etc.
        Set<String> preResourceModuleNames = new HashSet<>();

        // snapshot.rootPaths: classpath roots from the full module dependency tree.
        // Paths are plain strings with trailing !/ already stripped (done in collectModelSnapshot).
        for (String rootPath : snapshot.rootPaths) {
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

                // For project module output directories, include as PreResources so
                // freshly compiled classes shadow the (potentially stale) WEB-INF/classes.
                // Record the artifact name so we can skip its JAR in the PostResources pass.
                if (snapshot.outputToArtifactName.containsKey(rootPath)) {
                    // Artifact name comes from the module graph (Maven artifactId preferred),
                    // not from file-path extraction — reliable even when directory name ≠ artifactId.
                    String moduleDirName = snapshot.outputToArtifactName.get(rootPath);
                    // Guard 1 — name-based: fast, covers Maven and standard Gradle naming.
                    if (moduleDirName != null && coveredModuleNames.contains(moduleDirName.toLowerCase(Locale.ROOT))) {
                        LOG.debug("Skipping PreResources for module '" + moduleDirName + "' — name-matched JAR in WEB-INF/lib");
                        skippedModules.add(moduleDirName);
                        continue;
                    }
                    // Guard 2 — content + metadata: covers custom JAR naming (Gradle archivesBaseName,
                    // Ant custom jar task) AND empty/not-yet-compiled module outputs.
                    // Content check: samples file paths from the output dir and looks for them in JARs.
                    // Metadata check: reads META-INF/maven/<g>/<artifactId>/pom.properties inside JARs
                    // — works even when the output dir is empty because it doesn't need any content.
                    String coveringJar = findCoveringJar(nativePath, moduleDirName, jarIndex);
                    if (coveringJar != null) {
                        LOG.debug("Skipping PreResources for module '" + moduleDirName + "' — matched by '" + coveringJar + "' in WEB-INF/lib");
                        skippedModules.add(moduleDirName != null ? moduleDirName : coveringJar);
                        continue;
                    }
                    extraDirs.add(nativePath);
                    if (moduleDirName != null) {
                        preResourceModuleNames.add(moduleDirName.toLowerCase(Locale.ROOT));
                    }
                    continue;
                }

                // Not a known dependency module output — apply name-based duplicate guard
                // as a safety net for unusual classpath layouts (e.g. the webapp's own
                // target/classes, or output dirs from modules not in the dependency graph).
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

                // Skip JARs whose classes are already provided via PreResources from
                // their module's target/classes. Adding both the classes dir AND the JAR
                // causes duplicate classpath entries that break Liquibase, CDI, etc.
                String jarBase = stripJarVersion(jarName);
                if (jarBase != null && preResourceModuleNames.contains(jarBase.toLowerCase(Locale.ROOT))) {
                    LOG.info("Skipping PostResources for '" + jarName +
                            "' — classes already served via PreResources");
                    continue;
                }

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

    /** Max number of file paths sampled from a module output directory for content matching. */
    private static final int CONTENT_SAMPLE_SIZE = 5;

    /**
     * Pre-scanned metadata for a single JAR in {@code WEB-INF/lib}.
     * Built once per JAR during the initial WEB-INF/lib scan so that subsequent
     * per-module guard checks are purely in-memory — no repeated ZipFile opens.
     */
    static final class JarMeta {
        /** Stripped base name, e.g. {@code "common"} from {@code "common-1.0-SNAPSHOT.jar"}. */
        final String baseName;
        /** All ZIP entry names — used for content-based module matching. */
        final Set<String> entryPaths;
        /** Maven artifactIds from {@code META-INF/maven/<g>/<a>/pom.properties} entries. */
        final Set<String> pomArtifacts;

        JarMeta(String baseName, Set<String> entryPaths, Set<String> pomArtifacts) {
            this.baseName = baseName;
            this.entryPaths = entryPaths;
            this.pomArtifacts = pomArtifacts;
        }
    }

    /**
     * Opens {@code jarPath} once and reads all ZIP entries to build a {@link JarMeta}.
     * {@code META-INF/maven/<g>/<a>/pom.properties} entries are parsed to extract Maven
     * artifactIds for the metadata-based module coverage check.
     */
    @NotNull
    static JarMeta scanJar(@NotNull Path jarPath, @Nullable String baseName) {
        if (baseName == null) {
            String n = jarPath.getFileName().toString();
            baseName = n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n;
        }
        Set<String> entryPaths = new HashSet<>();
        Set<String> pomArtifacts = new HashSet<>();
        try (var zf = new ZipFile(jarPath.toFile())) {
            zf.stream().forEach(e -> {
                String name = e.getName();
                entryPaths.add(name);
                // META-INF/maven/<groupId>/<artifactId>/pom.properties — parts[3] = artifactId
                if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    String[] parts = name.split("/");
                    if (parts.length == 5) pomArtifacts.add(parts[3]);
                }
            });
        } catch (IOException e) {
            LOG.debug("JAR scan: could not open '" + jarPath.getFileName() + "': " + e.getMessage());
        }
        return new JarMeta(baseName, entryPaths, pomArtifacts);
    }

    /**
     * Determines whether any pre-scanned JAR in {@code jarIndex} packages the given
     * module's output. Two complementary checks are performed against the in-memory index
     * (no ZipFile I/O at this point — all JAR data was collected by {@link #scanJar}):
     *
     * <ul>
     *   <li><b>Content check</b> — samples up to {@value #CONTENT_SAMPLE_SIZE} file paths
     *       from {@code moduleOutputNativePath} and tests whether any indexed JAR contains
     *       those entries. Covers any build tool regardless of JAR naming convention.</li>
     *   <li><b>Metadata check</b> — tests whether any indexed JAR's {@code pom.properties}
     *       declares {@code artifactName} as its Maven artifactId. Works even when the
     *       module output directory is empty (not yet compiled).</li>
     * </ul>
     *
     * @return the matching JAR's base name, or {@code null} if no JAR covers this module
     */
    @Nullable
    static String findCoveringJar(@NotNull String moduleOutputNativePath,
                                          @Nullable String artifactName,
                                          @NotNull List<JarMeta> jarIndex) {
        if (jarIndex.isEmpty()) return null;

        // Sample file paths from the module output (may be empty if not yet compiled)
        Path outputDir = Paths.get(moduleOutputNativePath);
        List<String> sample = new ArrayList<>();
        try (var walk = Files.walk(outputDir)) {
            walk.filter(Files::isRegularFile)
                .limit(CONTENT_SAMPLE_SIZE)
                .forEach(p -> sample.add(
                        outputDir.relativize(p).toString().replace(File.separatorChar, '/')));
        } catch (IOException e) {
            LOG.debug("JAR scan: could not walk '" + moduleOutputNativePath + "': " + e.getMessage());
        }

        if (sample.isEmpty() && artifactName == null) return null;

        // Pure in-memory lookups — no I/O
        for (JarMeta meta : jarIndex) {
            if (!sample.isEmpty() && sample.stream().anyMatch(meta.entryPaths::contains)) {
                return meta.baseName;
            }
            if (artifactName != null && meta.pomArtifacts.contains(artifactName)) {
                return meta.baseName;
            }
        }
        return null;
    }

    /**
     * Snapshot of all IntelliJ project model data needed for context XML generation.
     *
     * <p>Collected atomically under a single read action in {@link #collectModelSnapshot}.
     * After collection every field is a plain Java object — no IntelliJ model APIs are
     * accessed subsequently, so there are no threading constraints on their use.
     */
    private static final class ArtifactModelSnapshot {
        /** The IntelliJ module that owns the deployed artifact. */
        final Module module;
        /**
         * Maps each dependency module's production output path to its artifact name
         * (Maven artifactId when available, otherwise stripped IntelliJ module name).
         * Keys are plain path strings — no trailing {@code !/} on JAR roots.
         */
        final Map<String, String> outputToArtifactName;
        /**
         * Full recursive classpath of the module (module outputs + library JARs).
         * Paths are plain strings with any trailing {@code !/} already stripped.
         */
        final List<String> rootPaths;

        ArtifactModelSnapshot(@NotNull Module module,
                              @NotNull Map<String, String> outputToArtifactName,
                              @NotNull List<String> rootPaths) {
            this.module = module;
            this.outputToArtifactName = outputToArtifactName;
            this.rootPaths = rootPaths;
        }
    }

    /**
     * Collects all IntelliJ project model data needed to build the context XML extra resources.
     * <strong>Must be called under a read action.</strong>
     *
     * <p>This is the single point of contact with IntelliJ model APIs in the classpath-building
     * pipeline. After this method returns the caller holds only plain Java values and may operate
     * on any thread without further read-action constraints.
     *
     * @return a fully populated snapshot, or {@code null} if no module can be found for the artifact
     */
    @Nullable
    private static ArtifactModelSnapshot collectModelSnapshot(@NotNull DeploymentArtifact artifact,
                                                              @NotNull Project project) {
        Module module = resolveModuleForArtifact(artifact, project);
        if (module == null) return null;

        // Build dependency module output path → artifact name map
        Map<String, String> outputToArtifactName = new HashMap<>();
        collectModuleDependencyNames(module, project, outputToArtifactName, new HashSet<>());

        // Collect full classpath root paths, converting VirtualFile to String while the
        // read action is still held. Trailing !/ on JAR content roots is stripped here
        // so callers always work with clean filesystem-style paths.
        List<String> rootPaths = new ArrayList<>();
        for (VirtualFile root : OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .classes()
                .getRoots()) {
            String path = root.getPath();
            if (path.endsWith("!/")) path = path.substring(0, path.length() - 2);
            rootPaths.add(path);
        }

        return new ArtifactModelSnapshot(module, outputToArtifactName, rootPaths);
    }

    private static void collectModuleDependencyNames(
            @NotNull Module module,
            @NotNull Project project,
            @NotNull Map<String, String> result,
            @NotNull Set<String> visited) {
        if (!visited.add(module.getName())) return;
        for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
            if (!(entry instanceof ModuleOrderEntry)) continue;
            Module dep = ((ModuleOrderEntry) entry).getModule();
            if (dep == null) continue;

            // Resolve the artifact name: Maven artifactId is authoritative; fall back to
            // the IntelliJ module name stripped of any compound project prefix
            // (e.g. "myapp.common" → "common") so it matches the JAR filename in WEB-INF/lib
            // for both Gradle and Maven projects regardless of how IntelliJ names modules.
            String artifactName = getMavenArtifactId(dep, project);
            if (artifactName == null) {
                String moduleName = dep.getName();
                int dot = moduleName.lastIndexOf('.');
                artifactName = dot >= 0 ? moduleName.substring(dot + 1) : moduleName;
            }

            // Use OrderEnumerator (same API as classesRoots in the caller) to get the
            // output paths for this single module — more reliable than CompilerModuleExtension
            // because it returns the actual paths the IDE uses, covering Maven (target/classes),
            // Gradle (build/classes/java/main), and IntelliJ default (out/production/...).
            for (VirtualFile outputRoot : OrderEnumerator.orderEntries(dep)
                    .productionOnly()
                    .withoutSdk()
                    .withoutLibraries()
                    .classes()
                    .getRoots()) {
                result.put(outputRoot.getPath(), artifactName);
            }

            collectModuleDependencyNames(dep, project, result, visited);
        }
    }

    /**
     * Returns the Maven artifactId for the given module, or {@code null} if the Maven
     * plugin is unavailable or the module is not part of a Maven project.
     *
     * <p>Uses reflection so there is no compile-time dependency on the Maven plugin —
     * the method degrades gracefully to {@code null} on Community Edition or Gradle-only
     * projects where {@code MavenProjectsManager} is absent.
     */
    @Nullable
    private static String getMavenArtifactId(@NotNull Module module, @NotNull Project project) {
        try {
            Class<?> managerClass =
                    Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager");
            Object manager = managerClass.getMethod("getInstance", Project.class)
                    .invoke(null, project);
            if (manager == null) return null;
            Object mavenProject = managerClass.getMethod("findProject", Module.class)
                    .invoke(manager, module);
            if (mavenProject == null) return null;
            Object mavenId = mavenProject.getClass().getMethod("getMavenId").invoke(mavenProject);
            if (mavenId == null) return null;
            return (String) mavenId.getClass().getMethod("getArtifactId").invoke(mavenId);
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    /**
     * Finds the IntelliJ Module associated with a deployment artifact.
     * Tries: artifact name match via ArtifactManager, name-based module lookup,
     * path-based matching, and web module fallback.
     *
     * <p><strong>Must be called under a read action</strong> — all accessed APIs
     * (ArtifactManager, ModuleManager, ModuleRootManager) require one.
     * The sole caller is {@link #collectModelSnapshot}, which is always invoked
     * inside {@link TomcatReadActions#compute}.
     */
    @Nullable
    private static Module resolveModuleForArtifact(@NotNull DeploymentArtifact artifact,
                                                   @NotNull Project project) {
        try {
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            String name = artifact.getName();

            // 1. ArtifactManager lookup — works on Ultimate where users configure artifacts.
            //    Wrapped in try/catch so it degrades silently on Community Edition where
            //    the packaging plugin may not be loaded (NoClassDefFoundError).
            try {
                ArtifactManager artifactManager =
                        ArtifactManager.getInstance(project);
                if (artifactManager != null) {
                    for (Artifact a : artifactManager.getArtifacts()) {
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

            // 5. Partial name match — Locale.ROOT keeps 'WebApi' matchable across tr_TR / en_US.
            for (Module m : webModules) {
                String mName = m.getName().toLowerCase(Locale.ROOT);
                String lowerBase = baseName.toLowerCase(Locale.ROOT);
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
