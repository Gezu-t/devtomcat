package com.dev.idea.plugins.tomcat.setting;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Tomcat Server Information
 *
 * Represents a configured Apache Tomcat server instance with its
 * installation path, version information, and unique identification.
 *
 * This class is serializable for persistence in IntelliJ's settings
 * and provides proper equals/hashCode for collections.
 *
 * @author Dev Tomcat Team
 */
public class TomcatInfo implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    // Unique identifier for this server instance
    @NotNull private String id;

    // User-friendly display name
    @NotNull private String name;

    // Tomcat version (e.g., "9.0.54")
    @NotNull private String version;

    // Installation path (CATALINA_HOME)
    @NotNull private String path;

    /**
     * Default constructor for serialization
     */
    public TomcatInfo() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.version = "";
        this.path = "";
    }

    /**
     * Constructor with all fields
     *
     * @param name Display name
     * @param version Tomcat version
     * @param path Installation path
     */
    public TomcatInfo(@NotNull String name, @NotNull String version, @NotNull String path) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.version = version;
        this.path = path;
    }

    /**
     * Full constructor including ID
     *
     * @param id Unique identifier
     * @param name Display name
     * @param version Tomcat version
     * @param path Installation path
     */
    public TomcatInfo(@NotNull String id, @NotNull String name, @NotNull String version, @NotNull String path) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.path = path;
    }

    // === GETTERS AND SETTERS ===

    /**
     * Get unique identifier
     *
     * @return The server ID
     */
    @NotNull
    public String getId() {
        // Generate ID if not set (for backward compatibility)
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    /**
     * Set unique identifier
     *
     * @param id The server ID
     */
    public void setId(@NotNull String id) {
        this.id = id;
    }

    /**
     * Get display name
     *
     * @return The server name
     */
    @NotNull
    public String getName() {
        return name != null ? name : "";
    }

    /**
     * Set display name
     *
     * @param name The server name
     */
    public void setName(@NotNull String name) {
        this.name = name;
    }

    /**
     * Get Tomcat version
     *
     * @return The version string
     */
    @NotNull
    public String getVersion() {
        return version != null ? version : "";
    }

    /**
     * Set Tomcat version
     *
     * @param version The version string
     */
    public void setVersion(@NotNull String version) {
        this.version = version;
    }

    /**
     * Get installation path
     *
     * @return The Tomcat home directory path
     */
    @NotNull
    public String getPath() {
        return path != null ? path : "";
    }

    /**
     * Set installation path
     *
     * @param path The Tomcat home directory path
     */
    public void setPath(@NotNull String path) {
        this.path = path;
    }

    // === UTILITY METHODS ===

    /**
     * Get display string for UI
     *
     * @return Name and version combined
     */
    @NotNull
    public String getDisplayString() {
        return String.format("%s (%s)", getName(), getVersion());
    }

    /**
     * Get CATALINA_HOME path
     *
     * @return Same as getPath()
     */
    @NotNull
    public String getCatalinaHome() {
        return getPath();
    }

    /**
     * Get CATALINA_BASE path (defaults to CATALINA_HOME)
     *
     * @return The base directory path
     */
    @NotNull
    public String getCatalinaBase() {
        // In Dev Tomcat, we use the same directory for both
        return getPath();
    }

    /**
     * Check if this server configuration is valid
     *
     * @return true if all required fields are set
     */
    public boolean isValid() {
        return !getName().isEmpty() &&
                !getVersion().isEmpty() &&
                !getPath().isEmpty() &&
                new java.io.File(getPath()).exists();
    }

    /**
     * Validate and throw exception if invalid
     *
     * @throws IllegalStateException if validation fails
     */
    public void validate() {
        if (getName().isEmpty()) {
            throw new IllegalStateException("Server name cannot be empty");
        }
        if (getVersion().isEmpty()) {
            throw new IllegalStateException("Server version cannot be empty");
        }
        if (getPath().isEmpty()) {
            throw new IllegalStateException("Server path cannot be empty");
        }

        java.io.File tomcatHome = new java.io.File(getPath());
        if (!tomcatHome.exists()) {
            throw new IllegalStateException("Tomcat home directory does not exist: " + getPath());
        }
        if (!tomcatHome.isDirectory()) {
            throw new IllegalStateException("Tomcat home path is not a directory: " + getPath());
        }

        // Check for catalina.jar to verify it's a valid Tomcat installation
        java.io.File catalinaJar = new java.io.File(tomcatHome, "lib/catalina.jar");
        if (!catalinaJar.exists()) {
            throw new IllegalStateException("Not a valid Tomcat installation (missing catalina.jar): " + getPath());
        }
    }

    /**
     * Get major version number
     *
     * @return Major version (e.g., 9 for "9.0.54")
     */
    public int getMajorVersion() {
        try {
            String ver = getVersion();
            int dotIndex = ver.indexOf('.');
            if (dotIndex > 0) {
                return Integer.parseInt(ver.substring(0, dotIndex));
            }
            return Integer.parseInt(ver);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if this is Tomcat 7 or newer
     *
     * @return true if version >= 7
     */
    public boolean isTomcat7OrNewer() {
        return getMajorVersion() >= 7;
    }

    // === OBJECT METHODS ===

    @Override
    public TomcatInfo clone() {
        try {
            TomcatInfo cloned = (TomcatInfo) super.clone();
            // Strings are immutable, so shallow copy is fine
            return cloned;
        } catch (CloneNotSupportedException e) {
            // Should never happen
            throw new AssertionError("Clone not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TomcatInfo)) return false;

        TomcatInfo that = (TomcatInfo) o;

        // Use ID for equality if both have IDs
        if (id != null && that.id != null && !id.isEmpty() && !that.id.isEmpty()) {
            return id.equals(that.id);
        }

        // Fallback to comparing all fields
        return Objects.equals(name, that.name) &&
                Objects.equals(version, that.version) &&
                Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        // Use ID for hash if available
        if (id != null && !id.isEmpty()) {
            return id.hashCode();
        }
        // Fallback to all fields
        return Objects.hash(name, version, path);
    }

    @Override
    public String toString() {
        // Return just the name for UI components
        return getName();
    }

    /**
     * Get detailed string representation
     *
     * @return Full details of this server
     */
    public String toDetailedString() {
        return String.format("TomcatInfo{id='%s', name='%s', version='%s', path='%s'}",
                getId(), getName(), getVersion(), getPath());
    }
}