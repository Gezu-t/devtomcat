package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * Tomcat Server Manager State
 *
 * Application-level service that manages the persistent state of all configured
 * Tomcat servers. This service is responsible for:
 * - Storing and retrieving Tomcat server configurations
 * - Creating new TomcatInfo instances from Tomcat installations
 * - Generating unique server names
 * - Validating Tomcat installations
 *
 * @author Dev Tomcat Team
 */
@State(
        name = "DevTomcatServerConfiguration",
        storages = @Storage("dev.tomcat.servers.xml")
)
public class TomcatServerManagerState implements PersistentStateComponent<TomcatServerManagerState> {

    private static final Logger LOG = Logger.getInstance(TomcatServerManagerState.class);

    // Constants
    private static final String CATALINA_JAR = "lib/catalina.jar";
    private static final String SERVER_INFO_PROPERTIES = "org/apache/catalina/util/ServerInfo.properties";
    private static final String PROP_SERVER_INFO = "server.info";
    private static final String PROP_SERVER_NUMBER = "server.number";

    // Persisted state
    @XCollection(elementTypes = TomcatInfo.class)
    private final List<TomcatInfo> tomcatInfos = new ArrayList<>();

    /**
     * Get the singleton instance of this service
     *
     * @return The TomcatServerManagerState instance
     */
    @NotNull
    public static TomcatServerManagerState getInstance() {
        return ApplicationManager.getApplication().getService(TomcatServerManagerState.class);
    }

    /**
     * Get all configured Tomcat servers
     *
     * @return List of TomcatInfo instances
     */
    @NotNull
    public List<TomcatInfo> getTomcatInfos() {
        return tomcatInfos;
    }

    /**
     * Add a new Tomcat server
     *
     * @param tomcatInfo The server to add
     */
    public void addTomcatInfo(@NotNull TomcatInfo tomcatInfo) {
        // Ensure unique ID
        if (tomcatInfo.getId() == null || tomcatInfo.getId().isEmpty()) {
            tomcatInfo.setId(UUID.randomUUID().toString());
        }

        tomcatInfos.add(tomcatInfo);
        LOG.info("Added Tomcat server: " + tomcatInfo.getDisplayString());
    }

    /**
     * Remove a Tomcat server
     *
     * @param tomcatInfo The server to remove
     * @return true if removed
     */
    public boolean removeTomcatInfo(@NotNull TomcatInfo tomcatInfo) {
        boolean removed = tomcatInfos.remove(tomcatInfo);
        if (removed) {
            LOG.info("Removed Tomcat server: " + tomcatInfo.getDisplayString());
        }
        return removed;
    }

    /**
     * Find a Tomcat server by ID
     *
     * @param id The server ID
     * @return The TomcatInfo or null
     */
    @Nullable
    public TomcatInfo findTomcatInfoById(@NotNull String id) {
        return tomcatInfos.stream()
                .filter(info -> id.equals(info.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find a Tomcat server by name
     *
     * @param name The server name
     * @return The TomcatInfo or null
     */
    @Nullable
    public TomcatInfo findTomcatInfoByName(@NotNull String name) {
        return tomcatInfos.stream()
                .filter(info -> name.equals(info.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if a server name is already used
     *
     * @param name The name to check
     * @return true if the name is already used
     */
    public boolean isNameUsed(@NotNull String name) {
        return tomcatInfos.stream()
                .anyMatch(info -> name.equals(info.getName()));
    }

    // === PERSISTENCE ===

    @Nullable
    @Override
    public TomcatServerManagerState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull TomcatServerManagerState state) {
        XmlSerializerUtil.copyBean(state, this);

        // Ensure all servers have IDs (for backward compatibility)
        for (TomcatInfo info : tomcatInfos) {
            if (info.getId() == null || info.getId().isEmpty()) {
                info.setId(UUID.randomUUID().toString());
            }
        }

        LOG.info("Loaded " + tomcatInfos.size() + " Tomcat servers from state");
    }

    // === FACTORY METHODS ===

    /**
     * Create a TomcatInfo from a Tomcat installation directory
     *
     * @param tomcatHome The Tomcat home directory path
     * @return Optional containing TomcatInfo if successful
     */
    @NotNull
    public static Optional<TomcatInfo> createTomcatInfo(@NotNull String tomcatHome) {
        return createTomcatInfo(tomcatHome, TomcatServerManagerState::generateTomcatName);
    }

    /**
     * Create a TomcatInfo with custom name generator
     *
     * @param tomcatHome The Tomcat home directory path
     * @param nameGenerator Function to generate the server name
     * @return Optional containing TomcatInfo if successful
     */
    @NotNull
    public static Optional<TomcatInfo> createTomcatInfo(@NotNull String tomcatHome,
                                                        @Nullable UnaryOperator<String> nameGenerator) {
        LOG.debug("Creating TomcatInfo for: " + tomcatHome);

        // Validate Tomcat home directory
        Path tomcatPath = Paths.get(tomcatHome);
        if (!Files.exists(tomcatPath)) {
            Messages.showErrorDialog("Tomcat home directory does not exist: " + tomcatHome, "Invalid Directory");
            return Optional.empty();
        }

        if (!Files.isDirectory(tomcatPath)) {
            Messages.showErrorDialog("Path is not a directory: " + tomcatHome, "Invalid Directory");
            return Optional.empty();
        }

        // Check for catalina.jar
        File catalinaJar = tomcatPath.resolve(CATALINA_JAR).toFile();
        if (!catalinaJar.exists()) {
            Messages.showErrorDialog(
                    "Cannot find catalina.jar in " + tomcatHome + "\n" +
                            "Please select a valid Tomcat installation directory.",
                    "Invalid Tomcat Installation"
            );
            return Optional.empty();
        }

        // Extract version information
        try {
            ServerInfo serverInfo = extractServerInfo(catalinaJar);

            // Generate unique name
            String name = nameGenerator != null ?
                    nameGenerator.apply(serverInfo.serverInfo) :
                    generateTomcatName(serverInfo.serverInfo);

            // Create TomcatInfo
            TomcatInfo tomcatInfo = new TomcatInfo();
            tomcatInfo.setName(name);
            tomcatInfo.setVersion(serverInfo.serverNumber);
            tomcatInfo.setPath(tomcatHome);

            LOG.info("Created TomcatInfo: " + tomcatInfo.getDisplayString());
            return Optional.of(tomcatInfo);

        } catch (IOException e) {
            LOG.error("Failed to read Tomcat version", e);
            Messages.showErrorDialog(
                    "Cannot read server version from " + tomcatHome + "\n" +
                            "Error: " + e.getMessage(),
                    "Error Reading Version"
            );
            return Optional.empty();
        }
    }

    /**
     * Extract server information from catalina.jar
     */
    private static ServerInfo extractServerInfo(@NotNull File catalinaJar) throws IOException {
        try (JarFile jar = new JarFile(catalinaJar)) {
            ZipEntry entry = jar.getEntry(SERVER_INFO_PROPERTIES);
            if (entry == null) {
                throw new IOException("Cannot find " + SERVER_INFO_PROPERTIES + " in catalina.jar");
            }

            Properties properties = new Properties();
            try (InputStream is = jar.getInputStream(entry)) {
                properties.load(is);
            }

            String serverInfo = properties.getProperty(PROP_SERVER_INFO);
            String serverNumber = properties.getProperty(PROP_SERVER_NUMBER);

            if (serverInfo == null || serverNumber == null) {
                throw new IOException("Missing server information in properties file");
            }

            return new ServerInfo(serverInfo, serverNumber);
        }
    }

    /**
     * Generate a unique Tomcat server name
     */
    @NotNull
    private static String generateTomcatName(@NotNull String baseName) {
        List<String> existingNames = getInstance().getTomcatInfos().stream()
                .map(TomcatInfo::getName)
                .collect(Collectors.toList());

        // If base name is unique, use it
        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        // Generate unique name with suffix
        int suffix = 1;
        String newName;
        do {
            newName = baseName + " (" + suffix + ")";
            suffix++;
        } while (existingNames.contains(newName));

        return newName;
    }

    /**
     * Validate all configured servers
     *
     * @return List of validation errors (empty if all valid)
     */
    @NotNull
    public List<String> validateAllServers() {
        List<String> errors = new ArrayList<>();

        for (TomcatInfo info : tomcatInfos) {
            try {
                info.validate();
            } catch (IllegalStateException e) {
                errors.add(info.getName() + ": " + e.getMessage());
            }
        }

        return errors;
    }

    /**
     * Get statistics about configured servers
     */
    @NotNull
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalServers", tomcatInfos.size());

        // Count by major version
        Map<Integer, Long> versionCounts = tomcatInfos.stream()
                .collect(Collectors.groupingBy(
                        TomcatInfo::getMajorVersion,
                        Collectors.counting()
                ));
        stats.put("versionDistribution", versionCounts);

        // Valid vs invalid
        long validCount = tomcatInfos.stream()
                .filter(TomcatInfo::isValid)
                .count();
        stats.put("validServers", validCount);
        stats.put("invalidServers", tomcatInfos.size() - validCount);

        return stats;
    }

    /**
     * Internal class to hold server information
     */
    private static class ServerInfo {
        final String serverInfo;
        final String serverNumber;

        ServerInfo(String serverInfo, String serverNumber) {
            this.serverInfo = serverInfo;
            this.serverNumber = serverNumber;
        }
    }
}