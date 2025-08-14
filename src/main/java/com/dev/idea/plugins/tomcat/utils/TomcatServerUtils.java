package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.dev.idea.plugins.tomcat.setting.TomcatServersConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tomcat server management utilities for DevTomcat plugin.
 *
 * This class handles:
 * - Tomcat installation selection and validation
 * - Server configuration management
 * - Port validation and conflict detection
 * - Version detection and compatibility
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class TomcatServerUtils {

    private static final Logger LOG = Logger.getInstance(TomcatServerUtils.class);

    // Port constraints
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    // Default Tomcat ports
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_HTTPS_PORT = 8443;
    public static final int DEFAULT_AJP_PORT = 8009;
    public static final int DEFAULT_SHUTDOWN_PORT = 8005;
    public static final int DEFAULT_JMX_PORT = 1099;
    public static final int DEFAULT_DEBUG_PORT = 5005;

    // Version detection patterns
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern DIR_VERSION_PATTERN = Pattern.compile("tomcat-?(\\d+\\.\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private TomcatServerUtils() {
        // Utility class
    }

    /**
     * Opens a file chooser dialog to select a Tomcat installation.
     *
     * @param callback Called with TomcatInfo when a valid installation is selected
     */
    public static void selectTomcatInstallation(@NotNull Consumer<TomcatInfo> callback) {
        selectTomcatInstallation(null, callback);
    }

    /**
     * Opens a file chooser dialog to select a Tomcat installation with custom naming.
     *
     * @param nameGenerator Optional function to generate custom server names
     * @param callback Called with TomcatInfo when a valid installation is selected
     */
    public static void selectTomcatInstallation(@Nullable UnaryOperator<String> nameGenerator,
                                                @NotNull Consumer<TomcatInfo> callback) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Tomcat Installation")
                .withDescription("Choose the Tomcat home directory (e.g., /path/to/apache-tomcat-9.0.56)")
                .withShowHiddenFiles(false);

        SwingUtilities.invokeLater(() -> {
            VirtualFile chosen = FileChooser.chooseFile(descriptor, null, null);
            if (chosen != null) {
                String homePath = chosen.getPath();

                try {
                    validateTomcatHome(homePath);

                    // Create TomcatInfo
                    Optional<TomcatInfo> tomcatInfo = TomcatServerManagerState.createTomcatInfo(homePath, nameGenerator);
                    tomcatInfo.ifPresent(callback);

                } catch (ConfigurationException e) {
                    Messages.showErrorDialog(
                            e.getMessage(),
                            "Invalid Tomcat Installation"
                    );
                }
            }
        });
    }

    /**
     * Validates that a directory contains a valid Tomcat installation.
     *
     * @param homePath Path to validate
     * @throws ConfigurationException if the path is not a valid Tomcat installation
     */
    public static void validateTomcatHome(@NotNull String homePath) throws ConfigurationException {
        if (StringUtil.isEmpty(homePath)) {
            throw new ConfigurationException("Tomcat home path cannot be empty");
        }

        Path home = Paths.get(homePath);

        if (!Files.exists(home)) {
            throw new ConfigurationException("Tomcat home directory does not exist: " + homePath);
        }

        if (!Files.isDirectory(home)) {
            throw new ConfigurationException("Tomcat home path is not a directory: " + homePath);
        }

        // Check required directories
        checkRequiredDirectory(home, "bin", "Tomcat bin directory");
        checkRequiredDirectory(home, "conf", "Tomcat conf directory");
        checkRequiredDirectory(home, "lib", "Tomcat lib directory");
        checkRequiredDirectory(home, "webapps", "Tomcat webapps directory");

        // Check for essential files
        checkRequiredFile(home, "bin/catalina.sh", "bin/catalina.bat", "Tomcat startup script");
        checkRequiredFile(home, "lib/catalina.jar", null, "Tomcat catalina.jar");
        checkRequiredFile(home, "conf/server.xml", null, "Tomcat server.xml");

        LOG.info("Valid Tomcat installation found at: " + homePath);
    }

    /**
     * Detects the Tomcat version from an installation directory.
     *
     * @param homePath Path to Tomcat home
     * @return Detected version string (e.g., "9.0.56")
     */
    @NotNull
    public static String detectTomcatVersion(@NotNull String homePath) {
        Path home = Paths.get(homePath);

        // Try to read from RELEASE-NOTES
        String version = detectVersionFromReleaseNotes(home);
        if (version != null) {
            return version;
        }

        // Try to parse from directory name
        version = detectVersionFromDirectoryName(home);
        if (version != null) {
            return version;
        }

        // Try to read from lib/catalina.jar manifest
        version = detectVersionFromManifest(home);
        if (version != null) {
            return version;
        }

        LOG.warn("Could not detect Tomcat version for: " + homePath);
        return "9.0"; // Default fallback
    }

    /**
     * Generates a unique name for a server instance.
     *
     * @param existingNames List of already used names
     * @param baseName Preferred base name
     * @return Unique name (e.g., "Tomcat 9.0" or "Tomcat 9.0 (2)")
     */
    @NotNull
    public static String generateUniqueName(@NotNull List<String> existingNames, @NotNull String baseName) {
        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        // Find the highest number suffix
        int maxNumber = 0;
        Pattern pattern = Pattern.compile("^" + Pattern.quote(baseName) + " \\((\\d+)\\)$");

        for (String name : existingNames) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                int number = Integer.parseInt(matcher.group(1));
                maxNumber = Math.max(maxNumber, number);
            }
        }

        return String.format("%s (%d)", baseName, maxNumber + 1);
    }

    /**
     * Validates a port number from a string.
     *
     * @param portString String representation of port
     * @return Valid port number
     * @throws ConfigurationException if invalid
     */
    public static int validatePort(@NotNull String portString) throws ConfigurationException {
        return validatePort(portString, "Port");
    }

    /**
     * Validates a port number from a string with custom field name.
     *
     * @param portString String representation of port
     * @param fieldName Name of the field for error messages
     * @return Valid port number
     * @throws ConfigurationException if invalid
     */
    public static int validatePort(@NotNull String portString, @NotNull String fieldName)
            throws ConfigurationException {

        if (StringUtil.isEmpty(portString)) {
            throw new ConfigurationException(fieldName + " cannot be empty");
        }

        try {
            int port = Integer.parseInt(portString.trim());

            if (port < MIN_PORT || port > MAX_PORT) {
                throw new ConfigurationException(
                        String.format("%s must be between %d and %d", fieldName, MIN_PORT, MAX_PORT)
                );
            }

            return port;

        } catch (NumberFormatException e) {
            throw new ConfigurationException(fieldName + " must be a valid number");
        }
    }

    /**
     * Checks that two ports don't conflict.
     *
     * @param port1 First port
     * @param port2 Second port
     * @param port1Name Name of first port
     * @param port2Name Name of second port
     * @throws ConfigurationException if ports are the same
     */
    public static void checkPortConflicts(int port1, int port2,
                                          @NotNull String port1Name,
                                          @NotNull String port2Name)
            throws ConfigurationException {
        if (port1 == port2) {
            throw new ConfigurationException(
                    String.format("%s and %s cannot use the same port (%d)",
                            port1Name, port2Name, port1)
            );
        }
    }

    /**
     * Checks if a port is available for binding.
     *
     * @param port Port to check
     * @return true if the port is available
     */
    public static boolean isPortAvailable(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            return false;
        }

        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Formats a port for display with its service name.
     *
     * @param serviceName Name of the service (e.g., "HTTP", "JMX")
     * @param port Port number
     * @return Formatted string (e.g., "HTTP: 8080")
     */
    @NotNull
    public static String formatPortDisplay(@NotNull String serviceName, int port) {
        return String.format("%s: %d", serviceName, port);
    }

    /**
     * Opens the Tomcat Servers configuration dialog.
     */
    public static void openServerConfiguration() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
                null,
                TomcatServersConfigurable.class
        );
    }

    /**
     * Gets a suggested name for a Tomcat server based on its version.
     *
     * @param version Tomcat version
     * @return Suggested name (e.g., "Tomcat 9.0")
     */
    @NotNull
    public static String suggestServerName(@NotNull String version) {
        // Clean up version to major.minor
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            String major = matcher.group(1);
            String minor = matcher.group(2);
            return String.format("Tomcat %s.%s", major, minor);
        }
        return "Tomcat " + version;
    }

    /**
     * Checks if a Tomcat version is supported.
     *
     * @param version Version string to check
     * @return true if version is supported
     */
    public static boolean isSupportedVersion(@NotNull String version) {
        try {
            Matcher matcher = VERSION_PATTERN.matcher(version);
            if (matcher.find()) {
                int major = Integer.parseInt(matcher.group(1));
                return major >= 7; // Support Tomcat 7.0 and higher
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    // ===================== Private Helper Methods =====================

    private static void checkRequiredDirectory(@NotNull Path home, @NotNull String dir, @NotNull String description)
            throws ConfigurationException {
        Path dirPath = home.resolve(dir);
        if (!Files.exists(dirPath)) {
            throw new ConfigurationException(description + " not found: " + dirPath);
        }
        if (!Files.isDirectory(dirPath)) {
            throw new ConfigurationException(description + " is not a directory: " + dirPath);
        }
    }

    private static void checkRequiredFile(@NotNull Path home, @NotNull String primaryPath,
                                          @Nullable String alternatePath, @NotNull String description)
            throws ConfigurationException {
        Path primary = home.resolve(primaryPath);
        boolean found = Files.exists(primary) && Files.isRegularFile(primary);

        if (!found && alternatePath != null) {
            Path alternate = home.resolve(alternatePath);
            found = Files.exists(alternate) && Files.isRegularFile(alternate);
        }

        if (!found) {
            throw new ConfigurationException(description + " not found");
        }
    }

    @Nullable
    private static String detectVersionFromReleaseNotes(@NotNull Path home) {
        Path releaseNotes = home.resolve("RELEASE-NOTES");
        if (Files.exists(releaseNotes)) {
            try {
                String content = FileUtil.loadFile(releaseNotes.toFile());

                // Look for "Apache Tomcat Version X.Y.Z"
                Pattern pattern = Pattern.compile("Apache Tomcat Version (\\d+\\.\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(content);

                if (matcher.find()) {
                    String version = matcher.group(1);
                    LOG.info("Detected Tomcat version from RELEASE-NOTES: " + version);
                    return version;
                }
            } catch (IOException e) {
                LOG.debug("Failed to read RELEASE-NOTES", e);
            }
        }
        return null;
    }

    @Nullable
    private static String detectVersionFromDirectoryName(@NotNull Path home) {
        String dirName = home.getFileName().toString();
        Matcher matcher = DIR_VERSION_PATTERN.matcher(dirName);

        if (matcher.find()) {
            String version = matcher.group(1);
            LOG.info("Detected Tomcat version from directory name: " + version);
            return version;
        }

        return null;
    }

    @Nullable
    private static String detectVersionFromManifest(@NotNull Path home) {
        // This would require reading the JAR manifest
        // For now, returning null as it's complex to implement
        return null;
    }
}