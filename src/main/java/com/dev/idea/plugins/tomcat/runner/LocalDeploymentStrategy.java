package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.update.DeployedClassesSync;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatDeploymentPaths;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
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
 * packaged WARs are copied to {@code webapps/}. The context descriptor may include
 * {@code <PostResources>} entries that wire in library JARs from the module's
 * classpath that aren't already packaged into {@code WEB-INF/lib/} (e.g.
 * transitive dependencies the build forgot to include).
 *
 * <p><b>What this strategy does NOT do (1.2.0 architectural change):</b>
 * earlier versions also injected each project module's class output directory
 * as {@code <PreResources>}, overlaying them on the deployed
 * {@code WEB-INF/classes/} for zero-copy hot reload. That overlay caused
 * duplicate-classpath problems (Liquibase 4.27+ refusing duplicate changelogs,
 * CDI duplicate-bean errors) because the same resource could be reached at two
 * URIs. The overlay was dropped; the WAR module's classes now reach Tomcat via
 * {@code DeployedClassesSync} copying {@code target/classes/} into the deployed
 * {@code WEB-INF/classes/} on every launch and Ctrl+F10. Dependency modules
 * are served from their {@code WEB-INF/lib/} JARs — repackaging required for
 * code changes, same contract as every other Tomcat deployment.
 */
final class LocalDeploymentStrategy implements DeploymentStrategy {

    private static final Logger LOG = Logger.getInstance(LocalDeploymentStrategy.class);

    // --- Tomcat extra resources (context.xml overlay) ---
    // RESOURCE_CLASS_DIR / WEBAPP_MOUNT_CLASSES were used by the now-removed
    // PreResources injection of class directories. PostResources for JARs only
    // needs RESOURCE_CLASS_FILE + WEBAPP_MOUNT_LIB.
    private static final String RESOURCE_CLASS_FILE = "org.apache.catalina.webresources.FileResourceSet";
    private static final String WEBAPP_MOUNT_LIB = "/WEB-INF/lib/";

    // PreResources for class directories used to overlay each module's
    // target/classes onto the deployed WEB-INF/classes. That overlay caused a
    // class of duplicate-classpath problems (Liquibase 4.27+ refusing duplicate
    // changelogs, CDI duplicate-bean errors, etc.) because the same resource
    // could be reached at two different URLs — once via the overlay, once via
    // the deployed copy. The overlay is gone; the WAR module's fresh classes
    // reach Tomcat via DeployedClassesSync copying target/classes →
    // WEB-INF/classes, and dep modules are served from WEB-INF/lib JARs. The
    // constant for the PreResources XML template was removed alongside the
    // injection logic.

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

    // --- IntelliJ + Maven path conventions (single-file scope) ---

    /**
     * Trailing suffix on IntelliJ's {@code VirtualFile.getPath()} for content inside a JAR — e.g.
     * {@code file:///…/foo.jar!/}. Stripped before storing the path as a plain filesystem string.
     */
    private static final String JAR_URL_SUFFIX = "!/";

    /** Maven's in-JAR metadata path prefix; entries beneath this hold {@code pom.properties}. */
    private static final String META_INF_MAVEN_PREFIX = "META-INF/maven/";

    /** Suffix of the Maven {@code pom.properties} entry inside a JAR. */
    private static final String POM_PROPERTIES_SUFFIX = "/pom.properties";

    /**
     * Expected slash-separated segment count of a Maven {@code pom.properties} entry —
     * {@code META-INF/maven/<groupId>/<artifactId>/pom.properties} → 5 segments after split.
     */
    private static final int META_INF_MAVEN_POM_PROPERTIES_SEGMENTS = 5;

    /**
     * Max number of stale-deployment filenames the balloon enumerates before
     * truncating to "and N more". Anything past this would blow the balloon's
     * reading length; the full list is always in the run console.
     */
    private static final int MAX_LISTED_STALE_FILES = 5;

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
                // Collect context stems for every artifact we are about to
                // deploy so the cleanup pass can also remove any leftover
                // webapps/<stem>/ directory at that location (e.g. a previous
                // run's WAR extract that would now conflict with a new WAR
                // copy or a switched-to-exploded descriptor).
                java.util.Set<String> activeContextNames = new java.util.HashSet<>();
                for (Deployment d : configuration.getDeployments()) {
                    if (!d.isValid()) continue;
                    try {
                        activeContextNames.add(ContextPathUtils.resolveContextName(d.getContextPath()));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid path is rejected by the duplicate-context
                        // validator at Apply time; skipping here is safe.
                    }
                }
                List<Path> staleFailures = cleanStaleDeployments(
                        webappsDir, confCatalinaLocalhost, activeContextNames);
                if (!staleFailures.isEmpty()) {
                    // Surface to the user before the imminent atomicWriteString fails with
                    // AccessDeniedException. Naming the files lets them grep for a stale
                    // Tomcat process holding them open — on Windows this is the realistic
                    // root cause of every cleanup-failed path here.
                    String filesList = staleFailures.stream()
                            .limit(MAX_LISTED_STALE_FILES)
                            .map(p -> p.getFileName().toString())
                            .collect(java.util.stream.Collectors.joining(", "));
                    String suffix = staleFailures.size() > MAX_LISTED_STALE_FILES
                            ? filesList + ", and " + (staleFailures.size() - MAX_LISTED_STALE_FILES) + " more"
                            : filesList;
                    String warning = "Stale-deployment cleanup could not delete "
                            + staleFailures.size() + " file(s) in CATALINA_BASE ("
                            + suffix + "). A previous Tomcat process may still be holding "
                            + "them open — stop any orphan Tomcat JVM, then retry. The next "
                            + "write may fail until the lock is released.";
                    if (logger != null) {
                        logger.logServerWarning(warning);
                    }
                    if (!project.isDisposed()) {
                        // Short balloon — file list is in the run console.
                        TomcatNotifier.warning(project,
                                "Stale files locked",
                                staleFailures.size() + " file(s) held by an orphan Tomcat. Stop it and retry.");
                    }
                }
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
                // Pass the configured JRE so the picker selects an ECJ tier
                // that actually loads on it. Without this, swapping in
                // ecj-3.36.0 (compiled for Java 17) on a Java 8 host throws
                // UnsupportedClassVersionError on the first JSP request.
                EcjJarSwapPrompt.show(project, mismatch, params.getJdk());
            }

            // Stale-swap rollback: if a previous swap installed an ECJ JAR
            // that requires a higher JVM than the one this run config is
            // launching with, surface a "Restore Previous ECJ" balloon. This
            // is the recovery path for users who ran the 1.0.10 swap on
            // Tomcat 7 + Java 8 and are now hitting
            // UnsupportedClassVersionError. Detection only fires when both
            // a current ecj-*.jar and a sibling .devtomcat-bak are present;
            // a clean install with no prior swap is a no-op here.
            Path libDir = Paths.get(tomcatInfo.getPath()).resolve("lib");
            com.intellij.openapi.projectRoots.JavaSdkVersion runtimeJvm = null;
            if (params.getJdk() != null) {
                try {
                    runtimeJvm = com.intellij.openapi.projectRoots.JavaSdk.getInstance()
                            .getVersion(params.getJdk());
                } catch (Throwable ignored) {
                    // Defensive — a misconfigured Sdk should not block the launch.
                }
            }
            EcjJarSwapper.StaleSwap stale = EcjJarSwapper.detectStaleSwap(libDir, runtimeJvm);
            EcjJarSwapPrompt.showRestorePromptIfStale(project, stale, runtimeJvm);
        }

        // Tomcat EOL warning. Fired once per IDE session per install so
        // legacy-Tomcat users get a periodic nudge to upgrade without
        // being spammed every launch. Non-blocking; the launch continues.
        TomcatCompatibilityPrompt.showEolWarningOnce(project, tomcatInfo);

        List<Deployment> deployments = configuration.getDeployments();
        int deployedCount = 0;
        for (Deployment deployment : deployments) {
            if (!deployment.isValid()) continue;

            String contextName;
            try {
                contextName = ContextPathUtils.resolveContextName(deployment.getContextPath());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException(e.getMessage());
            }

            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null || !Files.exists(artifactPath)) {
                throw new ExecutionException("Deployment artifact not found: "
                        + (artifactPath != null ? artifactPath : deployment.getDisplayName()));
            }

            try {
                if (deployment.isExploded() || Files.isDirectory(artifactPath)) {
                    String contextXml = buildContextXml(deployment, artifactPath, preserveSessions,
                            project, configuration.getTomcatInfo(), logger);
                    Path contextFile = TomcatDeploymentPaths.contextDescriptor(
                            confCatalinaLocalhost, contextName);
                    TomcatProjectUtils.atomicWriteString(contextFile, contextXml);
                    LOG.info("Deployed exploded artifact via context.xml: " + contextFile);
                } else {
                    Path targetWar = TomcatDeploymentPaths.warFile(webappsDir, contextName);
                    TomcatProjectUtils.atomicCopy(artifactPath, targetWar);
                    LOG.info("Deployed WAR artifact: " + targetWar);
                }
                deployedCount++;
            } catch (IOException e) {
                throw new ExecutionException("Failed to deploy artifact: " + artifactPath, e);
            }
        }

        // Warn when zero artifacts were actually deployed. Tomcat will still
        // start successfully — it will just serve whatever ROOT context happens
        // to live in CATALINA_HOME/webapps/ (if anything). The user almost
        // certainly intended to deploy something; surfacing the silent
        // misconfiguration here saves a confused trip back to the run-config
        // editor after seeing a blank welcome page.
        if (deployedCount == 0) {
            String configured = deployments.isEmpty()
                    ? "no artifacts are configured"
                    : "configured artifacts were all skipped as invalid";
            String warning = "Tomcat will start but " + configured
                    + " — nothing will be deployed. Add an artifact in the Deployment tab "
                    + "(or fix the invalid entries) to serve your webapp.";
            if (logger != null) {
                logger.logServerWarning(warning);
            }
            if (project != null && !project.isDisposed()) {
                // Short balloon — full prose explanation already in the console.
                TomcatNotifier.warning(project,
                        "No artifacts to deploy",
                        "Tomcat will start with nothing served. Add an artifact in Deployment.");
            }
        }
    }

    @NotNull
    static String buildContextXml(@NotNull Deployment deployment,
                                  @NotNull Path artifactPath,
                                  boolean preserveSessions,
                                  @NotNull Project project,
                                  @Nullable TomcatInfo tomcatInfo,
                                  @Nullable TomcatDeploymentLogger logger) {
        String extraResources = buildExtraResourcesXml(deployment, artifactPath, project, tomcatInfo, logger);
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
        // Emit <Resources allowLinking="true"> for an exploded artifact whenever
        // Tomcat supports the element (8+). Tomcat 8+ disables symlink traversal
        // by default (CVE-2014-0033 hardening), so a docBase that happens to BE
        // a symlink — or that contains symlinked subdirectories — fails to
        // deploy without this attribute. The Maven multi-module shape
        // (target/<module>/ resolved through a symlinked staging dir) is the
        // realistic hit. Previously the Resources block was emitted only when
        // extra PostResources were attached, so users with no extra resources
        // lost symlink support silently. The empty-children case is well-formed
        // and harmless to Tomcat 8+.
        //
        // Tomcat 7 does NOT support <Resources> under <Context> (its Digester
        // logs 'No rules found matching Context/Resources/PostResources' and
        // drops the element). On 7, allowLinking defaults to true on the
        // Context itself so symlinks work without explicit configuration —
        // omit the block entirely. The major-version=0 (unknown) case is
        // treated as modern to avoid regressing the realistic 8+ path.
        boolean tomcatSupportsResources = tomcatInfo == null
                || tomcatInfo.getMajorVersion() == 0
                || tomcatInfo.getMajorVersion() >= 8;
        if (tomcatSupportsResources) {
            xml.append("\n  <Resources allowLinking=\"true\">");
            if (!extraResources.isEmpty()) {
                xml.append(extraResources);
            }
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
            stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
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
        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) {
                // Packaged WARs are scanned by Tomcat after extraction; we do
                // not pre-extract here. Keep the scope narrow: exploded only.
                continue;
            }
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;
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
        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) continue;
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;
            Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
            if (!Files.isDirectory(webInfLib)) continue;
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
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
        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) continue;
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;
            Path webInf = artifactPath.resolve(WEB_INF);
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

    /**
     * Removes every stale {@code .xml} descriptor from {@code conf/Catalina/localhost}
     * and every stale {@code .war} from {@code webapps/} so the new launch starts
     * from a clean slate. Returns the list of paths that could NOT be deleted —
     * empty on the happy path.
     *
     * <p>The realistic failure case is Windows: a previous Tomcat JVM (orphaned
     * by an IDE force-quit or a hung shutdown) still holds the file open, and
     * Windows refuses to unlink open files. The caller surfaces this list to the
     * user as a balloon so they can stop the stale process before the next
     * launch crashes with a cryptic {@code AccessDeniedException} when
     * {@link com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils#atomicWriteString}
     * tries to overwrite the locked file. On Linux / macOS this list will be
     * empty: {@code unlink()} succeeds even when the file is open; the stale
     * process keeps reading from the now-anonymous inode.
     *
     * <p>Per-file delete failures are logged at WARN (was DEBUG) because they
     * are now actionable — the user has a balloon prompting them to act.
     * Directory-listing failures stay at WARN as well; previously DEBUG was
     * silent enough that a corrupt or unreadable conf dir went unnoticed.
     */
    @NotNull
    static List<Path> cleanStaleDeployments(@NotNull Path webappsDir, @NotNull Path confDir) {
        return cleanStaleDeployments(webappsDir, confDir, java.util.Set.of());
    }

    /**
     * Same as {@link #cleanStaleDeployments(Path, Path)}, plus an extra pass
     * that removes leftover {@code webapps/<contextName>/} directories for
     * every context name about to be deployed in this launch.
     *
     * <p><b>Why.</b> The plain {@code .war}-suffix cleanup pass leaves
     * extracted webapp directories behind. Two scenarios produce them:
     * <ol>
     *   <li>A previous run deployed {@code myapp.war}; Tomcat extracted it
     *       to {@code webapps/myapp/}. We then deleted the {@code .war} on
     *       the next launch but the extracted directory persists.</li>
     *   <li>The user changed an artifact's type from WAR to exploded — the
     *       new launch writes a context.xml descriptor pointing at
     *       {@code out/artifacts/...} but Tomcat also sees the old
     *       {@code webapps/myapp/} and gets an ambiguous double-deploy.</li>
     * </ol>
     * Either way the stale directory at the context name we are about to
     * deploy conflicts with the new deploy. The fix is targeted: we only
     * remove directories whose name matches a currently-deploying context.
     * Bundled apps mirrored by {@link CatalinaHomeMirror} (ROOT, manager,
     * host-manager, docs, examples) are left alone unless the user has
     * deliberately reserved that context for one of their own artifacts —
     * the mirror also skips reserved stems, so the replacement is intended.
     *
     * @param activeContextNames context stems (e.g. "ROOT", "myapp",
     *                           "foo#bar") for every artifact the current
     *                           launch will deploy. Pass an empty set to
     *                           preserve the legacy files-only behaviour.
     */
    @NotNull
    static List<Path> cleanStaleDeployments(@NotNull Path webappsDir,
                                            @NotNull Path confDir,
                                            @NotNull java.util.Set<String> activeContextNames) {
        List<Path> failures = new ArrayList<>();
        deleteEndingWith(confDir, EXT_XML, failures);
        deleteEndingWith(webappsDir, EXT_WAR, failures);
        for (String contextName : activeContextNames) {
            if (contextName == null || contextName.isBlank()) continue;
            Path leftover = TomcatDeploymentPaths.extractedDirectory(webappsDir, contextName);
            if (Files.isDirectory(leftover)) {
                try {
                    deleteRecursively(leftover);
                    LOG.info("Stale-deployment cleanup removed leftover directory: " + leftover);
                } catch (IOException e) {
                    LOG.warn("Stale-deployment cleanup could not delete leftover directory "
                            + leftover + ": " + e.getMessage());
                    failures.add(leftover);
                }
            }
        }
        return failures;
    }

    /**
     * Recursive {@code rm -rf} for a single directory. Uses {@link Files#walkFileTree}
     * with delete-on-exit semantics so Windows file locks bubble as IOException
     * (caller adds the path to the cleanup-failures list for the balloon).
     */
    private static void deleteRecursively(@NotNull Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    @NotNull Path file,
                    @NotNull java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(
                    @NotNull Path d, java.io.IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteEndingWith(@NotNull Path dir, @NotNull String suffix,
                                 @NotNull List<Path> failures) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(suffix))
                  .forEach(p -> {
                      try {
                          Files.deleteIfExists(p);
                      } catch (IOException e) {
                          LOG.warn("Stale-deployment cleanup could not delete " + p + ": " + e.getMessage());
                          failures.add(p);
                      }
                  });
        } catch (IOException e) {
            LOG.warn("Could not list directory for stale-deployment cleanup: " + dir, e);
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
     * Builds {@code <PostResources>} entries for an exploded artifact's context
     * XML, listing library JARs from the module's full classpath that are not
     * already packaged into the artifact's {@code WEB-INF/lib/}. Container-
     * provided JARs (Tomcat internals, Servlet/JSP/EL APIs) are excluded so
     * they don't fight Tomcat's own loaders.
     *
     * <h2>What this method does NOT do (architectural note for 1.2.0)</h2>
     *
     * <p>Previous versions also emitted {@code <PreResources>} entries for
     * project module class output directories ({@code target/classes/},
     * {@code out/production/<module>/}, etc.). The intent was to overlay
     * freshly compiled classes on top of the deployed {@code WEB-INF/classes/}
     * for zero-copy hot reload. But the overlay created a class of duplicate-
     * classpath problems — Liquibase 4.27+ refusing to load a changelog
     * reachable at two URIs, CDI duplicate-bean detection, Spring component-
     * scan double-registration — because the same resource could be reached
     * once via the overlay and once via the deployed copy. Each new strict-
     * classpath library would have required another defensive guard.
     *
     * <p>The overlay was dropped. Today:
     * <ul>
     *   <li>The WAR module's fresh classes reach Tomcat via
     *       {@link com.dev.idea.plugins.tomcat.update.DeployedClassesSync},
     *       which copies {@code target/classes/} → the deployed
     *       {@code WEB-INF/classes/} on every launch and every Ctrl+F10.
     *       The deployed location is the sole source of truth.</li>
     *   <li>Dependency modules' classes are served from their JARs in
     *       {@code WEB-INF/lib/}. Editing a dep module's class requires
     *       repackaging that module's JAR (typically {@code mvn install}).
     *       This matches the contract of every other Tomcat deployment.</li>
     *   <li>When a dep module's JAR is missing from {@code WEB-INF/lib/}
     *       (the case the old overlay silently rescued), we emit a clear
     *       pre-launch warning naming the module so the user can fix the
     *       build rather than running on a misconfigured classpath.</li>
     * </ul>
     *
     * <p>The matching of dep modules to JARs (used both to suppress
     * redundant PostResources entries and to identify "missing JAR"
     * modules for the warning) uses the same two-guard approach as before:
     * name-based and content+pom.properties.
     */
    @NotNull
    private static String buildExtraResourcesXml(@NotNull Deployment deployment,
                                          @NotNull Path artifactPath,
                                          @NotNull Project project,
                                          @Nullable TomcatInfo tomcatInfo,
                                          @Nullable TomcatDeploymentLogger logger) {
        // PostResources is Tomcat 8+; Tomcat 7's Digester emits
        // 'No rules found matching Context/Resources/PostResources' and drops them.
        // Major version 0 = unknown — treat as modern (don't accidentally regress modern users).
        if (tomcatInfo != null
                && tomcatInfo.getMajorVersion() > 0
                && tomcatInfo.getMajorVersion() < 8) {
            if (logger != null) {
                logger.logServerInfo(
                        "Tomcat " + tomcatInfo.getMajorVersion()
                                + " does not support <PostResources> (added in Tomcat 8). "
                                + "Extra library JARs for '" + deployment.getDisplayName()
                                + "' will not be wired in. Package any required JARs into "
                                + "WEB-INF/lib if your application depends on them.");
            }
            return "";
        }

        // Phase 1, Model access: collect all IntelliJ project model data under a single
        // read action so the snapshot is internally consistent. After this call every value
        // is a plain Java object (Module reference + String maps/lists); no further model
        // access is needed and no threading constraint applies to the rest of this method.
        ArtifactModelSnapshot snapshot = TomcatReadActions.compute(
                () -> collectModelSnapshot(deployment, project));
        if (snapshot == null) {
            LOG.info("No module found for '" + deployment.getDisplayName() + "', skipping extra classpath");
            return "";
        }

        // Phase 2 — File I/O: scan WEB-INF/lib once to build the JAR name index
        // (used for both the dep-JAR-already-packaged check and the missing-JAR detection)
        // and the pre-scanned JarMeta index (content + pom.properties matching).
        // No model access — pure filesystem I/O.
        Set<String> existingLibJars = new HashSet<>();
        Set<String> coveredModuleNames = new HashSet<>();
        List<JarMeta> jarIndex = new ArrayList<>();
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (Files.isDirectory(webInfLib)) {
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
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

        // Normalize artifact path for cross-platform comparison.
        String artifactAbsPath = artifactPath.toAbsolutePath().toString().replace('\\', '/');

        List<String> extraJars = new ArrayList<>();
        // Dep modules whose classes have no JAR backing in WEB-INF/lib. Without
        // the old PreResources overlay these modules' classes won't be visible
        // to Tomcat at runtime — surface a warning naming them so the user can
        // fix the build (typically `mvn install` on the missing module).
        List<String> missingDepJars = new ArrayList<>();

        for (String rootPath : snapshot.rootPaths) {
            // Skip entries already under the artifact's docBase (the WAR's
            // own WEB-INF/classes and WEB-INF/lib end up here via the
            // recursive classpath walk).
            if (rootPath.startsWith(artifactAbsPath)) {
                continue;
            }

            String nativePath = rootPath.replace('/', File.separatorChar);
            File file = new File(nativePath);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                // Class output directory. We no longer inject these as PreResources.
                // Two cases worth telling the user about:
                //   - It's a dep module's classes AND no JAR backs them → warn (build is missing this module's package).
                //   - It's the WAR module's own classes → handled by DeployedClassesSync, no action.
                //   - It's something else (rare) → silent skip.
                String moduleDirName = snapshot.outputToArtifactName.get(rootPath);
                if (moduleDirName == null) {
                    // Not a known dep module. Could be the WAR module's own
                    // target/classes (sync handles it) or an unrelated dir
                    // that IntelliJ surfaced via OrderEnumerator. Either way
                    // no PostResources work to do here.
                    continue;
                }
                // Dep module: is its JAR in WEB-INF/lib?
                if (coveredModuleNames.contains(moduleDirName.toLowerCase(Locale.ROOT))) {
                    continue; // name-matched
                }
                if (findCoveringJar(nativePath, moduleDirName, jarIndex) != null) {
                    continue; // content + pom.properties matched
                }
                // Genuinely missing — the user's build didn't package this dep.
                missingDepJars.add(moduleDirName);
            } else if (rootPath.endsWith(EXT_JAR)) {
                String jarName = file.getName();
                if (isContainerProvidedJar(jarName)) continue;
                if (existingLibJars.contains(jarName)) continue;
                extraJars.add(nativePath);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String jar : extraJars) {
            String jarName = new File(jar).getName();
            sb.append(String.format(POST_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_FILE, escapeXmlAttribute(jar),
                    WEBAPP_MOUNT_LIB + escapeXmlAttribute(jarName)));
        }

        if (!extraJars.isEmpty()) {
            LOG.info("Added " + extraJars.size() + " library JAR(s) as PostResources for '"
                    + deployment.getDisplayName() + "'");
        }

        if (!missingDepJars.isEmpty() && logger != null) {
            // Single consolidated warning, names every offender so the user can
            // act on the whole list at once.
            logger.logServerWarning(
                    "Dependency module(s) " + missingDepJars + " are on the project classpath "
                    + "but no matching JAR is packaged in WEB-INF/lib/. Tomcat will not find their "
                    + "classes at runtime. Run 'mvn install' on the missing module(s) (or check "
                    + "your build's <war>/<packagingIncludes>/<finalName> setup) so the JAR lands "
                    + "in the deployed WAR. Previously DevTomcat silently overlaid these modules' "
                    + "target/classes onto the classpath via <PreResources>, which masked the broken "
                    + "build but caused classpath duplicates with strict libraries (Liquibase 4.27+, "
                    + "CDI, etc.).");
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
        if (!jarName.endsWith(EXT_JAR)) return null;
        String base = jarName.substring(0, jarName.length() - EXT_JAR.length());
        // Remove -<version> suffix: version starts with a digit (1.2.3) or is a bare SNAPSHOT
        return base.replaceAll("-(\\d+.*|SNAPSHOT)$", "");
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
            baseName = n.endsWith(EXT_JAR) ? n.substring(0, n.length() - EXT_JAR.length()) : n;
        }
        Set<String> entryPaths = new HashSet<>();
        Set<String> pomArtifacts = new HashSet<>();
        try (var zf = new ZipFile(jarPath.toFile())) {
            zf.stream().forEach(e -> {
                String name = e.getName();
                entryPaths.add(name);
                // META-INF/maven/<groupId>/<artifactId>/pom.properties — parts[3] = artifactId
                if (name.startsWith(META_INF_MAVEN_PREFIX) && name.endsWith(POM_PROPERTIES_SUFFIX)) {
                    String[] parts = name.split("/");
                    if (parts.length == META_INF_MAVEN_POM_PROPERTIES_SEGMENTS) {
                        pomArtifacts.add(parts[3]);
                    }
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
     * @return a fully populated snapshot, or {@code null} if no module can be resolved
     */
    @Nullable
    private static ArtifactModelSnapshot collectModelSnapshot(@NotNull Deployment deployment,
                                                              @NotNull Project project) {
        Module module = resolveModuleForDeployment(deployment, project);
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
            if (path.endsWith(JAR_URL_SUFFIX)) {
                path = path.substring(0, path.length() - JAR_URL_SUFFIX.length());
            }
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
        Object mavenProject = com.dev.idea.plugins.tomcat.utils.MavenReflection
                .findMavenProject(module, project);
        if (mavenProject == null) return null;
        try {
            Object mavenId = mavenProject.getClass().getMethod("getMavenId").invoke(mavenProject);
            if (mavenId == null) return null;
            return (String) mavenId.getClass().getMethod("getArtifactId").invoke(mavenId);
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    /**
     * Resolves the owning IntelliJ Module via the typed {@link Deployment}
     * hierarchy — no string-matching anywhere.
     * {@link ArtifactBackedDeployment} walks the artifact's packaging tree
     * for its first {@code ModulePackagingElement};
     * {@link ModuleBackedDeployment} returns its pointer's module directly;
     * external deployments have no project module to resolve.
     *
     * <p><strong>Must be called under a read action.</strong> The sole caller
     * is {@link #collectModelSnapshot}, which is always invoked inside
     * {@link TomcatReadActions#compute}.
     */
    @Nullable
    private static Module resolveModuleForDeployment(@NotNull Deployment deployment,
                                                     @NotNull Project project) {
        try {
            if (deployment instanceof ArtifactBackedDeployment a) {
                Artifact artifact = a.getArtifactPointer().getArtifact();
                if (artifact == null) return null;
                ArtifactManager mgr;
                try {
                    mgr = ArtifactManager.getInstance(project);
                } catch (NoClassDefFoundError | Exception ignored) {
                    return null;
                }
                if (mgr == null) return null;
                return DeployedClassesSync.walkPackagingTreeForModule(
                        artifact.getRootElement(), mgr.getResolvingContext());
            }
            if (deployment instanceof ModuleBackedDeployment m) {
                return m.getModule();
            }
            return null; // ExternalFileDeployment — no project module
        } catch (Exception e) {
            LOG.warn("Failed to resolve module for '" + deployment.getDisplayName()
                    + "': " + e.getMessage());
            return null;
        }
    }
}
