package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

/**
 * VM Configuration for Tomcat process.
 */
public class VmConfig implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_VM_OPTIONS = "";

    @NotNull 
    private String vmOptions = DEFAULT_VM_OPTIONS;

    public VmConfig() {}

    public VmConfig(@NotNull VmConfig other) {
        Objects.requireNonNull(other, "VmConfig cannot be null");
        this.vmOptions = StringUtil.notNullize(other.vmOptions);
    }

    @NotNull
    public String getVmOptions() { return vmOptions; }

    public void setVmOptions(@Nullable String vmOptions) {
        this.vmOptions = StringUtil.notNullize(vmOptions).replaceAll("\\s+", " ").trim();
    }

    public boolean hasVmOptions() { return !vmOptions.isEmpty(); }

    @NotNull
    public List<String> parseVmOptions() {
        if (vmOptions.isEmpty()) return Collections.emptyList();
        return Arrays.stream(vmOptions.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @NotNull
    @Override
    public VmConfig clone() { return new VmConfig(this); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VmConfig that)) return false;
        return Objects.equals(vmOptions, that.vmOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmOptions);
    }

    @NotNull
    @Override
    public String toString() {
        return "VmConfig{options=" + (hasVmOptions() ? "set" : "empty") + '}';
    }
}