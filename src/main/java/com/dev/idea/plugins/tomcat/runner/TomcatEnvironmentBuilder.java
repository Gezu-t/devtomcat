package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Reusable builder for Tomcat environment configuration
 * Handles JDK options and environment variables setup
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatEnvironmentBuilder {

    // JDK 9+ module system options
    private static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";
    private static final String ENV_JDK_JAVA_OPTIONS =
            "--add-opens=java.base/java.lang=ALL-UNNAMED " +
                    "--add-opens=java.base/java.io=ALL-UNNAMED " +
                    "--add-opens=java.base/java.util=ALL-UNNAMED " +
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED " +
                    "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED";

    // Common Tomcat environment variables
    private static final String CATALINA_OPTS = "CATALINA_OPTS";
    private static final String JAVA_OPTS = "JAVA_OPTS";
    private static final String CATALINA_PID = "CATALINA_PID";

    private final Map<String, String> environment = new HashMap<>();
    private boolean includeJdkOptions = true;
    private boolean passParentEnvs = true;

    /**
     * Private constructor - use static factory methods
     */
    private TomcatEnvironmentBuilder() {
    }

    /**
     * Create a new environment builder
     */
    @NotNull
    public static TomcatEnvironmentBuilder create() {
        return new TomcatEnvironmentBuilder();
    }

    /**
     * Set whether to include JDK module system options
     */
    @NotNull
    public TomcatEnvironmentBuilder withJdkOptions(boolean include) {
        this.includeJdkOptions = include;
        return this;
    }

    /**
     * Set whether to pass parent environment variables
     */
    @NotNull
    public TomcatEnvironmentBuilder withPassParentEnvs(boolean pass) {
        this.passParentEnvs = pass;
        return this;
    }

    /**
     * Add custom environment variable
     */
    @NotNull
    public TomcatEnvironmentBuilder withEnvironmentVariable(@NotNull String key, @NotNull String value) {
        environment.put(key, value);
        return this;
    }

    /**
     * Add all environment variables from a map
     */
    @NotNull
    public TomcatEnvironmentBuilder withEnvironmentVariables(@NotNull Map<String, String> variables) {
        environment.putAll(variables);
        return this;
    }

    /**
     * Set CATALINA_OPTS
     */
    @NotNull
    public TomcatEnvironmentBuilder withCatalinaOpts(@NotNull String opts) {
        environment.put(CATALINA_OPTS, opts);
        return this;
    }

    /**
     * Set JAVA_OPTS
     */
    @NotNull
    public TomcatEnvironmentBuilder withJavaOpts(@NotNull String opts) {
        environment.put(JAVA_OPTS, opts);
        return this;
    }

    /**
     * Set CATALINA_PID for shutdown support
     */
    @NotNull
    public TomcatEnvironmentBuilder withCatalinaPid(@NotNull String pidFile) {
        environment.put(CATALINA_PID, pidFile);
        return this;
    }

    /**
     * Apply environment to command line
     */
    @NotNull
    public GeneralCommandLine applyTo(@NotNull GeneralCommandLine commandLine) {
        // Handle JDK options
        if (includeJdkOptions) {
            String existingJdkOptions = commandLine.getEnvironment().get(JDK_JAVA_OPTIONS);
            String newJdkOptions = StringUtil.isEmpty(existingJdkOptions)
                    ? ENV_JDK_JAVA_OPTIONS
                    : existingJdkOptions + " " + ENV_JDK_JAVA_OPTIONS;
            commandLine = commandLine.withEnvironment(JDK_JAVA_OPTIONS, newJdkOptions);
        }

        // Apply custom environment variables
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            commandLine = commandLine.withEnvironment(entry.getKey(), entry.getValue());
        }

        // Set parent environment passing
        commandLine = commandLine.withParentEnvironmentType(
                passParentEnvs
                        ? GeneralCommandLine.ParentEnvironmentType.CONSOLE
                        : GeneralCommandLine.ParentEnvironmentType.NONE
        );

        return commandLine;
    }

    /**
     * Build environment map
     */
    @NotNull
    public Map<String, String> build() {
        Map<String, String> result = new HashMap<>(environment);

        if (includeJdkOptions) {
            result.put(JDK_JAVA_OPTIONS, ENV_JDK_JAVA_OPTIONS);
        }

        return result;
    }

    /**
     * Create standard development environment
     */
    @NotNull
    public static TomcatEnvironmentBuilder createDevelopmentEnvironment() {
        return create()
                .withJdkOptions(true)
                .withPassParentEnvs(true)
                .withCatalinaOpts("-Dfile.encoding=UTF-8 -Ddevelopment=true")
                .withJavaOpts("-Xmx512m -Xms256m");
    }

    /**
     * Create production-like environment
     */
    @NotNull
    public static TomcatEnvironmentBuilder createProductionEnvironment() {
        return create()
                .withJdkOptions(true)
                .withPassParentEnvs(false)
                .withCatalinaOpts("-Dfile.encoding=UTF-8 -server")
                .withJavaOpts("-Xmx1024m -Xms512m -XX:+UseG1GC");
    }
}