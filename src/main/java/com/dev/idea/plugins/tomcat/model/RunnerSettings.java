package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encapsulates execution settings specific to a single run mode (Run, Debug, Coverage).
 * Mirrors IntelliJ Ultimate's per-mode configuration capabilities.
 */
public class RunnerSettings implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean useDefaultStartup = true;
    private String startupScript = "";
    
    private boolean useDefaultShutdown = true;
    private String shutdownScript = "";

    @NotNull private Map<String, String> environmentVariables = new LinkedHashMap<>();
    @NotNull private Set<String> computedEnvironmentKeys = new LinkedHashSet<>();
    @NotNull private Set<String> deletedComputedEnvironmentKeys = new LinkedHashSet<>();
    private boolean passParentEnvs = true;

    public RunnerSettings() {}

    public RunnerSettings(@NotNull RunnerSettings other) {
        Objects.requireNonNull(other, "RunnerSettings cannot be null");
        this.useDefaultStartup = other.useDefaultStartup;
        this.startupScript = StringUtil.notNullize(other.startupScript);
        this.useDefaultShutdown = other.useDefaultShutdown;
        this.shutdownScript = StringUtil.notNullize(other.shutdownScript);
        this.environmentVariables = new LinkedHashMap<>(other.environmentVariables);
        this.computedEnvironmentKeys = new LinkedHashSet<>(other.computedEnvironmentKeys);
        this.deletedComputedEnvironmentKeys = new LinkedHashSet<>(other.deletedComputedEnvironmentKeys);
        this.passParentEnvs = other.passParentEnvs;
    }

    public boolean isUseDefaultStartup() {
        return useDefaultStartup;
    }

    public void setUseDefaultStartup(boolean useDefaultStartup) {
        this.useDefaultStartup = useDefaultStartup;
    }

    @NotNull
    public String getStartupScript() {
        return startupScript;
    }

    public void setStartupScript(@Nullable String startupScript) {
        this.startupScript = StringUtil.notNullize(startupScript);
    }

    public boolean isUseDefaultShutdown() {
        return useDefaultShutdown;
    }

    public void setUseDefaultShutdown(boolean useDefaultShutdown) {
        this.useDefaultShutdown = useDefaultShutdown;
    }

    @NotNull
    public String getShutdownScript() {
        return shutdownScript;
    }

    public void setShutdownScript(@Nullable String shutdownScript) {
        this.shutdownScript = StringUtil.notNullize(shutdownScript);
    }

    @NotNull
    public Map<String, String> getEnvironmentVariables() {
        return new LinkedHashMap<>(environmentVariables);
    }

    public void setEnvironmentVariables(@Nullable Map<String, String> vars) {
        this.environmentVariables = vars != null ? new LinkedHashMap<>(vars) : new LinkedHashMap<>();
    }

    @NotNull
    public Set<String> getComputedEnvironmentKeys() {
        return new LinkedHashSet<>(computedEnvironmentKeys);
    }

    public void setComputedEnvironmentKeys(@Nullable Set<String> keys) {
        this.computedEnvironmentKeys = keys != null ? new LinkedHashSet<>(keys) : new LinkedHashSet<>();
    }

    @NotNull
    public Set<String> getDeletedComputedEnvironmentKeys() {
        return new LinkedHashSet<>(deletedComputedEnvironmentKeys);
    }

    public void setDeletedComputedEnvironmentKeys(@Nullable Set<String> keys) {
        this.deletedComputedEnvironmentKeys = keys != null ? new LinkedHashSet<>(keys) : new LinkedHashSet<>();
    }

    public boolean isPassParentEnvs() {
        return passParentEnvs;
    }

    public void setPassParentEnvs(boolean passParentEnvs) {
        this.passParentEnvs = passParentEnvs;
    }

    @NotNull
    @Override
    public RunnerSettings clone() {
        return new RunnerSettings(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunnerSettings that)) return false;
        return useDefaultStartup == that.useDefaultStartup &&
                useDefaultShutdown == that.useDefaultShutdown &&
                passParentEnvs == that.passParentEnvs &&
                Objects.equals(startupScript, that.startupScript) &&
                Objects.equals(shutdownScript, that.shutdownScript) &&
                Objects.equals(environmentVariables, that.environmentVariables) &&
                Objects.equals(computedEnvironmentKeys, that.computedEnvironmentKeys) &&
                Objects.equals(deletedComputedEnvironmentKeys, that.deletedComputedEnvironmentKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(useDefaultStartup, startupScript, useDefaultShutdown, shutdownScript,
                environmentVariables, computedEnvironmentKeys, deletedComputedEnvironmentKeys, passParentEnvs);
    }
}
