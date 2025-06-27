package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.utils.DevTomcatUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reusable builder for Tomcat Java parameters
 * Encapsulates the complex logic of building Java parameters for Tomcat
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatJavaParametersBuilder {

    // Constants
    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_CATALINA_TMPDIR = "java.io.tmpdir";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";

    // JMX default settings
    private static final String JMX_REMOTE_PROP = "com.sun.management.jmxremote";
    private static final String JMX_PORT_PROP = "com.sun.management.jmxremote.port";
    private static final String JMX_SSL_PROP = "com.sun.management.jmxremote.ssl";
    private static final String JMX_AUTH_PROP = "com.sun.management.jmxremote.authenticate";
    private static final String JMX_LOCAL_PROP = "com.sun.management.jmxremote.local.only";

    private final TomcatRunConfiguration configuration;
    private final Project project;

    public TomcatJavaParametersBuilder(@NotNull TomcatRunConfiguration configuration) {
        this.configuration = configuration;
        this.project = configuration.getProject();
    }

    /**
     * Builds JavaParameters for Tomcat execution
     *
     * @return Configured JavaParameters
     * @throws ExecutionException if configuration is invalid
     */
    @NotNull
    public JavaParameters build() throws ExecutionException {
        try {
            // Get paths
            Path catalinaBase = getCatalinaBase();
            Path catalinaHome = getCatalinaHome();

            // Ensure required directories exist
            ensureDirectoriesExist(catalinaBase);

            // Create JavaParameters
            JavaParameters params = new JavaParameters();

            // Basic setup
            setupBasicParameters(params, catalinaBase);

            // Classpath
            setupClasspath(params, catalinaHome);

            // Environment
            setupEnvironment(params);

            // VM options
            setupVmOptions(params, catalinaBase, catalinaHome);

            return params;

        } catch (IOException e) {
            throw new ExecutionException("Failed to prepare Tomcat directories", e);
        }
    }

    /**
     * Get Catalina base directory
     */
    @NotNull
    private Path getCatalinaBase() throws ExecutionException {
        Path base = DevTomcatUtils.getCatalinaBase(configuration);
        if (base == null) {
            throw new ExecutionException("Unable to determine catalina.base directory");
        }
        return base;
    }

    /**
     * Get Catalina home directory
     */
    @NotNull
    private Path getCatalinaHome() throws ExecutionException {
        if (configuration.getTomcatInfo() == null) {
            throw new ExecutionException("No Tomcat server configured");
        }
        return Paths.get(configuration.getTomcatInfo().getPath());
    }

    /**
     * Ensure required directories exist
     */
    private void ensureDirectoriesExist(@NotNull Path catalinaBase) throws IOException {
        Files.createDirectories(catalinaBase.resolve("temp"));
        Files.createDirectories(catalinaBase.resolve("logs"));
        Files.createDirectories(catalinaBase.resolve("webapps"));
        Files.createDirectories(catalinaBase.resolve("work"));
        Files.createDirectories(catalinaBase.resolve("conf"));
    }

    /**
     * Setup basic parameters
     */
    private void setupBasicParameters(@NotNull JavaParameters params, @NotNull Path catalinaBase) {
        params.setDefaultCharset(project);
        params.setWorkingDirectory(catalinaBase.toFile());
        params.setJdk(ProjectRootManager.getInstance(project).getProjectSdk());
        params.setMainClass(TOMCAT_MAIN_CLASS);
        params.getProgramParametersList().add("start");
    }

    /**
     * Setup classpath
     */
    private void setupClasspath(@NotNull JavaParameters params, @NotNull Path catalinaHome) {
        params.getClassPath().add(catalinaHome.resolve("bin/bootstrap.jar").toFile());
        params.getClassPath().add(catalinaHome.resolve("bin/tomcat-juli.jar").toFile());
    }

    /**
     * Setup environment
     */
    private void setupEnvironment(@NotNull JavaParameters params) {
        params.setPassParentEnvs(configuration.isPassParentEnvs());
        params.setEnv(configuration.getEnvironmentVariables());
    }

    /**
     * Setup VM options
     */
    private void setupVmOptions(@NotNull JavaParameters params,
                                @NotNull Path catalinaBase,
                                @NotNull Path catalinaHome) {
        ParametersList vmParams = params.getVMParametersList();

        // User-defined VM options
        String vmOptions = configuration.getVmOptions();
        if (StringUtil.isNotEmpty(vmOptions)) {
            vmParams.addParametersString(vmOptions);
        }

        // JMX configuration
        if (configuration.isJmxEnabled()) {
            configureJmx(vmParams);
        }

        // Hot deployment
        if (configuration.isHotDeploymentEnabled()) {
            configureHotDeployment(vmParams);
        }

        // Catalina system properties
        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome);
    }

    /**
     * Configure JMX settings
     */
    private void configureJmx(@NotNull ParametersList vmParams) {
        int port = configuration.getJmxPort() != null ? configuration.getJmxPort() : 1099;

        vmParams.addProperty(JMX_REMOTE_PROP, "");
        vmParams.addProperty(JMX_PORT_PROP, String.valueOf(port));
        vmParams.addProperty(JMX_SSL_PROP, "false");
        vmParams.addProperty(JMX_AUTH_PROP, "false");
        vmParams.addProperty(JMX_LOCAL_PROP, "false");
    }

    /**
     * Configure hot deployment settings
     */
    private void configureHotDeployment(@NotNull ParametersList vmParams) {
        vmParams.addProperty("tomcat.autoreload.enabled", "true");
        vmParams.addProperty("tomcat.development", "true");
        vmParams.addProperty("tomcat.reloadable", "true");
        vmParams.addProperty("tomcat.antiResourceLocking", "false");
        vmParams.addProperty("tomcat.antiJARLocking", "false");
    }

    /**
     * Configure Catalina system properties
     */
    private void configureCatalinaProperties(@NotNull ParametersList vmParams,
                                             @NotNull Path catalinaBase,
                                             @NotNull Path catalinaHome) {
        vmParams.defineProperty(PARAM_CATALINA_HOME, catalinaHome.toString());
        vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
        vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve("temp").toString());
        vmParams.defineProperty(PARAM_LOGGING_CONFIG, catalinaBase.resolve("conf/logging.properties").toString());
        vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);
    }

    /**
     * Create builder for a configuration
     */
    public static TomcatJavaParametersBuilder create(@NotNull TomcatRunConfiguration configuration) {
        return new TomcatJavaParametersBuilder(configuration);
    }
}