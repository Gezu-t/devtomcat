package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TomcatInfo implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String version;
    private String path;
    private String catalinaBase;

    public TomcatInfo() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.version = "";
        this.path = "";
        this.catalinaBase = "";
    }

    public TomcatInfo(@NotNull String name, @NotNull String version, @NotNull String path) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name);
        this.version = Objects.requireNonNull(version);
        this.path = Objects.requireNonNull(path);
        this.catalinaBase = "";
    }

    @NotNull
    public String getId() {
        return StringUtil.notNullize(id);
    }

    public void setId(@Nullable String id) {
        String normalized = StringUtil.notNullize(id).trim();
        this.id = normalized.isEmpty() ? UUID.randomUUID().toString() : normalized;
    }

    @NotNull
    public String getName() {
        return StringUtil.notNullize(name);
    }

    public void setName(@Nullable String name) {
        this.name = StringUtil.notNullize(name);
    }

    @NotNull
    public String getVersion() {
        return StringUtil.notNullize(version);
    }

    public void setVersion(@Nullable String version) {
        this.version = StringUtil.notNullize(version);
    }

    /**
     * Get the major version number from the version string.
     * For example, "9.0.56" returns 9, "10.1.2" returns 10.
     *
     * @return the major version number, or 0 if version cannot be parsed
     */
    public int getMajorVersion() {
        try {
            String ver = getVersion();
            if (ver.isEmpty()) {
                return 0;
            }
            // Extract first number before the first dot
            int dotIndex = ver.indexOf('.');
            String majorStr = dotIndex > 0 ? ver.substring(0, dotIndex) : ver;
            return Integer.parseInt(majorStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NotNull
    public String getPath() {
        return StringUtil.notNullize(path);
    }

    public void setPath(@Nullable String path) {
        this.path = StringUtil.notNullize(path);
    }

    public boolean isValid() {
        return !getName().isEmpty() && !getPath().isEmpty();
    }

    /**
     * Validate this TomcatInfo configuration.
     * Throws IllegalStateException if validation fails.
     *
     * @throws IllegalStateException if name or path is empty
     */
    public void validate() throws IllegalStateException {
        if (getName().isEmpty()) {
            throw new IllegalStateException("Tomcat name cannot be empty");
        }
        if (getPath().isEmpty()) {
            throw new IllegalStateException("Tomcat path cannot be empty");
        }
        if (!new java.io.File(getPath()).exists()) {
            throw new IllegalStateException("Tomcat path does not exist: " + getPath());
        }
    }

    /**
     * Get CATALINA_HOME path.
     * This is the Tomcat installation directory.
     *
     * @return the Tomcat installation path (same as getPath())
     */
    @NotNull
    public String getCatalinaHome() {
        return getPath();
    }

    /**
     * Get CATALINA_BASE path.
     * For a standard installation, this is the same as CATALINA_HOME.
     * For run configurations, this is typically the project-specific directory.
     *
     * @return the CATALINA_BASE path, or CATALINA_HOME if not set
     */
    @NotNull
    public String getCatalinaBase() {
        String base = StringUtil.notNullize(catalinaBase).trim();
        return base.isEmpty() ? getPath() : base;
    }

    public void setCatalinaBase(@Nullable String catalinaBase) {
        this.catalinaBase = StringUtil.notNullize(catalinaBase);
    }

    @Override
    @NotNull
    public TomcatInfo clone() {
        try {
            return (TomcatInfo) super.clone();
        } catch (CloneNotSupportedException e) {
            TomcatInfo copy = new TomcatInfo();
            copy.setId(this.id);
            copy.setName(this.name);
            copy.setVersion(this.version);
            copy.setPath(this.path);
            copy.setCatalinaBase(this.catalinaBase);
            return copy;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TomcatInfo that = (TomcatInfo) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    @Override
    public String toString() {
        return getName() + " (" + getVersion() + ")";
    }
}