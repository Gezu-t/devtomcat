package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static com.dev.idea.plugins.tomcat.TomcatConstants.CONFIG_LOGGING_PROPERTIES;
import static com.dev.idea.plugins.tomcat.TomcatConstants.DIR_TEMP;

/**
 * Builds the VM-options portion of the Tomcat launch command line:
 * user-supplied JVM args, JMX wiring, JPMS module-opens for JDK 9+, and
 * the catalina.home/base/tmpdir/logging-config system properties Tomcat
 * actually reads.
 *
 * <h2>Tomcat ports vs. JVM properties — what NOT to set here</h2>
 * Tomcat reads its HTTP, HTTPS, AJP, and shutdown port assignments
 * <b>only from {@code conf/server.xml}</b> connectors, which DevTomcat
 * regenerates per launch via {@link ServerXmlMutator}. The following
 * {@code -D} flags are <b>intentionally not set</b> because they are
 * either Spring Boot configuration ({@code -Dserver.port},
 * {@code -Dserver.shutdown.port}) or made-up names that no Tomcat
 * release reads ({@code -Dtomcat.https.port}):
 * <ul>
 *   <li>Setting them on a standalone Tomcat JVM has zero effect on the
 *       bound ports — the configuration came from server.xml.</li>
 *   <li>They mislead users who add similar flags to VM options expecting
 *       them to override port binding, when in reality only server.xml
 *       (and DevTomcat's UI) controls the bind.</li>
 * </ul>
 * Removing the dead flags also keeps the launch command line short and
 * readable in the IDE's process console.
 *
 * <p>The same policy is documented and enforced in
 * {@code DynamicTomcatEnvironment.buildCatalinaOpts()} — see the matching
 * regression test in {@code DynamicTomcatEnvironmentTest}.
 */
final class TomcatVmOptionsConfigurator {

    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_CATALINA_TMPDIR = "java.io.tmpdir";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";

    private static final String JMX_REMOTE_PROP = "com.sun.management.jmxremote";
    private static final String JMX_PORT_PROP = "com.sun.management.jmxremote.port";
    private static final String JMX_SSL_PROP = "com.sun.management.jmxremote.ssl";
    private static final String JMX_AUTH_PROP = "com.sun.management.jmxremote.authenticate";
    private static final String JMX_LOCAL_PROP = "com.sun.management.jmxremote.local.only";
    private static final String JMX_HOST_PROP = "com.sun.management.jmxremote.host";
    private static final String JMX_RMI_HOSTNAME_PROP = "java.rmi.server.hostname";
    private static final String JMX_DEFAULT_HOST = "127.0.0.1";

    private TomcatVmOptionsConfigurator() {
    }

    /**
     * Configure the VM parameter list for a Tomcat launch.
     *
     * @param vmParams       parameter list to mutate.
     * @param userVmOptions  free-form VM options string from the run config (may be empty).
     * @param ports          port configuration — only {@link PortConfig#getJmx()} is consumed
     *                       here; the HTTP/HTTPS/AJP/shutdown ports are wired into
     *                       {@code server.xml} by {@link ServerXmlMutator}, not into the
     *                       JVM command line. See class Javadoc for why.
     * @param jmxEnabled     whether to add the {@code com.sun.management.jmxremote.*} flags.
     * @param catalinaBase   resolved catalina.base path (per-launch in parallel mode).
     * @param catalinaHome   resolved catalina.home path (the registered Tomcat install).
     * @param jdk            the project / configured JDK; controls the JPMS module-opens.
     */
    static void configure(@NotNull ParametersList vmParams,
                          @Nullable String userVmOptions,
                          @NotNull PortConfig ports,
                          boolean jmxEnabled,
                          @NotNull Path catalinaBase,
                          @NotNull Path catalinaHome,
                          @NotNull Sdk jdk) {
        if (StringUtil.isNotEmpty(userVmOptions)) {
            vmParams.addParametersString(userVmOptions);
        }

        if (jmxEnabled) {
            configureJmx(vmParams, ports.getJmx());
        }

        configureModuleOpens(vmParams, jdk);
        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome);
    }

    private static void configureModuleOpens(@NotNull ParametersList vmParams, @NotNull Sdk jdk) {
        JavaSdkVersion sdkVersion = JavaSdkVersion.fromVersionString(jdk.getVersionString());
        if (sdkVersion == null || !sdkVersion.isAtLeast(JavaSdkVersion.JDK_1_9)) {
            return;
        }

        String[] moduleOpens = {
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED"
        };
        for (String open : moduleOpens) {
            vmParams.add(open);
        }
    }

    private static void configureJmx(@NotNull ParametersList vmParams, int jmxPort) {
        vmParams.defineProperty(JMX_REMOTE_PROP, "");
        vmParams.defineProperty(JMX_PORT_PROP, String.valueOf(jmxPort));
        vmParams.defineProperty(JMX_SSL_PROP, "false");
        vmParams.defineProperty(JMX_AUTH_PROP, "false");
        vmParams.defineProperty(JMX_LOCAL_PROP, "false");
        // JMX without auth or SSL must not be exposed on every interface. Without
        // an explicit host the JVM binds the JMX/RMI registry to 0.0.0.0, so a
        // network-reachable machine would expose unauthenticated JVM management
        // (including arbitrary MBean operations) to anyone on the network.
        // Pin to loopback. Power users who need network JMX can override via
        // VM options (the explicit -D wins over our defineProperty here).
        vmParams.defineProperty(JMX_HOST_PROP, JMX_DEFAULT_HOST);
        vmParams.defineProperty(JMX_RMI_HOSTNAME_PROP, JMX_DEFAULT_HOST);
    }

    private static void configureCatalinaProperties(@NotNull ParametersList vmParams,
                                                    @NotNull Path catalinaBase,
                                                    @NotNull Path catalinaHome) {
        vmParams.defineProperty(PARAM_CATALINA_HOME, catalinaHome.toString());
        vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
        vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve(DIR_TEMP).toString());
        vmParams.defineProperty(PARAM_LOGGING_CONFIG, catalinaBase.resolve(CONFIG_LOGGING_PROPERTIES).toString());
        vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);
    }
}
