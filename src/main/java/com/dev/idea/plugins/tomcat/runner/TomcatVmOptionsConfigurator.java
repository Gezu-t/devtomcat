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

    private static final String PARAM_HTTPS_PORT = "tomcat.https.port";
    private static final String PARAM_SERVER_PORT = "server.port";
    private static final String PARAM_SHUTDOWN_PORT = "server.shutdown.port";

    private TomcatVmOptionsConfigurator() {
    }

    static void configure(@NotNull ParametersList vmParams,
                          @Nullable String userVmOptions,
                          @NotNull PortConfig ports,
                          boolean jmxEnabled,
                          boolean httpsEnabled,
                          @NotNull Path catalinaBase,
                          @NotNull Path catalinaHome,
                          @NotNull Sdk jdk) {
        if (StringUtil.isNotEmpty(userVmOptions)) {
            vmParams.addParametersString(userVmOptions);
        }

        if (jmxEnabled) {
            configureJmx(vmParams, ports.getJmx());
        }

        if (httpsEnabled) {
            configureHttps(vmParams, ports.getHttps());
        }

        configureModuleOpens(vmParams, jdk);
        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome, ports.getHttp(), ports.getShutdown());
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
    }

    private static void configureHttps(@NotNull ParametersList vmParams, int httpsPort) {
        vmParams.defineProperty(PARAM_HTTPS_PORT, String.valueOf(httpsPort));
    }

    private static void configureCatalinaProperties(@NotNull ParametersList vmParams,
                                                    @NotNull Path catalinaBase,
                                                    @NotNull Path catalinaHome,
                                                    int httpPort,
                                                    int shutdownPort) {
        vmParams.defineProperty(PARAM_CATALINA_HOME, catalinaHome.toString());
        vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
        vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve(DIR_TEMP).toString());
        vmParams.defineProperty(PARAM_LOGGING_CONFIG, catalinaBase.resolve(CONFIG_LOGGING_PROPERTIES).toString());
        vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);
        vmParams.defineProperty(PARAM_SERVER_PORT, String.valueOf(httpPort));
        vmParams.defineProperty(PARAM_SHUTDOWN_PORT, String.valueOf(shutdownPort));
    }
}
