package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

import com.dev.idea.plugins.tomcat.TomcatConstants;

import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF;
import java.util.List;
import java.util.Set;

/**
 * Module and Project utilities for DevTomcat plugin.
 * Provides methods to identify web modules, locate web roots, and extract context paths
 * from IntelliJ IDEA modules, ensuring compatibility with various web project structures.
 *
 * This class handles:
 * - Detection of web modules based on web roots and build configurations
 * - Identification of web root directories (e.g., src/main/webapp, WebContent)
 * - Extraction of context paths from module names
 * - Test vs. production source detection
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class TomcatModuleUtils {

    // Common web root directory names
    private static final Set<String> WEB_ROOT_NAMES = Set.of(
            "webapp", "WebContent", "web", "WebRoot", "webroot", "public", "www"
    );

    // Common paths to web directories
    private static final List<String> WEB_ROOT_PATHS = Arrays.asList(
            "src/main/webapp",
            "web",
            "WebContent",
            "src/webapp",
            "webapp",
            "WebRoot",
            "src/main/web",
            "src/main/resources/static",
            "public"
    );

    // Common web file extensions
    private static final Set<String> WEB_FILE_EXTENSIONS = Set.of(
            "html", "jsp", "xhtml", "js", "ts", // Frontend files
            "xml" // web.xml for traditional Java web apps
    );

    // =====================================================================
    // Module-name parsing
    // =====================================================================

    /** Module-name suffixes stripped before deriving a context path.
     * Order-sensitive: outer suffixes first, so {@code foo.web.main} → {@code foo.web} → {@code foo}.
     *
     */
    private static final List<String> MODULE_NAME_TRIM_SUFFIXES = List.of(
            ".main", ".web", "-web", "_web"
    );

    /** Module names that map to the root context "/". */
    private static final Set<String> ROOT_CONTEXT_MODULE_NAMES = Set.of("root", "main");

    /** Module-name suffixes that mark the module as test-only. */
    private static final Set<String> TEST_MODULE_SUFFIXES = Set.of(
            ".test", ".tests", "-test", "-tests", "_test", "_tests",
            ".spec", "-spec", "_spec"
    );

    /** Exact module names that are always test-only. */
    private static final Set<String> TEST_MODULE_EXACT_NAMES = Set.of("test", "tests");

    /** Gradle sub-project source-set name — matched as the final dot-segment of a compound module name like {@code app.module.test}. */
    private static final String GRADLE_TEST_SOURCE_SET = "test";

    // =====================================================================
    // Cold-project fallback markers (used only by the build-file text scan in
    // hasWebBuildFileTextFallback). Web detection is primarily structural — a
    // discovered web root, the resolved Maven packaging, or a Spring web library
    // on the resolved classpath. These raw-text markers exist solely for a project
    // the IDE has not imported/resolved yet, where none of those signals are
    // available. Kept during a deprecation window while structural detection
    // becomes the norm.
    // =====================================================================

    /** POM content fragments indicating the module produces a war artifact or is Spring-Boot-web. */
    private static final List<String> POM_WEB_INDICATORS = List.of(
            TomcatConstants.POM_PACKAGING_WAR,
            "maven-war-plugin",
            "spring-boot-starter-web"
    );

    /** Gradle build-script fragments (Groovy + Kotlin DSL) indicating war or Spring-Boot-web. */
    private static final List<String> GRADLE_WEB_INDICATORS = List.of(
            "apply plugin: 'war'",
            "id(\"war\")",
            "id 'war'",
            "id \"war\"",
            "plugin 'war'",
            "org.springframework.boot",
            "spring-boot-starter-web"
    );

    /**
     * Resolved-classpath name prefix that marks a module as a servlet web app.
     * Matched against the jar names returned by {@link OrderEnumerator} (e.g.
     * {@code spring-webmvc-6.x.jar}). Deliberately {@code spring-webmvc} and not the
     * broader {@code spring-web}: {@code spring-webmvc} is where {@code DispatcherServlet}
     * lives, so it denotes a classic Tomcat-deployable servlet app. The broader
     * {@code spring-web} (the HTTP-client base behind {@code RestTemplate}/{@code WebClient}),
     * {@code spring-webflux} (reactive, not a servlet), and {@code spring-websocket} are
     * NOT web-deployment signals and would produce false positives. The
     * {@code *-starter-web} aggregator carries no classes, so we match the actual
     * library it resolves to — making the signal build-tool-agnostic (Maven or Gradle,
     * any DSL) and immune to build-file spelling, property-driven versions, and
     * BOM-managed dependencies.
     */
    private static final String SPRING_WEB_LIBRARY_PREFIX = "spring-webmvc";

    // =====================================================================
    // Web Facet reflection (provided by the platform's JavaEE plugin)
    // =====================================================================

    /** Facet type id matched via {@code FacetType.getStringId()} — string-equality avoids a compile-time dep on {@code WebFacet}, which lives in the optional JavaEE plugin. */
    private static final String WEB_FACET_STRING_ID = "web";
    private static final String FACET_METHOD_GET_WEB_ROOTS = "getWebRoots";
    private static final String WEB_ROOT_METHOD_GET_FILE = "getFile";

    private TomcatModuleUtils() {
        // Utility class
    }

    public static boolean isWebModule(@NotNull Module module) {
        // Skip test modules
        if (isTestModule(module)) {
            return false;
        }

        // Check for web roots
        List<VirtualFile> webRoots = findWebRoots(module);
        if (!webRoots.isEmpty()) {
            return true;
        }

        // Check for build tool configurations (e.g., Maven war plugin)
        return hasWebBuildConfiguration(module);
    }

    @NotNull
    public static List<VirtualFile> findWebRoots(@NotNull Module module) {
        // Use LinkedHashSet to deduplicate: WEB_ROOT_PATHS and WEB_ROOT_NAMES share
        // entries like "webapp", "web", "WebContent", so a single directory can be
        // matched by both loops and added twice without deduplication.
        LinkedHashSet<VirtualFile> webRoots = new LinkedHashSet<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            // Content root IS the webapp (Eclipse-style / IntelliJ-hand-configured):
            // the module's root directory directly contains WEB-INF/, no intervening
            // src/main/webapp/. Detected by a stricter check than isValidWebRoot —
            // we require WEB-INF presence specifically, because any Maven module's
            // pom.xml at content root would otherwise satisfy the "has a web-extension
            // file" branch and produce a false positive.
            if (isContentRootWebapp(contentRoot)) {
                webRoots.add(contentRoot);
            }
            // Check common web root paths — case-insensitive walk so layouts like
            // src/main/WEBAPP, src/Main/webapp, or SRC/main/Webapp are detected on
            // case-sensitive filesystems (Linux, CI runners, deliberately
            // case-sensitive macOS volumes). findFileByRelativePath would have
            // missed these even though Tomcat-on-Linux serves them fine.
            for (String webPath : WEB_ROOT_PATHS) {
                VirtualFile webRoot = findChildByRelativePathIgnoreCase(contentRoot, webPath);
                if (isValidWebRoot(webRoot)) {
                    webRoots.add(webRoot);
                }
            }

            // Check direct children with web root names (case-insensitive).
            for (VirtualFile child : contentRoot.getChildren()) {
                if (child.isValid()
                        && child.isDirectory()
                        && containsIgnoreCase(WEB_ROOT_NAMES, child.getName())
                        && isValidWebRoot(child)) {
                    webRoots.add(child);
                }
            }
        }

        return new ArrayList<>(webRoots);
    }

    /**
     * Whether {@code file} lives inside any web root discovered for {@code module}
     * — conventional ({@link #findWebRoots}), explicitly configured by the user
     * ({@link #findWebFacetRoots}), or any directory that holds {@code WEB-INF}
     * regardless of its name ({@link #findUnconventionalWebRoots}). Structure- and
     * file-type-agnostic: any file under a web root counts, whatever its extension
     * or the hosting directory's name — so it adapts to custom project layouts
     * instead of matching a fixed list of names/extensions.
     *
     * <p>Sources are evaluated cheapest-first and short-circuit: the recursive
     * {@code WEB-INF} scan ({@link #findUnconventionalWebRoots}) runs only when the
     * cheap conventional and facet lookups did not already match, so a hit on the
     * common conventional layout costs no filesystem walk. Must be called under a
     * read action (it reads the module model and the VFS).
     */
    public static boolean isUnderWebRoot(@NotNull VirtualFile file, @NotNull Module module) {
        return containsAsAncestor(findWebRoots(module), file)
                || containsAsAncestor(findWebFacetRoots(module), file)
                || containsAsAncestor(findUnconventionalWebRoots(module), file);
    }

    private static boolean containsAsAncestor(@NotNull List<VirtualFile> roots, @NotNull VirtualFile file) {
        for (VirtualFile root : roots) {
            // strict=false: a file that IS the web-root node also counts as web context.
            if (VfsUtilCore.isAncestor(root, file, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stricter variant of {@link #isValidWebRoot}: requires the directory to
     * contain a {@code WEB-INF/} subdirectory directly. Used for the
     * content-root-IS-webapp detection where the looser "has a web-extension
     * file" check from {@code isValidWebRoot} would produce false positives
     * (a Maven module's content root carries {@code pom.xml}, which would
     * satisfy that branch).
     */
    private static boolean isContentRootWebapp(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) return false;
        VirtualFile webInf = dir.findChild(WEB_INF);
        return webInf != null && webInf.isDirectory();
    }

    /**
     * Directory-name fragments that mark a subtree as build output, version
     * control, IDE metadata, or dependency cache — never a webapp source.
     * Used by {@link #findUnconventionalWebRoots} to keep the scan bounded.
     */
    private static final Set<String> SCAN_EXCLUDE_DIRS = Set.of(
            "target", "build", "out", "bin", "dist",
            ".git", ".idea", ".gradle", ".mvn",
            "node_modules", ".m2", ".vscode", ".settings",
            "test-output", "logs", "tmp"
    );

    /**
     * Maximum directory depth the {@link #findUnconventionalWebRoots} scan
     * descends. {@code src/main/webapp} is depth 3 from the content root;
     * 4 leaves headroom for layouts like {@code src/main/web/v2} without
     * letting the walk wander into deep dependency trees if exclusions
     * miss something.
     */
    private static final int UNCONVENTIONAL_SCAN_MAX_DEPTH = 4;

    /**
     * Bounded filesystem scan under each of a module's content roots looking
     * for any directory that directly contains {@code WEB-INF/}. Catches
     * layouts the convention lists miss — Gradle {@code webAppDirName}
     * overrides, custom Eclipse exports, arbitrary user-named webapp
     * directories — without requiring a parallel reflection path through the
     * Gradle plugin model.
     *
     * <p>Performance is the reason for the depth cap and the exclude list:
     * a recursive walk through {@code node_modules/} or a deep Maven
     * {@code target/} would dwarf the work this method exists to do, and
     * those directories never legitimately host a webapp source.
     *
     * <p>Returns an empty list (never {@code null}). Intended as a fallback
     * — callers should consult conventional + facet + Maven sources first and
     * only invoke this when none of those produced anything, otherwise the
     * scan would duplicate roots already discovered via cheaper paths.
     */
    @NotNull
    public static List<VirtualFile> findUnconventionalWebRoots(@NotNull Module module) {
        LinkedHashSet<VirtualFile> found = new LinkedHashSet<>();
        for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
            scanForWebInfHolders(contentRoot, 0, found);
        }
        return new ArrayList<>(found);
    }

    /**
     * Recursive helper for {@link #findUnconventionalWebRoots}. Skips
     * {@link #SCAN_EXCLUDE_DIRS} entries by name and gives up at
     * {@link #UNCONVENTIONAL_SCAN_MAX_DEPTH}. Symlinked directories are
     * skipped — could point outside the project or form a loop.
     */
    private static void scanForWebInfHolders(@NotNull VirtualFile dir,
                                             int depth,
                                             @NotNull LinkedHashSet<VirtualFile> out) {
        if (depth > UNCONVENTIONAL_SCAN_MAX_DEPTH) return;
        if (!dir.isValid() || !dir.isDirectory() || dir.is(com.intellij.openapi.vfs.VFileProperty.SYMLINK)) {
            return;
        }
        // Does this directory itself host a webapp?
        if (isContentRootWebapp(dir)) {
            out.add(dir);
            // Don't descend below a confirmed webapp root — its own subtree
            // is content, not a candidate for nested webapps.
            return;
        }
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isValid() || !child.isDirectory()) continue;
            // Skip well-known non-webapp subtrees by name. Case-insensitive
            // because Linux may have variants (.GIT vs .git) and Windows
            // case-folds anyway.
            if (containsIgnoreCase(SCAN_EXCLUDE_DIRS, child.getName())) continue;
            scanForWebInfHolders(child, depth + 1, out);
        }
    }

    /**
     * Reads {@code WebFacet.getWebRoots()} via {@link com.intellij.facet.FacetManager}
     * for every {@code "web"}-type facet attached to {@code module}. Falls back to an
     * empty list when the JavaEE plugin is not present, when no Web Facets are
     * attached, or when the reflective access fails.
     *
     * <p>Authoritative source: a user who has explicitly configured Web Facet roots
     * in Project Structure → Facets → Web wants those exact paths, not any
     * convention guess.
     */
    @NotNull
    public static List<VirtualFile> findWebFacetRoots(@NotNull Module module) {
        try {
            com.intellij.facet.FacetManager fm = com.intellij.facet.FacetManager.getInstance(module);
            if (fm == null) return java.util.Collections.emptyList();
            LinkedHashSet<VirtualFile> out = new LinkedHashSet<>();
            for (com.intellij.facet.Facet<?> facet : fm.getAllFacets()) {
                // String-ID match avoids a compile-time dependency on WebFacet
                // (which lives in the optional JavaEE plugin).
                if (!WEB_FACET_STRING_ID.equals(facet.getType().getStringId())) continue;
                try {
                    Object roots = facet.getClass().getMethod(FACET_METHOD_GET_WEB_ROOTS).invoke(facet);
                    if (!(roots instanceof Iterable<?>)) continue;
                    for (Object webRoot : (Iterable<?>) roots) {
                        Object file = webRoot.getClass().getMethod(WEB_ROOT_METHOD_GET_FILE).invoke(webRoot);
                        if (file instanceof VirtualFile vf && vf.isValid() && vf.isDirectory()) {
                            out.add(vf);
                        }
                    }
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // Cancellation must escape the reflective boundary — this
                    // runs per existing config on the cancelable action-update
                    // path (run-config producer matching).
                    if (e.getCause() instanceof com.intellij.openapi.progress.ProcessCanceledException pce) {
                        throw pce;
                    }
                    // Facet shape unexpected — skip this facet, try the next.
                } catch (NoSuchMethodException | IllegalAccessException ignored) {
                    // Facet shape unexpected — skip this facet, try the next.
                }
            }
            return new ArrayList<>(out);
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            // PCE before the generic handler — cancellation propagates instead
            // of silently degrading facet discovery to convention fallbacks.
            throw pce;
        } catch (NoClassDefFoundError | Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Case-insensitive equivalent of {@link VirtualFile#findFileByRelativePath}.
     * Walks each path segment by iterating the directory's children and matching
     * names with {@link String#equalsIgnoreCase}, so {@code src/main/webapp} also
     * locates {@code src/main/WEBAPP}, {@code SRC/Main/webapp}, etc.
     *
     * <p>Mac/Windows filesystems are case-insensitive by default — the original
     * {@code findFileByRelativePath} happened to work on those, hiding the bug
     * during routine development. Linux and case-sensitive macOS volumes (and
     * therefore CI runners, Docker images, and most production servers) made
     * the gap visible.
     */
    @Nullable
    private static VirtualFile findChildByRelativePathIgnoreCase(@NotNull VirtualFile root,
                                                                 @NotNull String relativePath) {
        VirtualFile current = root;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty()) continue;
            VirtualFile next = null;
            for (VirtualFile child : current.getChildren()) {
                if (segment.equalsIgnoreCase(child.getName())) {
                    next = child;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    /**
     * Case-insensitive membership check against a set of canonical names.
     * Uses {@link Locale#ROOT} so a Turkish-locale machine does not fold
     * {@code I} → {@code ı} and miss matches like {@code WebContent}.
     */
    private static boolean containsIgnoreCase(@NotNull Set<String> set, @NotNull String name) {
        for (String entry : set) {
            if (entry.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    @NotNull
    public static String extractContextPath(@NotNull Module module) {
        String moduleName = module.getName();

        for (String suffix : MODULE_NAME_TRIM_SUFFIXES) {
            moduleName = StringUtil.trimEnd(moduleName, suffix);
        }

        // Get last component after dots
        int lastDot = moduleName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < moduleName.length() - 1) {
            moduleName = moduleName.substring(lastDot + 1);
        }

        // Locale.ROOT — without it, a Turkish-locale user with a module named
        // "WebApi" would have lowercase produce "webapı" (dotless ı). The
        // [^a-z0-9-] regex then replaces 'ı' with '-' (because ı is outside
        // the ASCII a-z range), and the final context path becomes "/webap"
        // instead of "/webapi". Browser hits 404 on the wrong path.
        moduleName = moduleName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        if (moduleName.isEmpty() || ROOT_CONTEXT_MODULE_NAMES.contains(moduleName)) {
            return TomcatConstants.DEFAULT_CONTEXT_PATH;
        }

        return TomcatConstants.DEFAULT_CONTEXT_PATH + moduleName;
    }

    public static boolean isTestSource(@Nullable com.intellij.execution.Location<? extends PsiElement> location) {
        if (location == null) {
            return false;
        }

        VirtualFile file = location.getVirtualFile();
        if (file == null) {
            return false;
        }

        Project project = location.getProject();
        ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);
        return projectFileIndex.isInTestSourceContent(file);
    }

    // ===================== Private Helper Methods =====================

    private static boolean isValidWebRoot(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        // Check for WEB-INF directory (traditional Java web apps)
        VirtualFile webInf = dir.findChild(WEB_INF);
        if (webInf != null && webInf.isDirectory()) {
            return true;
        }

        // Check for common web files (including SPA frameworks)
        for (VirtualFile child : dir.getChildren()) {
            if (child.isValid() && !child.isDirectory()) {
                String extension = child.getExtension();
                if (extension != null && WEB_FILE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Determines whether a module is a test module.
     *
     * <p>Uses suffix-based matching to avoid false positives: a module named
     * "devtomcat-test" or "my-contest-app" is NOT a test module, but
     * "myapp.test", "myapp-tests", or "myapp_test" ARE test modules.
     * Also checks Gradle composite build naming (e.g., "project.test").
     */
    private static boolean isTestModule(@NotNull Module module) {
        // Locale.ROOT for consistency with extractContextPath and the rest of the
        // module-matching pipeline. The current suffix list happens not to contain
        // characters affected by tr_TR I-folding, but pinning here keeps the rule
        // local and survives any future suffix that does.
        String name = module.getName().toLowerCase(Locale.ROOT);

        for (String suffix : TEST_MODULE_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }

        if (TEST_MODULE_EXACT_NAMES.contains(name)) return true;

        // Gradle sub-project test source set — "test" as the final dot-segment.
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 && name.substring(lastDot + 1).equals(GRADLE_TEST_SOURCE_SET);
    }

    /**
     * Whether the module's build configuration marks it as web, determined
     * structurally first and only falling back to raw build-file text for a
     * project the IDE has not imported/resolved yet.
     *
     * <p>Order:
     * <ol>
     *   <li><b>Resolved Maven packaging</b> — {@code "war"} is web by definition,
     *       and the resolved model sees packaging inherited from a parent POM, set
     *       via a {@code ${property}}, or activated in a profile. ({@code "war"} is
     *       the bare resolved value, distinct from the XML fragment
     *       {@link TomcatConstants#POM_PACKAGING_WAR}.)</li>
     *   <li><b>Spring MVC on the resolved classpath</b> — a {@code spring-webmvc}
     *       (servlet) library resolved onto the module's runtime classpath marks it
     *       web. Build-tool-agnostic (Maven or Gradle, any DSL) and immune to
     *       build-file spelling; it also catches plain Spring MVC apps (no war
     *       packaging) that the build-file markers below do not name.</li>
     *   <li><b>Build-file text fallback</b> — for an un-imported project, where
     *       neither the Maven model nor the classpath is resolved yet. Scans both
     *       the {@code pom.xml} and the Gradle script for the legacy war / Spring
     *       markers, identical to the pre-rewrite behaviour, so detection for such
     *       projects is unchanged; structural signals simply take precedence when
     *       available.</li>
     * </ol>
     */
    private static boolean hasWebBuildConfiguration(@NotNull Module module) {
        if ("war".equals(MavenModelProvider.packaging(module))) {
            return true;
        }
        if (hasWebFrameworkOnClasspath(module)) {
            return true;
        }
        return hasWebBuildFileTextFallback(module);
    }

    /**
     * Structural Spring-web signal: a {@value #SPRING_WEB_LIBRARY_PREFIX}* library
     * on the module's resolved runtime classpath. Uses the same
     * {@code runtimeOnly().recursively()} enumeration the run-config producer uses
     * for Spring Boot detection, so the two stay consistent.
     */
    private static boolean hasWebFrameworkOnClasspath(@NotNull Module module) {
        for (VirtualFile root : OrderEnumerator.orderEntries(module)
                .runtimeOnly().recursively().classes().getRoots()) {
            if (isWebFrameworkLibrary(root.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure predicate: does a resolved classpath-root name denote a Spring web
     * library? Matched case-insensitively ({@link Locale#ROOT}) against
     * {@link #SPRING_WEB_LIBRARY_PREFIX}.
     */
    static boolean isWebFrameworkLibrary(@Nullable String rootName) {
        if (rootName == null) return false;
        return rootName.toLowerCase(Locale.ROOT).startsWith(SPRING_WEB_LIBRARY_PREFIX);
    }

    /**
     * Last-resort web detection for a not-yet-imported project, kept during a
     * deprecation window while the structural signals above become the norm. Reads
     * the raw build-file text and matches the known war / Spring-web markers.
     *
     * <p>Scans both the {@code pom.xml} and the Gradle build script on every content
     * root (and the project base dir, for single-module layouts whose module root
     * differs). It does <em>not</em> route by the resolved external-system id: the
     * scans are already self-selecting (each only fires when its build file is
     * present), and gating by owner could hide a module whose only web signal lives
     * in the other tool's file — a worse outcome than the rare cross-tool
     * false positive gating would suppress. This mirrors the pre-rewrite behaviour
     * exactly, so the fallback never loses a verdict the old code produced.
     */
    private static boolean hasWebBuildFileTextFallback(@NotNull Module module) {
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
            if (checkMavenWebConfig(root) || checkGradleWebConfig(root)) {
                return true;
            }
        }

        VirtualFile baseDir = ProjectUtil.guessProjectDir(module.getProject());
        if (baseDir != null) {
            if (checkMavenWebConfig(baseDir) || checkGradleWebConfig(baseDir)) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkMavenWebConfig(@NotNull VirtualFile dir) {
        VirtualFile pomFile = dir.findChild(TomcatConstants.MAVEN_BUILD_FILE);
        if (pomFile == null || !pomFile.exists()) return false;

        try {
            String content = VfsUtil.loadText(pomFile);
            // POM-packaged projects are aggregators/parents, not web apps
            if (content.contains(TomcatConstants.POM_PACKAGING_POM)) return false;
            return containsAny(content, POM_WEB_INDICATORS);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean checkGradleWebConfig(@NotNull VirtualFile dir) {
        // Check both Groovy DSL and Kotlin DSL
        VirtualFile gradleFile = dir.findChild(TomcatConstants.GRADLE_BUILD_FILE_GROOVY);
        if (gradleFile == null) {
            gradleFile = dir.findChild(TomcatConstants.GRADLE_BUILD_FILE_KOTLIN);
        }
        if (gradleFile == null || !gradleFile.exists()) return false;

        try {
            String content = VfsUtil.loadText(gradleFile);
            return containsAny(content, GRADLE_WEB_INDICATORS);
        } catch (IOException e) {
            return false;
        }
    }

    /** Returns true iff any of {@code markers} is a substring of {@code content}. */
    private static boolean containsAny(@NotNull String content, @NotNull List<String> markers) {
        for (String marker : markers) {
            if (content.contains(marker)) return true;
        }
        return false;
    }
}