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
        public static final boolean DEFAULT_PASS_PARENT_ENVS = true;

        @NotNull private String vmOptions = DEFAULT_VM_OPTIONS;
        @NotNull private Map<String, String> environmentVariables = new LinkedHashMap<>();
        private boolean passParentEnvs = DEFAULT_PASS_PARENT_ENVS;

        public VmConfig() {}

        public VmConfig(@NotNull VmConfig other) {
            Objects.requireNonNull(other, "VmConfig cannot be null");
            this.vmOptions = StringUtil.notNullize(other.vmOptions);
            this.environmentVariables = new LinkedHashMap<>(other.environmentVariables);
            this.passParentEnvs = other.passParentEnvs;
        }

        @NotNull
        public String getVmOptions() { return vmOptions; }

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

        public boolean isPassParentEnvs() { return passParentEnvs; }
        public void setPassParentEnvs(boolean pass) { this.passParentEnvs = pass; }

        public void addEnvironmentVariable(@NotNull String key, @NotNull String value) {
            environmentVariables.put(key, value);
        }

        public boolean removeEnvironmentVariable(@NotNull String key) {
            return environmentVariables.remove(key) != null;
        }

        @Nullable
        public String getEnvironmentVariable(@NotNull String key) {
            return environmentVariables.get(key);
        }

        public boolean hasVmOptions() { return !vmOptions.isEmpty(); }
        public boolean hasEnvironmentVariables() { return !environmentVariables.isEmpty(); }

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
            return passParentEnvs == that.passParentEnvs &&
                    Objects.equals(vmOptions, that.vmOptions) &&
                    Objects.equals(environmentVariables, that.environmentVariables);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vmOptions, environmentVariables, passParentEnvs);
        }

        @NotNull
        @Override
        public String toString() {
            return "VmConfig{options=" + (hasVmOptions() ? "set" : "empty") +
                    ", envVars=" + environmentVariables.size() + ", passParent=" + passParentEnvs + '}';
        }
    }