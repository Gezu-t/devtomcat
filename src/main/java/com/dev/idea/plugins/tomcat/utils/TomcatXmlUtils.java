package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * XML and filesystem utilities for DevTomcat plugin.
 *
 * Provides:
 * - Secure XML document handling with XXE (XML External Entity) protection
 * - Filesystem operations for Tomcat directories
 * - Directory cleanup utilities
 *
 * <p>100% NULL-SAFE — All parameters validated with Objects.requireNonNull()
 * <p>Security-First — XXE protection enabled by default
 * <p>Logging-Enabled — Comprehensive debug logging for troubleshooting
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create secure XML parsers and transformers</li>
 *   <li>Clean and manage Tomcat work/logs directories</li>
 *   <li>Validate and sanitize filesystem operations</li>
 *   <li>Preserve critical files during cleanup</li>
 * </ul>
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public final class TomcatXmlUtils {

    private static final Logger LOG = Logger.getInstance(TomcatXmlUtils.class);

    private TomcatXmlUtils() {
        // Utility class - no instantiation
    }

    // =====================================================================
    // XML UTILITIES (SECURE)
    // =====================================================================

    /**
     * Creates a secure DocumentBuilder with XXE protection.
     *
     * <p>Disables external entities, DTDs, and schemas to prevent XXE attacks.
     * Falls back gracefully if some features are not supported.
     *
     * @return Configured DocumentBuilder (never null)
     * @throws ParserConfigurationException if critical configuration fails
     */
    @NotNull
    public static DocumentBuilder createSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        try {
            // Disable DTDs completely
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            // Disable external entities
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            // Disable external DTDs and schemas
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            LOG.debug("XXE protection features successfully configured");

        } catch (IllegalArgumentException e) {
            // Some XML processors don't support these features, continue with basic security
            LOG.warn("Some XXE protection features not supported by this XML processor", e);
        }

        // Configure for safety
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        factory.setValidating(false);

        return factory.newDocumentBuilder();
    }

    /**
     * Creates a secure Transformer for XML output.
     *
     * <p>Configures UTF-8 encoding, pretty-printing, and disables external resources.
     *
     * @return Configured Transformer (never null)
     * @throws TransformerConfigurationException if critical configuration fails
     */
    @NotNull
    public static Transformer createSecureTransformer() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();

        try {
            // Secure the factory against external access
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

            LOG.debug("Transformer security features successfully configured");

        } catch (IllegalArgumentException e) {
            // Some transformers don't support these attributes
            LOG.warn("Some transformer security features not supported", e);
        }

        Transformer transformer = factory.newTransformer();

        // Configure output properties for readable XML
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");

        try {
            // Try to set indent amount (not all transformers support this)
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        } catch (IllegalArgumentException ignored) {
            // Not critical if not supported — transformer will still work
            LOG.debug("Indent amount property not supported by this transformer");
        }

        return transformer;
    }

    // =====================================================================
    // FILESYSTEM UTILITIES
    // =====================================================================

    /**
     * Recursively deletes directory and all its contents.
     *
     * <p>Deletes files before directories to handle dependencies correctly.
     * Logs warnings for individual failures but continues deletion.
     *
     * @param dir Directory to delete (cannot be null)
     * @throws IOException if directory walk fails
     */
    public static void deleteDirectoryContents(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir, "Directory path cannot be null");

        if (!Files.exists(dir)) {
            LOG.debug("Directory does not exist, skipping deletion: " + dir);
            return;
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(path -> !path.equals(dir))
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOG.debug("Deleted: " + path);
                        } catch (IOException e) {
                            LOG.warn("Could not delete " + path, e);
                        }
                    });
        }

        LOG.info("Deleted directory contents: " + dir);
    }

    /**
     * Selectively cleans work directory, preserving session files.
     *
     * <p>Removes temporary files but preserves .ser (serialized session) files.
     * Handles individual file failures gracefully.
     *
     * @param workDir Work directory to clean (cannot be null)
     * @throws IOException if directory walk fails
     */
    public static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        Objects.requireNonNull(workDir, "Work directory path cannot be null");

        if (!Files.exists(workDir)) {
            LOG.debug("Work directory does not exist, skipping cleanup: " + workDir);
            return;
        }

        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".ser")) // Preserve session files
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOG.debug("Cleaned: " + path);
                        } catch (IOException e) {
                            LOG.warn("Could not delete " + path, e);
                        }
                    });
        }

        LOG.info("Cleaned work directory: " + workDir);
    }

    /**
     * Checks if a directory is empty or doesn't exist.
     *
     * <p>Returns true if directory doesn't exist or contains no files.
     * Never throws exceptions — returns true on error.
     *
     * @param dir Directory to check (cannot be null)
     * @return true if directory is empty or doesn't exist
     */
    public static boolean isEmptyDirectory(@NotNull Path dir) {
        Objects.requireNonNull(dir, "Directory path cannot be null");

        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                LOG.debug("Directory does not exist or is not a directory: " + dir);
                return true;
            }

            try (Stream<Path> entries = Files.list(dir)) {
                boolean isEmpty = !entries.findFirst().isPresent();
                LOG.debug("Directory " + dir + " is " + (isEmpty ? "empty" : "not empty"));
                return isEmpty;
            }

        } catch (IOException e) {
            LOG.warn("Could not check if directory is empty: " + dir, e);
            return true; // Assume empty on error for safety
        }
    }

    /**
     * Sanitizes a name for safe use in file paths.
     *
     * <p>Removes or replaces special characters that could cause filesystem issues.
     * Replaces problematic characters with underscores.
     *
     * @param name Name to sanitize (can be null)
     * @param defaultValue Default value if name is null/empty (cannot be null)
     * @return Sanitized name safe for filesystem use (never null)
     * @throws NullPointerException if defaultValue is null
     */
    @NotNull
    public static String sanitizeName(@Nullable String name, @NotNull String defaultValue) {
        Objects.requireNonNull(defaultValue, "Default value cannot be null");

        if (name == null || name.trim().isEmpty()) {
            LOG.debug("Name is null or empty, using default: " + defaultValue);
            return defaultValue;
        }

        // Replace problematic characters with underscore
        String sanitized = name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        LOG.debug("Sanitized name from '" + name + "' to '" + sanitized + "'");

        return sanitized;
    }

    /**
     * Creates directory if it doesn't exist.
     *
     * <p>Creates parent directories as needed.
     * No-op if directory already exists.
     *
     * @param dir Directory to create (cannot be null)
     * @return true if directory was created, false if it already existed
     * @throws IOException if directory creation fails
     */
    public static boolean createDirectoryIfNotExists(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir, "Directory path cannot be null");

        if (Files.exists(dir) && Files.isDirectory(dir)) {
            LOG.debug("Directory already exists: " + dir);
            return false;
        }

        Files.createDirectories(dir);
        LOG.info("Created directory: " + dir);
        return true;
    }

    /**
     * Get directory size in bytes.
     *
     * <p>Recursively calculates total size of all files in directory.
     * Returns 0 if directory doesn't exist.
     *
     * @param dir Directory to measure (cannot be null)
     * @return total size in bytes
     * @throws IOException if walking directory fails
     */
    public static long getDirectorySize(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir, "Directory path cannot be null");

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            LOG.debug("Directory does not exist or is not a directory: " + dir);
            return 0;
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            LOG.warn("Could not get size of " + path, e);
                            return 0;
                        }
                    })
                    .sum();
        }
    }
}