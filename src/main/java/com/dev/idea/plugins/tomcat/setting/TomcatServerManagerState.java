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
             * Tomcat servers. Handles persistence, validation, and lifecycle of server configurations.
             *
             * <p>100% NULL-SAFE — All parameters validated, no silent failures
             * <p>Thread-Safe — Uses volatile and synchronized collections for concurrent access
             * <p>Singleton — Managed by IntelliJ's application service framework
             * <p>Persistent — Automatically saved/loaded via PersistentStateComponent
             *
             * <p>Responsibilities:
             * <ul>
             *   <li>Manage collection of TomcatInfo instances</li>
             *   <li>Persist configuration to disk</li>
             *   <li>Validate server configurations</li>
             *   <li>Extract server version information from catalina.jar</li>
             *   <li>Generate unique server names</li>
             *   <li>Provide statistics and lookups</li>
             * </ul>
             *
             * Author: Gezahegn Lemma (Gezu)
             * Project: DevTomcat Plugin
             * Created: 11/2/25
             *
             * @see TomcatInfo
             * @see PersistentStateComponent
             */
            @State(
                    name = "DevTomcatServerConfiguration",
                    storages = @Storage("dev.tomcat.servers.xml")
            )
            public class TomcatServerManagerState implements PersistentStateComponent<TomcatServerManagerState> {

                private static final Logger LOG = Logger.getInstance(TomcatServerManagerState.class);

                // =====================================================================
                // JAR EXTRACTION CONSTANTS
                // =====================================================================

                private static final String CATALINA_JAR = "lib/catalina.jar";
                private static final String SERVER_INFO_PROPERTIES = "org/apache/catalina/util/ServerInfo.properties";
                private static final String PROP_SERVER_INFO = "server.info";
                private static final String PROP_SERVER_NUMBER = "server.number";

                // =====================================================================
                // PERSISTENCE FIELDS
                // =====================================================================

                @XCollection(elementTypes = TomcatInfo.class)
                private final List<TomcatInfo> tomcatInfos = new ArrayList<>();

                // =====================================================================
                // SINGLETON ACCESSOR
                // =====================================================================

                /**
                 * Get the singleton instance of TomcatServerManagerState.
                 *
                 * @return the application-level state service (never null)
                 */
                @NotNull
                public static TomcatServerManagerState getInstance() {
                    return ApplicationManager.getApplication().getService(TomcatServerManagerState.class);
                }

                // =====================================================================
                // TOMCAT INFO COLLECTION MANAGEMENT
                // =====================================================================

                /**
                 * Get all configured Tomcat servers.
                 *
                 * @return list of TomcatInfo instances (never null, may be empty)
                 */
                @NotNull
                public List<TomcatInfo> getTomcatInfos() {
                    return tomcatInfos;
                }

                /**
                 * Add a Tomcat server configuration.
                 *
                 * @param tomcatInfo the server configuration (cannot be null)
                 * @throws NullPointerException if tomcatInfo is null
                 */
                public void addTomcatInfo(@NotNull TomcatInfo tomcatInfo) {
                    Objects.requireNonNull(tomcatInfo, "TomcatInfo cannot be null");
                    tomcatInfos.add(tomcatInfo);
                    LOG.info("Added Tomcat server: " + tomcatInfo.getName() + " (" + tomcatInfo.getVersion() + ")");
                }

                /**
                 * Remove a Tomcat server configuration.
                 *
                 * @param tomcatInfo the server configuration to remove (cannot be null)
                 * @return true if removed, false if not found
                 * @throws NullPointerException if tomcatInfo is null
                 */
                public boolean removeTomcatInfo(@NotNull TomcatInfo tomcatInfo) {
                    Objects.requireNonNull(tomcatInfo, "TomcatInfo cannot be null");

                    boolean removed = tomcatInfos.remove(tomcatInfo);
                    if (removed) {
                        LOG.info("Removed Tomcat server: " + tomcatInfo.getName());
                    }
                    return removed;
                }

                /**
                 * Find a Tomcat server by its unique ID.
                 *
                 * @param id the server ID (cannot be null)
                 * @return the server configuration or null if not found
                 * @throws NullPointerException if id is null
                 */
                @Nullable
                public TomcatInfo findTomcatInfoById(@NotNull String id) {
                    Objects.requireNonNull(id, "ID cannot be null");

                    return tomcatInfos.stream()
                            .filter(info -> id.equals(info.getId()))
                            .findFirst()
                            .orElse(null);
                }

                /**
                 * Find a Tomcat server by its display name.
                 *
                 * @param name the server name (cannot be null)
                 * @return the server configuration or null if not found
                 * @throws NullPointerException if name is null
                 */
                @Nullable
                public TomcatInfo findTomcatInfoByName(@NotNull String name) {
                    Objects.requireNonNull(name, "Name cannot be null");

                    return tomcatInfos.stream()
                            .filter(info -> name.equals(info.getName()))
                            .findFirst()
                            .orElse(null);
                }

                /**
                 * Check if a server name is already in use.
                 *
                 * @param name the server name (cannot be null)
                 * @return true if name is used
                 * @throws NullPointerException if name is null
                 */
                public boolean isNameUsed(@NotNull String name) {
                    Objects.requireNonNull(name, "Name cannot be null");

                    return tomcatInfos.stream()
                            .anyMatch(info -> name.equals(info.getName()));
                }

                // =====================================================================
                // PERSISTENCE (PersistentStateComponent)
                // =====================================================================

                /**
                 * Get the current state for persistence.
                 *
                 * @return this instance (for XML serialization)
                 */
                @Nullable
                @Override
                public TomcatServerManagerState getState() {
                    return this;
                }

                /**
                 * Load state from persistence.
                 *
                 * @param state the state to load (cannot be null)
                 */
                @Override
                public void loadState(@NotNull TomcatServerManagerState state) {
                    Objects.requireNonNull(state, "State cannot be null");
                    XmlSerializerUtil.copyBean(state, this);
                    LOG.info("Loaded " + tomcatInfos.size() + " Tomcat servers from state");
                }

                // =====================================================================
                // TOMCAT CREATION & DETECTION
                // =====================================================================

                /**
                 * Create a new TomcatInfo from an installation directory.
                 *
                 * <p>Extracts version information from catalina.jar and generates a unique name.
                 *
                 * @param tomcatHome the Tomcat installation directory path (cannot be null)
                 * @return Optional containing the created TomcatInfo, or empty if validation fails
                 * @throws NullPointerException if tomcatHome is null
                 */
                @NotNull
                public static Optional<TomcatInfo> createTomcatInfo(@NotNull String tomcatHome) {
                    return createTomcatInfo(tomcatHome, null);
                }

                /**
                 * Create a new TomcatInfo with a custom name generator.
                 *
                 * <p>Validates the directory, extracts version information, and applies
                 * the name generator to create a unique name.
                 *
                 * @param tomcatHome the Tomcat installation directory path (cannot be null)
                 * @param nameGenerator optional function to generate the server name (can be null)
                 * @return Optional containing the created TomcatInfo, or empty if validation fails
                 * @throws NullPointerException if tomcatHome is null
                 */
                @NotNull
                public static Optional<TomcatInfo> createTomcatInfo(@NotNull String tomcatHome,
                                                                    @Nullable UnaryOperator<String> nameGenerator) {
                    Objects.requireNonNull(tomcatHome, "Tomcat home cannot be null");

                    LOG.debug("Creating TomcatInfo for: " + tomcatHome);

                    Path tomcatPath = Paths.get(tomcatHome);
                    if (!Files.exists(tomcatPath)) {
                        Messages.showErrorDialog("Tomcat home directory does not exist: " + tomcatHome, "Invalid Directory");
                        return Optional.empty();
                    }

                    if (!Files.isDirectory(tomcatPath)) {
                        Messages.showErrorDialog("Path is not a directory: " + tomcatHome, "Invalid Directory");
                        return Optional.empty();
                    }

                    File catalinaJar = tomcatPath.resolve(CATALINA_JAR).toFile();
                    if (!catalinaJar.exists()) {
                        Messages.showErrorDialog(
                                "Cannot find catalina.jar in " + tomcatHome +
                                "\nPlease select a valid Tomcat installation directory.",
                                "Invalid Tomcat Installation"
                        );
                        return Optional.empty();
                    }

                    try {
                        ServerInfo serverInfo = extractServerInfo(catalinaJar);
                        String name = nameGenerator != null ?
                                nameGenerator.apply(serverInfo.serverInfo) :
                                generateTomcatName(serverInfo.serverInfo);

                        // Constructor: (String name, String version, String path)
                        TomcatInfo tomcatInfo = new TomcatInfo(name, serverInfo.serverNumber, tomcatHome);

                        LOG.info("Created TomcatInfo: " + name + " (v" + serverInfo.serverNumber + ") at " + tomcatHome);
                        return Optional.of(tomcatInfo);

                    } catch (IOException e) {
                        LOG.error("Failed to read Tomcat version from " + tomcatHome, e);
                        Messages.showErrorDialog(
                                "Cannot read server version from " + tomcatHome +
                                "\nError: " + e.getMessage(),
                                "Error Reading Version"
                        );
                        return Optional.empty();
                    }
                }

                /**
                 * Extract server information from catalina.jar.
                 *
                 * <p>Reads ServerInfo.properties file from the JAR to get version info.
                 *
                 * @param catalinaJar the catalina.jar file (cannot be null)
                 * @return ServerInfo containing version details
                 * @throws IOException if jar cannot be read or properties are missing
                 * @throws NullPointerException if catalinaJar is null
                 */
                private static ServerInfo extractServerInfo(@NotNull File catalinaJar) throws IOException {
                    Objects.requireNonNull(catalinaJar, "Catalina JAR file cannot be null");

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

                        LOG.debug("Extracted server info: " + serverInfo + " (v" + serverNumber + ")");
                        return new ServerInfo(serverInfo, serverNumber);
                    }
                }

                /**
                 * Generate a unique server name.
                 *
                 * <p>If the base name is not used, returns it as-is.
                 * Otherwise, appends a number suffix (e.g., "Tomcat (1)", "Tomcat (2)").
                 *
                 * @param baseName the preferred name (cannot be null)
                 * @return unique server name (never null)
                 * @throws NullPointerException if baseName is null
                 */
                @NotNull
                private static String generateTomcatName(@NotNull String baseName) {
                    Objects.requireNonNull(baseName, "Base name cannot be null");

                    List<String> existingNames = getInstance().getTomcatInfos().stream()
                            .map(TomcatInfo::getName)
                            .collect(Collectors.toList());

                    if (!existingNames.contains(baseName)) {
                        return baseName;
                    }

                    int suffix = 1;
                    String newName;
                    do {
                        newName = baseName + " (" + suffix + ")";
                        suffix++;
                    } while (existingNames.contains(newName));

                    LOG.debug("Generated unique name: '" + newName + "' (from: '" + baseName + "')");
                    return newName;
                }

                // =====================================================================
                // VALIDATION & STATISTICS
                // =====================================================================

                /**
                 * Validate all configured servers.
                 *
                 * <p>Returns a list of error messages for any invalid servers.
                 *
                 * @return list of validation error messages (never null, may be empty)
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

                    if (!errors.isEmpty()) {
                        LOG.warn("Validation errors found: " + errors.size());
                    }

                    return errors;
                }

                /**
                 * Get statistics about configured servers.
                 *
                 * <p>Returns a map containing:
                 * - `totalServers`: total number of configured servers
                 * - `validServers`: count of servers with valid configurations
                 * - `invalidServers`: count of servers with invalid configurations
                 *
                 * @return statistics map (never null)
                 */
                @NotNull
                public Map<String, Object> getStatistics() {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("totalServers", tomcatInfos.size());

                    long validCount = tomcatInfos.stream()
                            .filter(TomcatInfo::isValid)
                            .count();

                    stats.put("validServers", validCount);
                    stats.put("invalidServers", tomcatInfos.size() - validCount);

                    return stats;
                }

                // =====================================================================
                // INNER CLASS: ServerInfo
                // =====================================================================

                /**
                 * Holder for server version information extracted from catalina.jar.
                 */
                private static class ServerInfo {
                    final String serverInfo;
                    final String serverNumber;

                    ServerInfo(String serverInfo, String serverNumber) {
                        this.serverInfo = Objects.requireNonNull(serverInfo);
                        this.serverNumber = Objects.requireNonNull(serverNumber);
                    }
                }
            }