package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.update.DeploymentModuleResolver;
import com.dev.idea.plugins.tomcat.update.WebResourcesSync;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.TomcatDeploymentPaths;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Local deployment strategy: deploys artifacts to the CATALINA_BASE filesystem.
 *
 * <p>Exploded artifacts get a context XML descriptor in
 * {@code conf/Catalina/localhost/}; packaged WARs are copied to
 * {@code webapps/}. For exploded artifacts the context descriptor overlays two
 * kinds of source onto Tomcat's webapp classloader:
 *
 * <ul>
 *   <li>Each webapp source directory is mounted at the web-app root
 *       {@code /} via {@code <PreResources>}, so an edited JSP or static
 *       resource is served straight from source — the physical mirror into
 *       the deployed artifact stays in place as a fallback.</li>
 *   <li>Each compile- or runtime-scope library JAR not already in
 *       {@code WEB-INF/lib/} is mounted there via {@code <PostResources>}, so
 *       transitive dependencies the build didn't package are still visible.
 *       Scope is restricted to exactly what the build packages into
 *       {@code WEB-INF/lib/} (compile + runtime); test- and provided-scope
 *       libraries are excluded so the overlay never adds a library the
 *       deployed artifact does not itself contain.</li>
 * </ul>
 *
 * <p><b>Class output directories are deliberately NOT overlaid.</b> Mounting a
 * module's {@code target/classes/} (or {@code out/production/<module>/}) at
 * {@code /WEB-INF/classes} via {@code <PreResources>} would make every resource
 * it contains reachable at two classpath URIs — once through the overlay and
 * once through the copy in the deployed {@code WEB-INF/classes/}. A library
 * that enumerates a resource by name and expects a single hit then fails
 * ("found N files with the same path"). The deployed {@code WEB-INF/classes/}
 * is the single source of truth for compiled output: {@code DeployedClassesSync}
 * keeps it fresh on every launch and Update, copying the web module's own
 * output in full and dependency modules' {@code .class} files only — a
 * dependency's resources already ship inside its {@code WEB-INF/lib/} JAR, so
 * copying them would duplicate them onto the classpath.
 *
 * <p>Effect: when the user edits a webapp resource Tomcat serves it from source
 * on the next request; when the user recompiles, {@code DeployedClassesSync}
 * refreshes the deployed {@code WEB-INF/classes/}. The Update action's "Update
 * classes and resources" path triggers a context reload (touch context.xml) so
 * any cached references are dropped and the next lookup hits the fresh bytes.
 */
final class LocalDeploymentStrategy implements DeploymentStrategy {

    private static final Logger LOG = Logger.getInstance(LocalDeploymentStrategy.class);

    // --- Tomcat extra resources (context.xml overlay) ---
    private static final String RESOURCE_CLASS_DIR = "org.apache.catalina.webresources.DirResourceSet";
    private static final String RESOURCE_CLASS_FILE = "org.apache.catalina.webresources.FileResourceSet";
    private static final String WEBAPP_MOUNT_LIB = "/WEB-INF/lib/";
    // Webapp source directories mount at the web-app root so static files,
    // JSPs, and descriptors are served straight from source.
    private static final String WEBAPP_MOUNT_ROOT = "/";

    // Webapp source directories mount at the web-app root via PreResources so
    // an edited JSP or static file is served from source ahead of the deployed
    // copy. Class output directories are intentionally never mounted via this
    // template — the deployed WEB-INF/classes/ is their single source of truth
    // (see the class javadoc), so there is no /WEB-INF/classes overlay.
    private static final String PRE_RESOURCE_TEMPLATE =
            "\n    <PreResources className=\"%s\"\n                   base=\"%s\" webAppMount=\"%s\" />";

    // JAR files mount at /WEB-INF/lib/<filename> via PostResources — they
    // extend WEB-INF/lib with entries not already packaged in the deployed
    // artifact, so there is no shadowing conflict with docBase content.
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
                    // Pre-launch classpath-duplicate scan. Surfaces files that
                    // appear at the same logical path in WEB-INF/classes/ AND
                    // a WEB-INF/lib/ JAR (or in 2+ JARs), filtering universally-
                    // benign cases. The warning is non-blocking and library-
                    // agnostic — describes what the user's build packaged, not
                    // what any specific framework will do about it.
                    warnAboutClasspathDuplicates(deployment, artifactPath, logger);
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

    /**
     * Runs {@link com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner}
     * against the deployed exploded artifact and, if any duplicates are
     * found, surfaces a consolidated console warning naming each duplicate
     * and the locations it appears in.
     *
     * <p>The scan is library-agnostic and the warning is non-blocking. The
     * remedies named in the message are generic — fix the build to package
     * each resource once, or configure whichever framework is auditing the
     * classpath to tolerate duplicates. We don't pattern-match for any
     * specific framework.
     */
    private static void warnAboutClasspathDuplicates(@NotNull Deployment deployment,
                                                     @NotNull Path artifactPath,
                                                     @Nullable TomcatDeploymentLogger logger) {
        if (logger == null) return;
        List<com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner.DuplicateGroup> duplicates;
        try {
            duplicates = com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner.scan(artifactPath);
        } catch (Exception e) {
            // Defensive: the scanner already handles per-JAR I/O failures
            // internally, but a top-level exception (disk gone, etc.) should
            // not block deploy. The launch itself will surface the real
            // problem if one exists.
            LOG.debug("Classpath duplicate scan failed for "
                    + deployment.getDisplayName() + ": " + e.getMessage());
            return;
        }
        if (duplicates.isEmpty()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("Classpath duplicates in deployed artifact '")
           .append(deployment.getDisplayName())
           .append("' — ")
           .append(duplicates.size())
           .append(duplicates.size() == 1 ? " path appears" : " paths appear")
           .append(" in multiple locations:");
        for (var group : duplicates) {
            msg.append("\n  - ").append(group.logicalPath()).append("  →  ");
            msg.append(String.join(" , ", group.locations()));
        }
        msg.append("\nFirst match wins in classloader resolution; frameworks that enumerate")
           .append(" all instances of a resource (strict-classpath audits) may refuse to start.")
           .append(" To fix: update your build so each resource is packaged in only one")
           .append(" location, or — if the duplication is intentional — configure the")
           .append(" framework that's auditing the classpath to tolerate duplicates.");

        logger.logServerWarning(msg.toString());
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
        // extra Pre/PostResources were attached, so users with no extras lost
        // symlink support silently. The empty-children case is well-formed
        // and harmless to Tomcat 8+.
        //
        // Tomcat 7 does NOT support <Resources> under <Context> (its Digester
        // logs 'No rules found matching Context/Resources/PreResources' and
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

        Set<String> containerLibKeys = resolveContainerLibKeys(tomcatInfo);
        // LinkedHashSet for deterministic order in the generated XML.
        java.util.LinkedHashSet<String> skip = new java.util.LinkedHashSet<>();
        try (var stream = Files.list(webInfLib)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
                  .forEach(p -> {
                      String jarName = p.getFileName().toString();
                      if (isContainerProvidedJar(jarName, containerLibKeys)) {
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
        Set<String> containerLibKeys = resolveContainerLibKeys(configuration.getTomcatInfo());
        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) continue;
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;
            Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
            if (!Files.isDirectory(webInfLib)) continue;
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
                        .map(p -> p.getFileName().toString())
                        .filter(name -> isContainerProvidedJar(name, containerLibKeys))
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
     * Builds extra resource entries for an exploded artifact's context XML:
     *
     * <ul>
     *   <li>Webapp source directories (WebFacet roots, convention dirs,
     *       declared build-time web-resource dirs) are mounted at the web-app
     *       root {@code /} via {@code <PreResources>}. Because PreResources are
     *       searched ahead of docBase, an edited JSP or static file is served
     *       from source on the next request — zero-copy hot reload of webapp
     *       resources, layered on top of the physical mirror that still keeps
     *       the deployed artifact self-contained.</li>
     *   <li>Library JARs from the production-runtime classpath (compile +
     *       runtime scope) that are not already packaged in
     *       {@code WEB-INF/lib/} are mounted there via {@code <PostResources>}.
     *       Lets the deployed app see transitive deps the build didn't include,
     *       while test- and provided-scope libraries — which the build never
     *       packages — stay off the webapp classloader.</li>
     * </ul>
     *
     * <p>Class output directories are intentionally excluded: the deployed
     * {@code WEB-INF/classes/} (kept fresh by {@code DeployedClassesSync}) is
     * their single source of truth. Overlaying them here too would expose every
     * resource at two classpath URIs and break strict-classpath libraries — see
     * the class javadoc.
     *
     * <p>Container-provided JARs (Tomcat internals, Servlet/JSP/EL APIs) are
     * filtered out of the JAR list so they don't fight Tomcat's own loaders
     * for the same classes.
     *
     * <p>Classpath roots that fall under the artifact's docBase are skipped —
     * the recursive classpath walk includes the deployed
     * {@code WEB-INF/classes/} and {@code WEB-INF/lib/} contents, and
     * re-mounting them would be redundant.
     */
    @NotNull
    private static String buildExtraResourcesXml(@NotNull Deployment deployment,
                                          @NotNull Path artifactPath,
                                          @NotNull Project project,
                                          @Nullable TomcatInfo tomcatInfo,
                                          @Nullable TomcatDeploymentLogger logger) {
        // PreResources / PostResources are Tomcat 8+; Tomcat 7's Digester emits
        // 'No rules found matching Context/Resources/PreResources' and drops them.
        // Major version 0 = unknown — treat as modern (don't accidentally regress modern users).
        if (tomcatInfo != null
                && tomcatInfo.getMajorVersion() > 0
                && tomcatInfo.getMajorVersion() < 8) {
            if (logger != null) {
                logger.logServerInfo(
                        "Tomcat " + tomcatInfo.getMajorVersion()
                                + " does not support <PreResources>/<PostResources> "
                                + "(added in Tomcat 8). Module classpath additions for '"
                                + deployment.getDisplayName()
                                + "' will not be wired in. Package any required JARs "
                                + "into WEB-INF/lib if your application depends on them.");
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

        // Phase 2 — File I/O: scan WEB-INF/lib once so we don't re-mount a JAR
        // already packaged into the artifact. WEB-INF/lib is authoritative for
        // libraries: we key by artifactId stem (version stripped), NOT exact
        // filename, so a *different version* of an already-deployed library is
        // never injected too. Tomcat searches <PostResources> after docBase, so
        // the deployed JAR wins for class loading — but resource ENUMERATION
        // (getResources) returns every copy regardless of search order, so a
        // second version on the classpath duplicates every resource path it
        // shares with the deployed one. That is exactly the failure strict-
        // classpath libraries reject ("found N files with the same path").
        Set<String> deployedLibArtifacts = new HashSet<>();
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (Files.isDirectory(webInfLib)) {
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
                      .forEach(p -> deployedLibArtifacts.add(
                              LibraryArtifactNames.libraryArtifactKey(p.getFileName().toString())));
            } catch (IOException e) {
                LOG.debug("Could not list WEB-INF/lib: " + e.getMessage());
            }
        }

        // Phase 3 — Processing.
        // Normalize artifact path for cross-platform comparison; entries
        // already under docBase are dropped (the deployed WEB-INF/classes
        // and WEB-INF/lib show up here via the recursive classpath walk
        // and re-mounting them would be redundant).
        String artifactAbsPath = artifactPath.toAbsolutePath().toString().replace('\\', '/');
        // Authoritative container-provided set from the configured Tomcat's lib/.
        Set<String> containerLibKeys = resolveContainerLibKeys(tomcatInfo);

        List<String> extraJars = new ArrayList<>();

        for (String rootPath : snapshot.rootPaths) {
            if (rootPath.startsWith(artifactAbsPath)) {
                continue;
            }
            // Class output directories are NOT overlaid here. The deployed
            // WEB-INF/classes/ is their single source of truth, kept fresh by
            // DeployedClassesSync. Mounting them again via <PreResources> would
            // expose every resource at two classpath URIs and break strict-
            // classpath libraries (see the class javadoc). Only library JARs
            // extend the classpath, via <PostResources> at /WEB-INF/lib.
            if (!rootPath.endsWith(EXT_JAR)) {
                continue;
            }
            String nativePath = rootPath.replace('/', File.separatorChar);
            File file = new File(nativePath);
            if (!file.isFile()) continue;
            String jarName = file.getName();
            if (isContainerProvidedJar(jarName, containerLibKeys)) continue;
            if (deployedLibArtifacts.contains(LibraryArtifactNames.libraryArtifactKey(jarName))) continue;
            extraJars.add(nativePath);
        }

        // Webapp source directories overlay the deployed docBase at the web-app
        // root. Because PreResources are searched ahead of docBase, an edited
        // JSP or static file is served from source on the next request without
        // re-copying into the exploded artifact. Same docBase-containment skip
        // as the classpath roots: a source dir that already lives under docBase
        // would re-mount the deployed copy onto itself.
        List<String> webappDirs = new ArrayList<>();
        for (String rootPath : snapshot.webappSourceRoots) {
            if (rootPath.startsWith(artifactAbsPath)) {
                continue;
            }
            String nativePath = rootPath.replace('/', File.separatorChar);
            File file = new File(nativePath);
            if (file.isDirectory()) {
                webappDirs.add(nativePath);
            }
        }

        if (extraJars.isEmpty() && webappDirs.isEmpty()) {
            return "";
        }

        LOG.info("Mounted " + webappDirs.size() + " webapp source dir(s) and "
                + extraJars.size() + " JAR(s) for '" + deployment.getDisplayName() + "'");

        return renderExtraResourcesXml(webappDirs, extraJars);
    }

    /**
     * Pure emission of the {@code <Resources>} children from already-resolved
     * native path strings. No project-model or filesystem access — split out
     * from {@link #buildExtraResourcesXml} so the XML shape and resource
     * ordering can be verified in isolation.
     *
     * <p>Emission order is deliberate:
     * <ol>
     *   <li>Webapp source dirs → {@code <PreResources>} at the web-app root
     *       {@code /} (searched before docBase, so an edited JSP or static
     *       file is served from source rather than the deployed copy).</li>
     *   <li>Library JARs → {@code <PostResources>} at
     *       {@code /WEB-INF/lib/<name>} (searched after docBase, so they only
     *       extend — never shadow — the packaged libraries).</li>
     * </ol>
     *
     * <p>Class output directories are never emitted: the deployed
     * {@code WEB-INF/classes/} is their single source of truth (see the class
     * javadoc), so this emitter produces no {@code /WEB-INF/classes} mount.
     */
    @NotNull
    static String renderExtraResourcesXml(@NotNull List<String> webappDirs,
                                          @NotNull List<String> libJars) {
        StringBuilder sb = new StringBuilder();
        for (String webappDir : webappDirs) {
            sb.append(String.format(PRE_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_DIR, escapeXmlAttribute(webappDir), WEBAPP_MOUNT_ROOT));
        }
        for (String jar : libJars) {
            String jarName = new File(jar).getName();
            sb.append(String.format(POST_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_FILE, escapeXmlAttribute(jar),
                    WEBAPP_MOUNT_LIB + escapeXmlAttribute(jarName)));
        }
        return sb.toString();
    }

    /**
     * Whether {@code jarName} is provided by the target container and must not be
     * injected into the webapp classpath (it would duplicate classes / web
     * fragments the container's own loader already provides).
     *
     * <p>Authoritative signal first: a match by version-independent artifact key
     * against {@code containerLibKeys} — the JARs the configured Tomcat actually
     * ships in {@code lib/} (and {@code bin/}). This tracks the real install and
     * auto-covers JARs the static list below never anticipated (a renamed core
     * JAR, a Tomcat fork, a future release).
     *
     * <p>Union'd with the static {@link #CONTAINER_PROVIDED_JAR_PREFIXES}, which
     * is still required for the spec API JARs: a webapp pulls them under Maven
     * coordinates ({@code jakarta.servlet-api}, {@code javax.servlet-api}) while
     * Tomcat ships them under bare names ({@code servlet-api.jar}), so their
     * artifact keys don't match and the lib-key signal alone would miss them. The
     * prefix list is also the sole fallback when the Tomcat home is unknown or
     * unreadable (empty {@code containerLibKeys}).
     */
    static boolean isContainerProvidedJar(@NotNull String jarName,
                                          @NotNull Set<String> containerLibKeys) {
        if (!containerLibKeys.isEmpty()
                && containerLibKeys.contains(LibraryArtifactNames.libraryArtifactKey(jarName))) {
            return true;
        }
        return isContainerProvidedJar(jarName);
    }

    /**
     * Static-prefix fallback for {@link #isContainerProvidedJar(String, Set)} —
     * used when the configured Tomcat's {@code lib/} set is unavailable, and
     * (because of the Maven-vs-Tomcat naming variance noted there) always
     * consulted for the spec API JARs. Package-private for direct unit testing of
     * the prefix coverage.
     */
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
     * Version-independent artifact keys for every JAR the configured Tomcat ships
     * in {@code lib/} and {@code bin/} — the authoritative set of container-provided
     * libraries for this install. Empty when the Tomcat home is unknown or
     * unreadable, in which case {@link #isContainerProvidedJar(String, Set)} falls
     * back to the static prefix heuristic alone.
     */
    @NotNull
    static Set<String> resolveContainerLibKeys(@Nullable TomcatInfo tomcatInfo) {
        if (tomcatInfo == null) return Collections.emptySet();
        String home = tomcatInfo.getPath();
        if (home == null || home.isEmpty()) return Collections.emptySet();
        Path homeDir = Paths.get(home);
        Set<String> keys = new HashSet<>();
        addJarKeysFrom(homeDir.resolve("lib"), keys);
        // bin/ carries bootstrap.jar, tomcat-juli.jar and (when installed)
        // commons-daemon — also container-provided.
        addJarKeysFrom(homeDir.resolve("bin"), keys);
        return keys;
    }

    private static void addJarKeysFrom(@NotNull Path dir, @NotNull Set<String> keys) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXT_JAR))
                  .forEach(p -> keys.add(
                          LibraryArtifactNames.libraryArtifactKey(p.getFileName().toString())));
        } catch (IOException e) {
            LOG.debug("Could not scan Tomcat dir for container-provided jars: "
                    + dir + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Snapshot of all IntelliJ project model data needed for context XML generation.
     *
     * <p>Collected atomically under a single read action in {@link #collectModelSnapshot}.
     * After collection every field is a plain Java object — no IntelliJ model APIs are
     * accessed subsequently, so there are no threading constraints on their use.
     */
    private static final class ArtifactModelSnapshot {
        /**
         * Recursive production-runtime classpath of the module (compile +
         * runtime scope): class output directories and library JARs. Test- and
         * provided-scope entries are excluded so the set matches exactly what
         * the build packages into {@code WEB-INF/lib/}. Paths are plain strings
         * with any trailing {@code !/} on JAR roots already stripped.
         */
        final List<String> rootPaths;
        /**
         * Webapp source directories for the module (WebFacet roots, convention
         * dirs, declared build-time web-resource directories), in resolution
         * order. Plain absolute path strings, forward-slash normalized for
         * comparison. Mounted read-only at the web-app root so source files
         * shadow the deployed copy without a rebuild.
         */
        final List<String> webappSourceRoots;

        ArtifactModelSnapshot(@NotNull List<String> rootPaths,
                              @NotNull List<String> webappSourceRoots) {
            this.rootPaths = rootPaths;
            this.webappSourceRoots = webappSourceRoots;
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

        // Production-runtime classpath roots (compile + runtime scope),
        // converted to plain path strings while the read action is held. This
        // is exactly the set the build packages into WEB-INF/lib/: test-scope
        // and provided-scope libraries are excluded so the context.xml overlay
        // never mounts a library the deployed artifact does not itself contain.
        List<String> rootPaths = collectRuntimeClasspathRoots(module);

        // Webapp source roots, resolved through the same dispatch the mirror
        // pipeline uses so the overlay covers exactly what gets copied. Normalize
        // to forward slashes so the docBase-containment check below is uniform
        // with the classpath roots above.
        List<String> webappSourceRoots = new ArrayList<>();
        for (Path webappRoot : WebResourcesSync.findWebappSourceRootsForTyped(project, deployment)) {
            webappSourceRoots.add(webappRoot.toAbsolutePath().toString().replace('\\', '/'));
        }

        return new ArtifactModelSnapshot(rootPaths, webappSourceRoots);
    }

    /**
     * Collects the module's recursive <em>production-runtime</em> classpath
     * roots — the class output directories and library JARs visible at runtime
     * in a packaged build. Scope is restricted to compile + runtime via
     * {@code productionOnly().runtimeOnly()} so the set matches exactly what
     * the build tool packages into {@code WEB-INF/lib/}: test-scope and
     * provided-scope dependencies are excluded.
     *
     * <p>This matters because the result feeds the {@code <PostResources>}
     * overlay, which adds entries to Tomcat's webapp classloader. Mounting a
     * test- or provided-scope JAR there would put a library on the running
     * webapp's classpath that the real (Maven-built) artifact never contains —
     * a phantom entry that can shadow or duplicate classes, break frameworks
     * that audit the classpath for unique resources, and confuse type-based
     * dependency resolution. Keeping the scope identical to the build's
     * {@code WEB-INF/lib/} guarantees the in-IDE deployment classpath equals
     * the packaged one.
     *
     * <p>Trailing {@code !/} on JAR content roots is stripped so callers always
     * work with clean filesystem-style paths. Both directories and JARs are
     * returned in classpath order; the caller selects JARs for
     * {@code <PostResources>} and skips directory roots.
     *
     * <p><strong>Must be called under a read action.</strong>
     */
    @NotNull
    static List<String> collectRuntimeClasspathRoots(@NotNull Module module) {
        List<String> rootPaths = new ArrayList<>();
        for (VirtualFile root : OrderEnumerator.orderEntries(module)
                .recursively()
                .productionOnly()
                .runtimeOnly()
                .withoutSdk()
                .classes()
                .getRoots()) {
            String path = root.getPath();
            if (path.endsWith(JAR_URL_SUFFIX)) {
                path = path.substring(0, path.length() - JAR_URL_SUFFIX.length());
            }
            rootPaths.add(path);
        }
        return rootPaths;
    }

    /**
     * Resolves the owning IntelliJ Module for {@code deployment}, delegating to
     * {@link DeploymentModuleResolver} so the launch classpath and the
     * scoped-compile module set are derived from exactly the same
     * deployment→module mapping.
     *
     * <p><strong>Must be called under a read action.</strong> The sole caller
     * is {@link #collectModelSnapshot}, which is always invoked inside
     * {@link TomcatReadActions#compute}.
     */
    @Nullable
    private static Module resolveModuleForDeployment(@NotNull Deployment deployment,
                                                     @NotNull Project project) {
        return DeploymentModuleResolver.resolve(deployment, project);
    }
}
