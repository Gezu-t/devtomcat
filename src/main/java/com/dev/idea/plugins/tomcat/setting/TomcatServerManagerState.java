package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;

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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Application-level persistent state for every Tomcat server the user has registered.
 *
 * <p><b>Concurrency.</b> The backing list is a {@link CopyOnWriteArrayList} so any
 * reader (UI table, run-config server picker, validation pass) gets a stable snapshot
 * without synchronisation while writers (add / remove / setState during load) mutate
 * through the COW path. No reader ever needs a lock; no iterator ever ConcurrentModifies.
 *
 * <p><b>Version extraction.</b> Reading the version from a Tomcat install opens
 * {@code lib/catalina.jar}, walks to {@code org/apache/catalina/util/ServerInfo.properties},
 * and parses {@code server.info} / {@code server.number}. The two values are bundled into
 * the inner {@link ServerInfo} record. Reflection-free path; works on every Tomcat
 * 5+ ship.
 *
 * <p><b>Persistence.</b> {@link PersistentStateComponent} serialises this whole class
 * as XML into {@code dev.tomcat.servers.xml} under the IDE config dir. {@link XCollection}
 * on {@link #tomcatInfos} keeps the list as a flat {@code <option name="…">} sequence so
 * users can diff their stored servers cleanly.
 *
 * @see TomcatInfo
 */
@Service(Service.Level.APP)
@State(
        name = "DevTomcatServerConfiguration",
        storages = @Storage("dev.tomcat.servers.xml")
)
public final class TomcatServerManagerState implements PersistentStateComponent<TomcatServerManagerState> {

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
    private final List<TomcatInfo> tomcatInfos = new CopyOnWriteArrayList<>();

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
     * @return unmodifiable snapshot of TomcatInfo instances (never null, may be empty)
     */
    @NotNull
    public List<TomcatInfo> getTomcatInfos() {
        return Collections.unmodifiableList(new ArrayList<>(tomcatInfos));
    }

    /**
     * Replace all configured Tomcat servers.
     *
     * @param infos the new list of server configurations (cannot be null)
     * @throws NullPointerException if infos is null
     */
    public void setTomcatInfos(@NotNull List<TomcatInfo> infos) {
        tomcatInfos.clear();
        tomcatInfos.addAll(infos);
        LOG.info("Replaced all Tomcat servers: " + infos.size() + " servers");
    }

    /**
     * Add a Tomcat server configuration.
     *
     * @param tomcatInfo the server configuration (cannot be null)
     * @throws NullPointerException if tomcatInfo is null
     */
    public void addTomcatInfo(@NotNull TomcatInfo tomcatInfo) {
        tomcatInfos.add(tomcatInfo);
        LOG.info("Added Tomcat server: " + tomcatInfo.getName() + " (" + tomcatInfo.getVersion() + ")");
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

        return tomcatInfos.stream()
                .anyMatch(info -> name.equals(info.getName()));
    }

    @Nullable
    public TomcatInfo findTomcatInfoByPath(@NotNull String path) {

        return tomcatInfos.stream()
                .filter(info -> path.equals(info.getPath()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolve a persisted {@link TomcatInfo} reference to the canonical registered instance.
     *
     * <p>A run configuration carries an embedded {@code TomcatInfo} snapshot so it can survive
     * cross-machine transport (VCS imports). That snapshot can drift from the registered
     * state — IDs regenerate when users remove-and-re-add, paths change after reinstalls,
     * and hand-edited XML is always possible. Every caller that reads a run configuration's
     * server reference must go through this resolver so the UI (Server-tab combo),
     * validator, runtime launch path, and dashboard all agree on a single interpretation.
     *
     * <p>Match priority:
     * <ol>
     *   <li><b>ID</b> — exact match. Authoritative when present.</li>
     *   <li><b>Path</b> — exact, then normalized ({@code toAbsolutePath().normalize()}),
     *       then {@code Files.isSameFile} when both exist on disk. Catches trailing
     *       slashes, symlinks, and {@code ..} segments.</li>
     *   <li><b>Name</b> — last resort for legacy configs with no ID and a path that
     *       has since moved.</li>
     * </ol>
     *
     * @param persisted the embedded reference (may be null)
     * @return the matching registered instance, or {@code null} if no match — treat as
     *         dangling (surface in UI, block validation).
     */
    @Nullable
    public TomcatInfo resolve(@Nullable TomcatInfo persisted) {
        if (persisted == null) return null;

        String persistedId = persisted.getId();
        if (!persistedId.isEmpty()) {
            TomcatInfo byId = findTomcatInfoById(persistedId);
            if (byId != null) return byId;
        }

        String persistedPath = persisted.getPath();
        if (!persistedPath.isEmpty()) {
            TomcatInfo byPath = findRegisteredByPath(persistedPath);
            if (byPath != null) return byPath;
        }

        String persistedName = persisted.getName();
        if (!persistedName.isEmpty()) {
            TomcatInfo byName = findTomcatInfoByName(persistedName);
            if (byName != null) return byName;
        }

        return null;
    }

    /**
     * {@link #resolve} with a self-healing fallback: when the persisted
     * reference does not match any registered server, but its {@code path}
     * points to a valid Tomcat installation on disk, auto-register the
     * installation and return the newly-registered instance.
     *
     * <p>This is the default resolver for code paths that can tolerate —
     * and benefit from — self-healing: UI load, pre-launch validation, and
     * the launcher itself. Fresh IDE installs, VCS-imported projects, and
     * wiped sandbox profiles no longer demand a manual "add server" step as
     * long as the referenced Tomcat install is actually present on disk.
     *
     * <p>No-op when the path is empty, missing, or not a valid Tomcat
     * installation (no {@code catalina.jar}); in those cases the method
     * returns {@code null} exactly like {@link #resolve}, so validators
     * can still surface a useful error.
     *
     * <p>When auto-registration happens, the new instance is persisted via
     * the normal {@link #addTomcatInfo} path and is immediately returned —
     * subsequent calls will find it via {@link #resolve}'s path / ID tiers.
     *
     * @param persisted the embedded reference (may be null)
     * @return a registered instance when one exists or was auto-registered;
     *         {@code null} when neither resolution nor auto-registration is
     *         possible
     */
    @Nullable
    public TomcatInfo resolveOrAutoRegister(@Nullable TomcatInfo persisted) {
        TomcatInfo resolved = resolve(persisted);
        if (resolved != null) return resolved;
        if (persisted == null) return null;

        String path = persisted.getPath();
        if (path.isEmpty()) return null;

        // Preserve the persisted name when present so auto-registration
        // doesn't silently rename a server the user had already labelled.
        UnaryOperator<String> nameGenerator = persisted.getName().isEmpty()
                ? null
                : ignored -> persisted.getName();
        // Diagnostic variant logs the specific reason if validation fails.
        Optional<TomcatInfo> created = tryCreateTomcatInfoDiagnostic(path, nameGenerator);
        if (created.isEmpty()) return null;

        TomcatInfo toAdd = created.get();
        addTomcatInfo(toAdd);
        LOG.info("Auto-registered Tomcat server from persisted reference"
                + " (name=" + persisted.getName()
                + ", path=" + path + ")"
                + " — self-heal for fresh install / imported project.");
        return toAdd;
    }

    /**
     * Path match with three tiers (exact → normalized → {@link Files#isSameFile}).
     * Kept private so callers flow through {@link #resolve}.
     */
    @Nullable
    private TomcatInfo findRegisteredByPath(@NotNull String persistedPath) {
        for (TomcatInfo info : tomcatInfos) {
            if (persistedPath.equals(info.getPath())) return info;
        }
        Path persistedNormalized;
        try {
            persistedNormalized = Paths.get(persistedPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
        for (TomcatInfo info : tomcatInfos) {
            String candidatePath = info.getPath();
            if (candidatePath.isEmpty()) continue;
            try {
                Path candidateNormalized = Paths.get(candidatePath).toAbsolutePath().normalize();
                if (persistedNormalized.equals(candidateNormalized)) return info;
                if (Files.exists(persistedNormalized) && Files.exists(candidateNormalized)
                        && Files.isSameFile(persistedNormalized, candidateNormalized)) {
                    return info;
                }
            } catch (IOException | RuntimeException ignored) {
                // fall through to next candidate
            }
        }
        return null;
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
        // Copy contents instead of copyBean to preserve CopyOnWriteArrayList type.
        // XmlSerializerUtil.copyBean replaces field references via reflection,
        // which would overwrite our thread-safe COWAL with a plain ArrayList.
        tomcatInfos.clear();
        tomcatInfos.addAll(state.tomcatInfos);
        LOG.info("Loaded " + tomcatInfos.size() + " Tomcat servers from state");
    }

    // =====================================================================
    // TOMCAT CREATION & DETECTION
    // =====================================================================

    @NotNull
    public static Optional<TomcatInfo> tryCreateTomcatInfo(@NotNull String tomcatHome) {
        return tryCreateTomcatInfo(tomcatHome, null);
    }

    @NotNull
    public static Optional<TomcatInfo> tryCreateTomcatInfo(@NotNull String tomcatHome,
                                                           @Nullable UnaryOperator<String> nameGenerator) {
        return tryCreateTomcatInfoInternal(tomcatHome, nameGenerator, /* logFailures */ false);
    }

    /** Self-heal variant — logs WARN explaining which validation step failed. */
    @NotNull
    static Optional<TomcatInfo> tryCreateTomcatInfoDiagnostic(@NotNull String tomcatHome,
                                                              @Nullable UnaryOperator<String> nameGenerator) {
        return tryCreateTomcatInfoInternal(tomcatHome, nameGenerator, /* logFailures */ true);
    }

    @NotNull
    private static Optional<TomcatInfo> tryCreateTomcatInfoInternal(@NotNull String tomcatHome,
                                                                    @Nullable UnaryOperator<String> nameGenerator,
                                                                    boolean logFailures) {
        Path tomcatPath = Paths.get(tomcatHome);
        if (!Files.exists(tomcatPath) || !Files.isDirectory(tomcatPath)) {
            if (logFailures) {
                LOG.warn("Cannot create TomcatInfo for self-heal: path does not exist or is not a directory: "
                        + tomcatHome);
            }
            return Optional.empty();
        }

        File catalinaJar = tomcatPath.resolve(CATALINA_JAR).toFile();
        if (!catalinaJar.exists()) {
            if (logFailures) {
                LOG.warn("Cannot create TomcatInfo for self-heal: missing " + CATALINA_JAR
                        + " under " + tomcatHome
                        + " — does this path point to a Tomcat install root?");
            }
            return Optional.empty();
        }

        try {
            ServerInfo serverInfo = extractServerInfo(catalinaJar);
            String name = nameGenerator != null
                    ? nameGenerator.apply(serverInfo.serverInfo())
                    : generateTomcatName(serverInfo.serverInfo());
            return Optional.of(new TomcatInfo(name, serverInfo.serverNumber(), tomcatHome));
        } catch (IOException e) {
            if (logFailures) {
                LOG.warn("Cannot create TomcatInfo for self-heal: failed to read version from "
                        + tomcatHome + " (" + catalinaJar + "): " + e.getMessage(), e);
            } else {
                LOG.debug("Failed to read Tomcat version from " + tomcatHome, e);
            }
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

        List<String> existingNames = getInstance().getTomcatInfos().stream()
                .map(TomcatInfo::getName)
                .toList();

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
    // INNER CLASS: ServerInfo
    // =====================================================================

    /** Holder for server version information extracted from {@code catalina.jar}. */
    private record ServerInfo(@NotNull String serverInfo, @NotNull String serverNumber) {}
}
