package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Owns CATALINA_BASE setup and Tomcat config file materialization.
 *
 * <p>Extracted from {@code TomcatJavaParametersBuilder} to isolate filesystem
 * preparation from Java command-line building. This is where dynamic Tomcat
 * config support should live.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create directory structure (temp, logs, webapps, work, conf)</li>
 *   <li>Pre-create log files for IntelliJ's log tabs</li>
 *   <li>Copy full {@code conf} tree from CATALINA_HOME to CATALINA_BASE</li>
 *   <li>Mutate only {@code server.xml} via {@link ServerXmlMutator}</li>
 * </ul>
 */
public final class TomcatConfigPreparer {

    private static final Logger LOG = Logger.getInstance(TomcatConfigPreparer.class);

    private TomcatConfigPreparer() {}

    /**
     * Prepares the CATALINA_BASE directory with all config files needed for launch.
     *
     * @param catalinaBase the catalina.base directory to prepare
     * @param catalinaHome the catalina.home directory to copy defaults from
     * @param httpPort     resolved HTTP port
     * @param shutdownPort resolved shutdown port
     * @param httpsPort    resolved HTTPS port
     * @param httpsEnabled whether HTTPS connector should be configured
     * @param ajpPort      resolved AJP port
     * @param ajpEnabled   whether AJP connector should be configured
     * @return list of warnings from server.xml mutation (may be empty)
     * @throws IOException if directory creation or file operations fail
     */
    /**
     * Minimum required config files for a Tomcat launch.
     * If any of these are missing after conf copy, a warning is emitted.
     */
    private static final String[] REQUIRED_CONF_FILES = {
            CONFIG_SERVER_XML,
            CONFIG_WEB_XML,
            CONFIG_CATALINA_PROPERTIES
    };

    @NotNull
    public static List<String> prepare(@NotNull Path catalinaBase, @NotNull Path catalinaHome,
                                       int httpPort, int shutdownPort,
                                       int httpsPort, boolean httpsEnabled,
                                       int ajpPort, boolean ajpEnabled) throws IOException {
        return prepare(catalinaBase, catalinaHome, httpPort, shutdownPort,
                httpsPort, httpsEnabled, ajpPort, ajpEnabled, null);
    }

    /**
     * Prepares the CATALINA_BASE directory with all config files needed for launch.
     *
     * <p>Steps:
     * <ol>
     *   <li>Create directory structure</li>
     *   <li>Pre-create log files for IntelliJ tabs</li>
     *   <li>Clean stale work directory to prevent classloader caching</li>
     *   <li>Copy full {@code conf} from CATALINA_HOME</li>
     *   <li>Apply user conf overlay (if present)</li>
     *   <li>Mutate {@code server.xml} with configured ports</li>
     *   <li>Validate required config files exist</li>
     * </ol>
     *
     * @param catalinaBase the catalina.base directory to prepare
     * @param catalinaHome the catalina.home directory to copy defaults from
     * @param httpPort     resolved HTTP port
     * @param shutdownPort resolved shutdown port
     * @param httpsPort    resolved HTTPS port
     * @param httpsEnabled whether HTTPS connector should be configured
     * @param ajpPort      resolved AJP port
     * @param ajpEnabled   whether AJP connector should be configured
     * @param confOverlay  optional project-level conf overlay directory; files here
     *                     are copied on top of CATALINA_HOME's conf, letting users
     *                     persist custom Realms, Valves, JNDI resources, etc.
     * @return list of warnings (may be empty)
     * @throws IOException if directory creation or file operations fail
     */
    @NotNull
    public static List<String> prepare(@NotNull Path catalinaBase, @NotNull Path catalinaHome,
                                       int httpPort, int shutdownPort,
                                       int httpsPort, boolean httpsEnabled,
                                       int ajpPort, boolean ajpEnabled,
                                       @Nullable Path confOverlay) throws IOException {
        List<String> warnings = new ArrayList<>();

        createDirectories(catalinaBase);
        createLogFiles(catalinaBase.resolve(DIR_LOGS));
        cleanWorkDirectory(catalinaBase);
        warnings.addAll(inspectTempDirectory(catalinaBase));

        copyConfDirectory(catalinaHome, catalinaBase);

        if (confOverlay != null) {
            applyConfOverlay(confOverlay, catalinaBase);
        }

        warnings.addAll(customizeServerXml(
                catalinaHome, catalinaBase, httpPort, shutdownPort,
                httpsPort, httpsEnabled, ajpPort, ajpEnabled));

        warnings.addAll(validateConf(catalinaBase));

        return warnings;
    }

    /**
     * Non-destructive inspection of temp/ before launch.
     * Disk-backed caches often persist lock/state files here across runs.
     */
    @NotNull
    static List<String> inspectTempDirectory(@NotNull Path catalinaBase) throws IOException {
        Path tempDir = catalinaBase.resolve(DIR_TEMP);
        if (!Files.isDirectory(tempDir)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(tempDir)) {
            List<Path> suspiciousEntries = entries
                    .filter(TomcatConfigPreparer::looksLikePersistentAppTempState)
                    .toList();
            if (suspiciousEntries.isEmpty()) {
                return List.of();
            }
            return List.of("CATALINA_BASE temp directory contains possible persistent cache/state entries ("
                    + suspiciousEntries.size() + ") under " + tempDir + ". "
                    + "These may cause stale state or lock errors on restart. If startup fails with a locked persistence directory, "
                    + "stop the old process and clean the stale cache path. Suspect entries: " + summarizeSuspiciousEntries(suspiciousEntries));
        }
    }

    private static boolean looksLikePersistentAppTempState(@NotNull Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
        // Only flag files/directories with names suggesting persistent state —
        // not ALL directories, since Tomcat normally creates temp subdirectories.
        return name.contains("lock")
                || name.contains("cache")
                || name.contains("ehcache")
                || name.contains("liquibase")
                || name.contains("hazelcast")
                || name.endsWith(".lck")
                || name.endsWith(".pid");
    }

    @NotNull
    private static String summarizeSuspiciousEntries(@NotNull List<Path> suspiciousEntries) {
        return suspiciousEntries.stream()
                .limit(3)
                .map(path -> path.getFileName() != null ? path.getFileName().toString() : path.toString())
                .reduce((left, right) -> left + ", " + right)
                .map(summary -> suspiciousEntries.size() > 3 ? summary + ", ..." : summary)
                .orElse("(unknown)");
    }

    /**
     * Creates the CATALINA_BASE directory structure.
     */
    static void createDirectories(@NotNull Path catalinaBase) throws IOException {
        Files.createDirectories(catalinaBase.resolve(DIR_TEMP));
        Files.createDirectories(catalinaBase.resolve(DIR_LOGS));
        Files.createDirectories(catalinaBase.resolve(DIR_WEBAPPS));
        Files.createDirectories(catalinaBase.resolve(DIR_WORK));
        Files.createDirectories(catalinaBase.resolve(DIR_CONF));
    }

    /**
     * Pre-creates standard log files so IntelliJ's RunContentBuilder creates tabs for them.
     */
    static void createLogFiles(@NotNull Path logsDir) throws IOException {
        Files.createDirectories(logsDir);
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            Path logPath = Paths.get(logFile.resolveFullPath(logsDir));
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
            }
        }
    }

    /**
     * Customizes server.xml using DOM-based mutation via {@link ServerXmlMutator}.
     * Falls back to generating a minimal server.xml if the source doesn't exist.
     *
     * @return list of warnings from the mutation
     */
    @NotNull
    static List<String> customizeServerXml(@NotNull Path catalinaHome, @NotNull Path catalinaBase,
                                           int httpPort, int shutdownPort,
                                           int httpsPort, boolean httpsEnabled,
                                           int ajpPort, boolean ajpEnabled) throws IOException {
        Path sourceServerXml = catalinaHome.resolve(CONFIG_SERVER_XML);
        Path targetServerXml = catalinaBase.resolve(CONFIG_SERVER_XML);

        if (!Files.exists(sourceServerXml)) {
            LOG.warn("server.xml not found at " + sourceServerXml + ", generating minimal config");
            String minimal = generateMinimalServerXml(httpPort, shutdownPort,
                    httpsPort, httpsEnabled, ajpPort, ajpEnabled);
            Files.writeString(targetServerXml, minimal);
            return List.of();
        }

        String xml = Files.readString(sourceServerXml);
        ServerXmlMutator.MutationResult result = ServerXmlMutator.customize(
                xml, shutdownPort, httpPort, httpsPort, httpsEnabled, ajpPort, ajpEnabled);

        for (String warning : result.getWarnings()) {
            LOG.warn("server.xml: " + warning);
        }

        // If DOM parsing failed, fall back to a minimal generated config rather than
        // writing the original (possibly broken) XML to CATALINA_BASE.
        boolean parseFailed = result.getWarnings().stream()
                .anyMatch(w -> w.contains("XML parsing failed"));
        String outputXml;
        if (parseFailed) {
            LOG.warn("Falling back to minimal generated server.xml due to parse failure");
            outputXml = generateMinimalServerXml(httpPort, shutdownPort,
                    httpsPort, httpsEnabled, ajpPort, ajpEnabled);
        } else {
            outputXml = result.getXml();
        }

        Files.writeString(targetServerXml, outputXml);
        LOG.info("Created server.xml at " + targetServerXml + " (HTTP=" + httpPort +
                ", shutdown=" + shutdownPort +
                (httpsEnabled ? ", HTTPS=" + httpsPort : "") +
                (ajpEnabled ? ", AJP=" + ajpPort : "") + ")");

        return result.getWarnings();
    }

    /**
     * Recursively copies the entire {@code conf} directory from CATALINA_HOME into CATALINA_BASE.
     *
     * <p>This ensures the runtime base has the full configuration tree that Tomcat expects,
     * including files the plugin does not explicitly know about (e.g. custom valves, realms,
     * MIME mappings). Only {@code server.xml} is mutated afterwards; everything else is
     * kept as-is from the installation.
     */
    static void copyConfDirectory(@NotNull Path catalinaHome, @NotNull Path catalinaBase) throws IOException {
        Path sourceConf = catalinaHome.resolve(DIR_CONF);
        Path targetConf = catalinaBase.resolve(DIR_CONF);

        if (!Files.isDirectory(sourceConf)) {
            LOG.warn("CATALINA_HOME conf directory not found: " + sourceConf);
            return;
        }

        recreateDirectory(targetConf);

        Files.walkFileTree(sourceConf, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceConf.relativize(dir);
                Files.createDirectories(targetConf.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceConf.relativize(file);
                Path target = targetConf.resolve(relative);
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });

        LOG.info("Copied full conf directory from " + sourceConf + " to " + targetConf);
    }

    private static void recreateDirectory(@NotNull Path dir) throws IOException {
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
        Files.createDirectories(dir);
    }

    private static void deleteRecursively(@NotNull Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path currentDir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(currentDir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Cleans the {@code work/} directory between runs to prevent stale classloader
     * caching from a previous launch. Tomcat compiles JSPs and caches class files
     * here; leftover entries can cause ClassNotFoundException or stale content.
     */
    static void cleanWorkDirectory(@NotNull Path catalinaBase) throws IOException {
        Path workDir = catalinaBase.resolve(DIR_WORK);
        if (Files.isDirectory(workDir)) {
            AtomicInteger count = new AtomicInteger();
            Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    count.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    if (!dir.equals(workDir)) {
                        Files.deleteIfExists(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (count.get() > 0) {
                LOG.info("Cleaned " + count.get() + " stale files from work directory");
            }
        }
    }

    /**
     * Applies a user conf overlay on top of the copied CATALINA_HOME conf.
     *
     * <p>Files in the overlay directory are copied into {@code CATALINA_BASE/conf},
     * overwriting the defaults from CATALINA_HOME. This lets users persist custom
     * Realms, Valves, JNDI resources, or any other Tomcat config that should survive
     * across runs without modifying the Tomcat installation.
     *
     * <p>Note: {@code server.xml} in the overlay will be overwritten by the port
     * mutation step that runs after this. Users who need custom server.xml content
     * should add non-port elements (Realms, Valves, etc.) — ports are always managed
     * by the plugin.
     */
    static void applyConfOverlay(@NotNull Path overlayDir, @NotNull Path catalinaBase) throws IOException {
        if (!Files.isDirectory(overlayDir)) {
            return;
        }

        Path targetConf = catalinaBase.resolve(DIR_CONF);
        AtomicInteger count = new AtomicInteger();

        Files.walkFileTree(overlayDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = overlayDir.relativize(dir);
                Files.createDirectories(targetConf.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = overlayDir.relativize(file);
                Path target = targetConf.resolve(relative);
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                count.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });

        if (count.get() > 0) {
            LOG.info("Applied " + count.get() + " conf overlay file(s) from " + overlayDir);
        }
    }

    /**
     * Validates that the prepared CATALINA_BASE/conf contains the minimum
     * required files for Tomcat to start. Returns warnings for any missing files
     * so problems surface early rather than as cryptic Tomcat errors.
     */
    @NotNull
    static List<String> validateConf(@NotNull Path catalinaBase) {
        List<String> warnings = new ArrayList<>();
        for (String required : REQUIRED_CONF_FILES) {
            if (!Files.exists(catalinaBase.resolve(required))) {
                warnings.add("Required config file missing: " + required +
                        " — Tomcat may fail to start");
            }
        }
        return warnings;
    }

    /**
     * Generates a minimal server.xml when the source doesn't exist.
     */
    @NotNull
    static String generateMinimalServerXml(int httpPort, int shutdownPort,
                                           int httpsPort, boolean httpsEnabled,
                                           int ajpPort, boolean ajpEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Server port=\"").append(shutdownPort).append("\" shutdown=\"").append(SHUTDOWN_COMMAND).append("\">\n");
        sb.append("  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n");
        sb.append("  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n");
        sb.append("  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n");
        sb.append("  <Service name=\"Catalina\">\n");
        sb.append("    <Connector port=\"").append(httpPort).append("\" protocol=\"").append(PROTOCOL_HTTP).append("\"\n");
        sb.append("               connectionTimeout=\"20000\" redirectPort=\"")
                .append(httpsEnabled ? httpsPort : com.dev.idea.plugins.tomcat.model.PortConfig.DEFAULT_HTTPS_PORT)
                .append("\" />\n");

        if (httpsEnabled) {
            sb.append("    <Connector port=\"").append(httpsPort).append("\" protocol=\"").append(PROTOCOL_HTTPS).append("\"\n");
            sb.append("               maxThreads=\"150\" SSLEnabled=\"true\">\n");
            sb.append("      <!-- Configure SSL certificate in server.xml or use JVM keystore -->\n");
            sb.append("    </Connector>\n");
        }

        if (ajpEnabled) {
            sb.append("    <Connector port=\"").append(ajpPort).append("\" protocol=\"").append(PROTOCOL_AJP).append("\"\n");
            sb.append("               secretRequired=\"false\" redirectPort=\"")
                    .append(httpsEnabled ? httpsPort : com.dev.idea.plugins.tomcat.model.PortConfig.DEFAULT_HTTPS_PORT)
                    .append("\" />\n");
        }

        sb.append("    <Engine name=\"Catalina\" defaultHost=\"").append(DEFAULT_HOST).append("\">\n");
        sb.append("      <Host name=\"").append(DEFAULT_HOST).append("\" appBase=\"").append(DIR_WEBAPPS).append("\"\n");
        sb.append("            unpackWARs=\"true\" autoDeploy=\"true\" />\n");
        sb.append("    </Engine>\n");
        sb.append("  </Service>\n");
        sb.append("</Server>\n");
        return sb.toString();
    }
}
