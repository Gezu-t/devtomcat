/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/2/25
 *
 * VM Configuration Manager
 * Handles JVM options and environment variables
 */
package com.dev.idea.plugins.tomcat.conf;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.*;

public class VmConfig implements Serializable, Cloneable {

    private String vmOptions;
    private Map<String, String> environmentVariables;
    private boolean passParentEnvs;

    public VmConfig() {
        this.vmOptions = "";
        this.environmentVariables = new LinkedHashMap<>();
        this.passParentEnvs = true;
    }

    public VmConfig(@NotNull VmConfig other) {
        this.vmOptions = other.vmOptions;
        this.environmentVariables = new LinkedHashMap<>(other.environmentVariables);
        this.passParentEnvs = other.passParentEnvs;
    }

    // === GETTERS/SETTERS ===

    @NotNull
    public String getVmOptions() {
        return StringUtil.notNullize(vmOptions);
    }

    public void setVmOptions(@Nullable String vmOptions) {
        this.vmOptions = StringUtil.notNullize(vmOptions);
    }

    @NotNull
    public Map<String, String> getEnvironmentVariables() {
        return new LinkedHashMap<>(environmentVariables);
    }

    public void setEnvironmentVariables(@Nullable Map<String, String> vars) {
        this.environmentVariables = vars != null ? new LinkedHashMap<>(vars) : new LinkedHashMap<>();
    }

    public boolean isPassParentEnvs() {
        return passParentEnvs;
    }

    public void setPassParentEnvs(boolean pass) {
        this.passParentEnvs = pass;
    }

    // === UTILITY METHODS ===

    public void addEnvironmentVariable(@NotNull String key, @NotNull String value) {
        environmentVariables.put(key, value);
    }

    public void removeEnvironmentVariable(@NotNull String key) {
        environmentVariables.remove(key);
    }

    public boolean hasEnvironmentVariable(@NotNull String key) {
        return environmentVariables.containsKey(key);
    }

    @Nullable
    public String getEnvironmentVariable(@NotNull String key) {
        return environmentVariables.get(key);
    }

    public void clearEnvironmentVariables() {
        environmentVariables.clear();
    }

    public boolean hasVmOptions() {
        return !StringUtil.isEmpty(vmOptions);
    }

    public boolean hasEnvironmentVariables() {
        return !environmentVariables.isEmpty();
    }

    /**
     * Parses VM options string into individual options
     */
    @NotNull
    public List<String> parseVmOptions() {
        if (StringUtil.isEmpty(vmOptions)) {
            return Collections.emptyList();
        }

        List<String> options = new ArrayList<>();
        String[] parts = vmOptions.trim().split("\\s+");
        for (String part : parts) {
            if (!StringUtil.isEmpty(part)) {
                options.add(part);
            }
        }
        return options;
    }

    /**
     * Checks if a specific VM option is present
     */
    public boolean hasVmOption(@NotNull String option) {
        return getVmOptions().contains(option);
    }

    // === CLONING ===

    @Override
    public VmConfig clone() {
        try {
            VmConfig cloned = (VmConfig) super.clone();
            cloned.environmentVariables = new LinkedHashMap<>(this.environmentVariables);
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone VmConfig", e);
        }
    }

    // === OBJECT METHODS ===

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VmConfig vmConfig = (VmConfig) o;
        return passParentEnvs == vmConfig.passParentEnvs &&
                Objects.equals(vmOptions, vmConfig.vmOptions) &&
                Objects.equals(environmentVariables, vmConfig.environmentVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmOptions, environmentVariables, passParentEnvs);
    }

    @Override
    public String toString() {
        return "VmConfig{" +
                "vmOptions='" + vmOptions + '\'' +
                ", envVars=" + environmentVariables.size() +
                ", passParent=" + passParentEnvs +
                '}';
    }
}