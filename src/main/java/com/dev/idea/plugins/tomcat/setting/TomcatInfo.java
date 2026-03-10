package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TomcatInfo implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Default library JAR name prefixes shown when no custom library list has been persisted.
     * Covers both legacy Tomcat (jsp-api, servlet-api) and Jakarta-era Tomcat 10+
     * (jakarta.servlet-api, jakarta.servlet.jsp-api).
     */
    public static final List<String> DEFAULT_LIBRARY_PREFIXES =
            List.of("jakarta.servlet.jsp-api", "jakarta.servlet-api",
                    "jsp-api", "servlet-api");

    private String id;
    private String name;
    private String version;
    private String path;
    private String catalinaBase;
    private List<String> libraries;

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

    /**
     * Get the persisted library paths.
     * Returns null if no custom library selection has been made (i.e. use defaults).
     * Returns a defensive copy so callers cannot mutate internal state.
     */
    @Nullable
    public List<String> getLibraries() {
        return libraries != null ? new ArrayList<>(libraries) : null;
    }

    /**
     * Set the persisted library paths.
     * Pass null to indicate "use default filtered libraries".
     * Pass an explicit list (even empty) to indicate the user has customized libraries.
     */
    public void setLibraries(@Nullable List<String> libraries) {
        this.libraries = libraries != null ? new ArrayList<>(libraries) : null;
    }

    /**
     * Check whether the user has customized the library list.
     */
    public boolean hasCustomLibraries() {
        return libraries != null;
    }

    /**
     * Determine whether custom libraries should be reset when the Tomcat Home changes.
     * Libraries are only reset when a valid Tomcat installation was detected AND
     * the new home differs from the previously confirmed home. This avoids wiping
     * a curated library list on every keystroke or when typing a partial/invalid path.
     *
     * @param validTomcatDetected true if the new home was successfully identified as a Tomcat installation
     * @param newHome             the current home path being evaluated
     * @param confirmedHome       the last home path that was confirmed as valid
     * @return true if custom libraries should be cleared
     */
    public static boolean shouldResetLibraries(boolean validTomcatDetected,
                                               @NotNull String newHome,
                                               @NotNull String confirmedHome) {
        return validTomcatDetected && !newHome.equals(confirmedHome);
    }

    /**
     * Filter JAR files from a Tomcat lib directory to only the default API JARs.
     *
     * @param libDir the Tomcat lib directory
     * @return sorted list of absolute paths for matching JARs
     */
    @NotNull
    public static List<String> filterDefaultLibraries(@NotNull java.io.File libDir) {
        List<String> result = new ArrayList<>();
        if (!libDir.isDirectory()) return result;
        java.io.File[] files = libDir.listFiles(file ->
                file.isFile() && file.getName().endsWith(".jar"));
        if (files == null) return result;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName, String.CASE_INSENSITIVE_ORDER));
        for (java.io.File jar : files) {
            String name = jar.getName();
            for (String prefix : DEFAULT_LIBRARY_PREFIXES) {
                if (name.startsWith(prefix)) {
                    result.add(jar.getAbsolutePath());
                    break;
                }
            }
        }
        return result;
    }

    @Override
    @NotNull
    public TomcatInfo clone() {
        try {
            TomcatInfo copy = (TomcatInfo) super.clone();
            if (this.libraries != null) {
                copy.libraries = new ArrayList<>(this.libraries);
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            TomcatInfo copy = new TomcatInfo();
            copy.setId(this.id);
            copy.setName(this.name);
            copy.setVersion(this.version);
            copy.setPath(this.path);
            copy.setCatalinaBase(this.catalinaBase);
            copy.setLibraries(this.libraries);
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