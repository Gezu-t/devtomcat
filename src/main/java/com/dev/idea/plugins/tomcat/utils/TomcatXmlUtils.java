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
import java.util.stream.Stream;

/**
 * XML and filesystem utilities for DevTomcat plugin.
 *
 * This class provides:
 * - Secure XML document handling with XXE protection
 * - Filesystem operations for Tomcat directories
 * - Directory cleanup utilities
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class TomcatXmlUtils {

    private static final Logger LOG = Logger.getInstance(TomcatXmlUtils.class);

    private TomcatXmlUtils() {
        // Utility class
    }

    // =====================================================================
    // XML UTILITIES (SECURE)
    // =====================================================================

    /**
     * Creates a secure DocumentBuilder with XXE protection.
     *
     * @return Configured DocumentBuilder
     * @throws ParserConfigurationException if configuration fails
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
        } catch (IllegalArgumentException e) {
            // Some XML processors don't support these features, continue with basic security
            LOG.warn("Some XXE protection features not supported", e);
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
     * @return Configured Transformer
     * @throws TransformerConfigurationException if configuration fails
     */
    @NotNull
    public static Transformer createSecureTransformer() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();

        try {
            // Secure the factory
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException e) {
            // Some transformers don't support these attributes
            LOG.warn("Some transformer security features not supported", e);
        }

        Transformer transformer = factory.newTransformer();

        // Configure output properties
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");

        try {
            // Try to set indent amount (not all transformers support this)
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        } catch (IllegalArgumentException ignored) {
            // Not critical if not supported
        }

        return transformer;
    }

    // =====================================================================
    // FILESYSTEM UTILITIES
    // =====================================================================

    /**
     * Recursively deletes directory contents.
     *
     * @param dir Directory to clean
     * @throws IOException if deletion fails
     */
    public static void deleteDirectoryContents(@NotNull Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(path -> !path.equals(dir))
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOG.warn("Could not delete " + path, e);
                        }
                    });
        }
    }

    /**
     * Selectively cleans work directory, preserving session files.
     *
     * @param workDir Work directory to clean
     * @throws IOException if cleaning fails
     */
    public static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        if (!Files.exists(workDir)) return;

        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".ser")) // Preserve session files
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOG.warn("Could not delete " + path, e);
                        }
                    });
        }
    }

    /**
     * Checks if a directory is empty.
     *
     * @param dir Directory to check
     * @return true if directory doesn't exist or is empty
     */
    public static boolean isEmptyDirectory(@NotNull Path dir) {
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return true;
            }

            try (Stream<Path> entries = Files.list(dir)) {
                return !entries.findFirst().isPresent();
            }

        } catch (IOException e) {
            LOG.warn("Could not check if directory is empty: " + dir, e);
            return true;
        }
    }

    /**
     * Sanitizes a name for use in file paths.
     *
     * @param name Name to sanitize
     * @param defaultValue Default value if name is empty
     * @return Sanitized name safe for filesystem use
     */
    @NotNull
    public static String sanitizeName(@Nullable String name, @NotNull String defaultValue) {
        if (name == null || name.trim().isEmpty()) {
            return defaultValue;
        }

        // Replace problematic characters with underscore
        return name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}