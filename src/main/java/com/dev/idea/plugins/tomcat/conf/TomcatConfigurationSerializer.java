package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.dev.idea.plugins.tomcat.model.LogFileConfig;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Handles XML serialization/deserialization of Tomcat run configuration.
 */
public class TomcatConfigurationSerializer {

    private static final Logger LOG = Logger.getInstance(TomcatConfigurationSerializer.class);

    private static final String ATTR_HTTP_PORT = "httpPort";
    private static final String ATTR_SHUTDOWN_PORT = "shutdownPort";
    private static final String ATTR_HTTPS_PORT = "httpsPort";
    private static final String ATTR_HTTPS_ENABLED = "httpsEnabled";
    private static final String ATTR_JMX_PORT = "jmxPort";
    private static final String ATTR_JMX_ENABLED = "jmxEnabled";
    private static final String ATTR_AJP_PORT = "ajpPort";
    private static final String ATTR_AJP_ENABLED = "ajpEnabled";
    private static final String ATTR_CATALINA_BASE = "catalinaBase";
    private static final String ATTR_CONTEXT_PATH = "contextPath";
    private static final String ATTR_SERVER_MODE = "serverMode";
    private static final String ATTR_VM_OPTIONS = "vmOptions";
    private static final String ATTR_PASS_PARENT_ENVS = "passParentEnvs";

    private static final String ATTR_BROWSER_URL = "browserUrl";
    private static final String ATTR_AFTER_LAUNCH_ENABLED = "afterLaunchEnabled";
    private static final String ATTR_WITH_JS_DEBUGGER = "withJsDebugger";
    private static final String ATTR_WITH_JS_DEBUGGER_OLD = "withJavaScriptDebugger";
    private static final String ATTR_BROWSER_NAME = "browser";
    private static final String ATTR_BROWSER_NAME_OLD = "browserName";

    private static final String ATTR_HOT_DEPLOYMENT_ENABLED = "hotDeploymentEnabled";
    private static final String ATTR_UPDATE_CLASSES_AND_RESOURCES = "updateClassesAndResources";
    private static final String ATTR_PRESERVE_SESSIONS = "preserveSessions";

    private static final String ATTR_ON_UPDATE = "onUpdate";
    private static final String ATTR_ON_FRAME_DEACTIVATION = "onFrameDeactivation";
    private static final String ATTR_SHOW_UPDATE_DIALOG = "showUpdateDialog";
    private static final String ATTR_SHOW_FRAME_DEACTIVATION_DIALOG = "showFrameDeactivationDialog";
    private static final String ATTR_UPDATE_ACTION_OLD = "updateAction";
    private static final String ATTR_SHOW_DIALOG_OLD = "showDialog";

    private static final String ATTR_ACTIVATE_TOOL_WINDOW = "activateToolWindow";
    private static final String ATTR_FOCUS_TOOL_WINDOW = "focusToolWindow";
    private static final String ATTR_JRE_SELECTION = "jreSelection";
    private static final String ATTR_ALLOW_MULTIPLE_INSTANCES = "allowMultipleInstances";
    private static final String ATTR_STORE_AS_PROJECT_FILE = "storeAsProjectFile";

    private static final String ATTR_DEBUG_PORT = "debugPort";
    private static final String ATTR_DEBUG_TRANSPORT = "debugTransport";
    private static final String ATTR_USE_MODULE_CLASSPATH = "useModuleClasspath";

    private static final String ATTR_MANAGER_URL = "managerUrl";
    private static final String ATTR_REMOTE_USERNAME = "remoteUsername";
    private static final String ATTR_REMOTE_PASSWORD = "remotePassword";
    private static final String ATTR_USE_REMOTE_CREDENTIALS = "useRemoteCredentials";

    private static final String TAG_DEPLOYMENTS = "deployments";
    private static final String TAG_ARTIFACT = "artifact";
    private static final String ATTR_ARTIFACT_NAME = "name";
    private static final String ATTR_ARTIFACT_PATH = "path";
    private static final String ATTR_ARTIFACT_TYPE = "type";
    private static final String ATTR_ARTIFACT_CONTEXT = "contextPath";
    private static final String ATTR_ARTIFACT_DEPLOYED = "deployed";

    private static final String TAG_COVERAGE = "coverageConfig";
    private static final String TAG_COVERAGE_INCLUDE = "include";
    private static final String TAG_COVERAGE_EXCLUDE = "exclude";
    public static void write(@NotNull TomcatRunConfiguration config, @NotNull Element element) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        write(config.getConfigData(), element);
    }

    public static void write(@NotNull TomcatConfigurationData data, @NotNull Element element) {
        Objects.requireNonNull(data, "Configuration data cannot be null");
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            PortConfig pc = data.getPortConfig();
            writeInt(element, ATTR_HTTP_PORT, pc.getHttp());
            writeInt(element, ATTR_SHUTDOWN_PORT, pc.getShutdown());
            writeInt(element, ATTR_HTTPS_PORT, pc.getHttps());
            element.setAttribute(ATTR_HTTPS_ENABLED, String.valueOf(pc.isHttpsEnabled()));
            writeInt(element, ATTR_JMX_PORT, pc.getJmx());
            element.setAttribute(ATTR_JMX_ENABLED, String.valueOf(pc.isJmxEnabled()));
            writeInt(element, ATTR_AJP_PORT, pc.getAjp());
            element.setAttribute(ATTR_AJP_ENABLED, String.valueOf(pc.isAjpEnabled()));
            element.setAttribute(ATTR_CONTEXT_PATH, StringUtil.notNullize(data.getContextPath()));
            element.setAttribute(ATTR_SERVER_MODE, StringUtil.notNullize(data.getServerMode()));
            element.setAttribute(ATTR_CATALINA_BASE, StringUtil.notNullize(data.getCatalinaBase()));
            var vmConfig = data.getVmConfig();
            element.setAttribute(ATTR_VM_OPTIONS, StringUtil.notNullize(vmConfig.getVmOptions()));
            element.setAttribute(ATTR_PASS_PARENT_ENVS, String.valueOf(vmConfig.isPassParentEnvs()));
            writeEnvironmentVariables(element, vmConfig.getEnvironmentVariables());

            var browserConfig = data.getBrowserConfig();
            element.setAttribute(ATTR_BROWSER_URL, StringUtil.notNullize(browserConfig.getBrowserUrl()));
            element.setAttribute(ATTR_AFTER_LAUNCH_ENABLED, String.valueOf(browserConfig.isAfterLaunchEnabled()));
            element.setAttribute(ATTR_WITH_JS_DEBUGGER, String.valueOf(browserConfig.isWithJsDebugger()));
            element.setAttribute(ATTR_BROWSER_NAME, StringUtil.notNullize(browserConfig.getBrowserName(), "System Default"));

            var deploymentConfig = data.getDeploymentConfig();
            element.setAttribute(ATTR_HOT_DEPLOYMENT_ENABLED, String.valueOf(deploymentConfig.isHotDeploymentEnabled()));
            element.setAttribute(ATTR_UPDATE_CLASSES_AND_RESOURCES, String.valueOf(deploymentConfig.isUpdateClassesAndResources()));
            element.setAttribute(ATTR_PRESERVE_SESSIONS, String.valueOf(deploymentConfig.isPreserveSessions()));
            writeDeploymentArtifacts(element, deploymentConfig.getArtifacts());

            var updateConfig = data.getUpdateConfig();
            element.setAttribute(ATTR_ON_UPDATE, StringUtil.notNullize(updateConfig.getOnUpdate()));
            element.setAttribute(ATTR_ON_FRAME_DEACTIVATION, StringUtil.notNullize(updateConfig.getOnFrameDeactivation()));
            element.setAttribute(ATTR_SHOW_UPDATE_DIALOG, String.valueOf(updateConfig.isShowUpdateDialog()));
            element.setAttribute(ATTR_SHOW_FRAME_DEACTIVATION_DIALOG, String.valueOf(updateConfig.isShowFrameDeactivationDialog()));
            var uiConfig = data.getUiConfig();
            element.setAttribute(ATTR_ACTIVATE_TOOL_WINDOW, String.valueOf(uiConfig.isActivateToolWindow()));
            element.setAttribute(ATTR_FOCUS_TOOL_WINDOW, String.valueOf(uiConfig.isShowLogsPage()));

            element.setAttribute(ATTR_JRE_SELECTION, StringUtil.notNullize(data.getJreSelection()));
            element.setAttribute(ATTR_ALLOW_MULTIPLE_INSTANCES, String.valueOf(data.isAllowMultipleInstances()));
            element.setAttribute(ATTR_STORE_AS_PROJECT_FILE, String.valueOf(data.isStoreAsProjectFile()));
            DebugConfig dc = data.getDebugConfig();
            writeInt(element, ATTR_DEBUG_PORT, dc.getPort());
            element.setAttribute(ATTR_DEBUG_TRANSPORT, StringUtil.notNullize(dc.getTransport(), "Socket"));
            element.setAttribute(ATTR_USE_MODULE_CLASSPATH, String.valueOf(dc.isUseModuleClasspath()));
            RemoteConfig rc = data.getRemoteConfig();
            element.setAttribute(ATTR_MANAGER_URL, StringUtil.notNullize(rc.getManagerUrl()));
            element.setAttribute(ATTR_REMOTE_USERNAME, StringUtil.notNullize(rc.getUsername()));
            element.setAttribute(ATTR_REMOTE_PASSWORD, StringUtil.notNullize(rc.getPassword()));
            element.setAttribute(ATTR_USE_REMOTE_CREDENTIALS, String.valueOf(rc.isUseCredentials()));
            writeLogFileConfig(element, data.getLogFileConfig());
            writeTomcatInfo(element, data.getTomcatInfo());
            writeCoverageConfig(element, data.getCoverageConfig());

            LOG.debug("Wrote configuration data");
        } catch (Exception e) {
            LOG.error("Failed to write configuration data", e);
        }
    }

    private static void writeInt(@NotNull Element element, @NotNull String name, int value) {
        element.setAttribute(name, String.valueOf(value));
    }

    private static void writeEnvironmentVariables(@NotNull Element element, @Nullable Map<String, String> env) {
        if (env == null || env.isEmpty()) return;

        Element envElem = new Element("environmentVariables");
        for (var e : env.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                Element var = new Element("variable");
                var.setAttribute("name", e.getKey());
                var.setAttribute("value", e.getValue());
                envElem.addContent(var);
            }
        }
        element.addContent(envElem);
    }

    private static void writeLogFileConfig(@NotNull Element element, @NotNull LogFileConfig config) {
        Element logElem = new Element("logFileConfig");

        logElem.setAttribute("showStdout", String.valueOf(config.isShowStdoutConsole()));
        logElem.setAttribute("showStderr", String.valueOf(config.isShowStderrConsole()));
        logElem.setAttribute("saveToFile", String.valueOf(config.isSaveConsoleToFile()));
        logElem.setAttribute("saveFilePath", config.getSaveConsoleFilePath());

        for (String path : config.getLogFiles()) {
            if (path == null) continue;

            String normalized = path.trim();
            if (normalized.isEmpty()) continue;

            Element file = new Element("file");
            file.setAttribute("path", normalized);
            logElem.addContent(file);
        }

        element.addContent(logElem);
    }

    private static void writeDeploymentArtifacts(@NotNull Element element, @NotNull java.util.List<DeploymentArtifact> artifacts) {
        Element deployments = new Element(TAG_DEPLOYMENTS);
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            Element art = new Element(TAG_ARTIFACT);
            art.setAttribute(ATTR_ARTIFACT_NAME, StringUtil.notNullize(artifact.getName()));
            art.setAttribute(ATTR_ARTIFACT_PATH, StringUtil.notNullize(artifact.getPath()));
            art.setAttribute(ATTR_ARTIFACT_TYPE, StringUtil.notNullize(artifact.getType()));
            art.setAttribute(ATTR_ARTIFACT_CONTEXT, StringUtil.notNullize(artifact.getContextPath(), "/"));
            art.setAttribute(ATTR_ARTIFACT_DEPLOYED, String.valueOf(artifact.isDeployed()));
            deployments.addContent(art);
        }
        if (!deployments.getChildren(TAG_ARTIFACT).isEmpty()) {
            element.addContent(deployments);
        }
    }

    private static void writeTomcatInfo(@NotNull Element element, @Nullable TomcatInfo info) {
        if (info == null) return;

        Element tomcat = new Element("tomcatInfo");
        tomcat.setAttribute("name", StringUtil.notNullize(info.getName()));
        tomcat.setAttribute("version", StringUtil.notNullize(info.getVersion()));
        tomcat.setAttribute("path", StringUtil.notNullize(info.getPath()));
        tomcat.setAttribute("id", StringUtil.notNullize(info.getId()));
        element.addContent(tomcat);
    }
    public static void read(@NotNull TomcatRunConfiguration config, @NotNull Element element) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        read(config.getConfigData(), element);
    }

    public static void read(@NotNull TomcatConfigurationData data, @NotNull Element element) {
        Objects.requireNonNull(data, "Configuration data cannot be null");
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            PortConfig pc = data.getPortConfig();
            readInt(element, ATTR_HTTP_PORT, pc::setHttp);
            readInt(element, ATTR_SHUTDOWN_PORT, pc::setShutdown);
            readInt(element, ATTR_HTTPS_PORT, pc::setHttps);
            readBool(element, ATTR_HTTPS_ENABLED, pc::setHttpsEnabled);
            readInt(element, ATTR_JMX_PORT, pc::setJmx);
            readBool(element, ATTR_JMX_ENABLED, pc::setJmxEnabled);
            readInt(element, ATTR_AJP_PORT, pc::setAjp);
            readBool(element, ATTR_AJP_ENABLED, pc::setAjpEnabled);
            data.setContextPath(element.getAttributeValue(ATTR_CONTEXT_PATH));
            data.setServerMode(element.getAttributeValue(ATTR_SERVER_MODE));
            data.setCatalinaBase(element.getAttributeValue(ATTR_CATALINA_BASE));
            var vmConfig = data.getVmConfig();
            vmConfig.setVmOptions(element.getAttributeValue(ATTR_VM_OPTIONS));
            readBool(element, ATTR_PASS_PARENT_ENVS, vmConfig::setPassParentEnvs);
            readEnvironmentVariables(element, vmConfig::setEnvironmentVariables);

            var browserConfig = data.getBrowserConfig();
            browserConfig.setBrowserUrl(StringUtil.notNullize(element.getAttributeValue(ATTR_BROWSER_URL)));
            readBool(element, ATTR_AFTER_LAUNCH_ENABLED, browserConfig::setAfterLaunchEnabled);

            if (element.getAttributeValue(ATTR_WITH_JS_DEBUGGER) != null) {
                readBool(element, ATTR_WITH_JS_DEBUGGER, browserConfig::setWithJsDebugger);
            } else {
                readBool(element, ATTR_WITH_JS_DEBUGGER_OLD, browserConfig::setWithJsDebugger);
            }

            // Read browser name (new attr first, fall back to old)
            String browserName = element.getAttributeValue(ATTR_BROWSER_NAME);
            if (browserName == null) {
                browserName = element.getAttributeValue(ATTR_BROWSER_NAME_OLD);
            }
            if (browserName != null && !browserName.isEmpty()) {
                browserConfig.setBrowserName(browserName);
            }

            var deploymentConfig = data.getDeploymentConfig();
            readBool(element, ATTR_HOT_DEPLOYMENT_ENABLED, deploymentConfig::setHotDeploymentEnabled);
            readBool(element, ATTR_UPDATE_CLASSES_AND_RESOURCES, deploymentConfig::setUpdateClassesAndResources);
            readBool(element, ATTR_PRESERVE_SESSIONS, deploymentConfig::setPreserveSessions);
            readDeploymentArtifacts(element, deploymentConfig);

            var updateConfig = data.getUpdateConfig();

            String onUpdate = element.getAttributeValue(ATTR_ON_UPDATE);
            if (StringUtil.isEmpty(onUpdate)) {
                onUpdate = element.getAttributeValue(ATTR_UPDATE_ACTION_OLD);
            }
            if (!StringUtil.isEmpty(onUpdate)) {
                updateConfig.setOnUpdate(onUpdate);
            }

            String onFrame = element.getAttributeValue(ATTR_ON_FRAME_DEACTIVATION);
            if (!StringUtil.isEmpty(onFrame)) {
                updateConfig.setOnFrameDeactivation(onFrame);
            }

            if (element.getAttributeValue(ATTR_SHOW_UPDATE_DIALOG) != null) {
                readBool(element, ATTR_SHOW_UPDATE_DIALOG, updateConfig::setShowUpdateDialog);
            } else {
                readBool(element, ATTR_SHOW_DIALOG_OLD, updateConfig::setShowUpdateDialog);
            }

            readBool(element, ATTR_SHOW_FRAME_DEACTIVATION_DIALOG, updateConfig::setShowFrameDeactivationDialog);
            var uiConfig = data.getUiConfig();
            readBool(element, ATTR_ACTIVATE_TOOL_WINDOW, uiConfig::setActivateToolWindow);
            readBool(element, ATTR_FOCUS_TOOL_WINDOW, uiConfig::setShowLogsPage);

            data.setJreSelection(element.getAttributeValue(ATTR_JRE_SELECTION));
            readBool(element, ATTR_ALLOW_MULTIPLE_INSTANCES, data::setAllowMultipleInstances);
            readBool(element, ATTR_STORE_AS_PROJECT_FILE, data::setStoreAsProjectFile);
            DebugConfig dc = data.getDebugConfig();
            readInt(element, ATTR_DEBUG_PORT, dc::setPort);
            dc.setTransport(StringUtil.notNullize(element.getAttributeValue(ATTR_DEBUG_TRANSPORT), "Socket"));
            readBool(element, ATTR_USE_MODULE_CLASSPATH, dc::setUseModuleClasspath);
            RemoteConfig rc = data.getRemoteConfig();
            String managerUrl = element.getAttributeValue(ATTR_MANAGER_URL);
            if (managerUrl != null) rc.setManagerUrl(managerUrl);
            String remoteUsername = element.getAttributeValue(ATTR_REMOTE_USERNAME);
            if (remoteUsername != null) rc.setUsername(remoteUsername);
            rc.setPassword(element.getAttributeValue(ATTR_REMOTE_PASSWORD));
            readBool(element, ATTR_USE_REMOTE_CREDENTIALS, rc::setUseCredentials);
            readLogFileConfig(element, data.getLogFileConfig());
            readTomcatInfo(element, data::setTomcatInfo);
            readCoverageConfig(element, data.getCoverageConfig());

            LOG.debug("Read configuration data");
        } catch (Exception e) {
            LOG.error("Failed to read configuration data", e);
        }
    }

    private static void readInt(@NotNull Element element, @NotNull String name, java.util.function.IntConsumer setter) {
        String v = element.getAttributeValue(name);
        if (v != null && !v.isEmpty()) {
            try {
                setter.accept(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                LOG.warn("Invalid integer for " + name + ": " + v);
            }
        }
    }

    private static void readBool(@NotNull Element element, @NotNull String name, java.util.function.Consumer<Boolean> setter) {
        String v = element.getAttributeValue(name);
        if (v != null && !v.isEmpty()) {
            setter.accept(Boolean.parseBoolean(v));
        }
    }

    private static void readEnvironmentVariables(@NotNull Element element, java.util.function.Consumer<Map<String, String>> setter) {
        Element envElem = element.getChild("environmentVariables");
        if (envElem == null) return;

        Map<String, String> map = new HashMap<>();
        for (Element var : envElem.getChildren("variable")) {
            String name = var.getAttributeValue("name");
            String value = var.getAttributeValue("value");
            if (name != null && value != null) {
                map.put(name, value);
            }
        }
        if (!map.isEmpty()) {
            setter.accept(map);
        }
    }

    private static void readLogFileConfig(@NotNull Element element, @NotNull LogFileConfig config) {
        Element logElem = element.getChild("logFileConfig");
        if (logElem == null) return;

        String showStdout = logElem.getAttributeValue("showStdout");
        if (showStdout != null) config.setShowStdoutConsole(Boolean.parseBoolean(showStdout));

        String showStderr = logElem.getAttributeValue("showStderr");
        if (showStderr != null) config.setShowStderrConsole(Boolean.parseBoolean(showStderr));

        String saveToFile = logElem.getAttributeValue("saveToFile");
        if (saveToFile != null) config.setSaveConsoleToFile(Boolean.parseBoolean(saveToFile));

        String saveFilePath = logElem.getAttributeValue("saveFilePath");
        if (saveFilePath != null) config.setSaveConsoleFilePath(saveFilePath);

        java.util.List<String> files = new java.util.ArrayList<>();
        for (Element file : logElem.getChildren("file")) {
            String path = file.getAttributeValue("path");
            if (path != null) {
                String normalized = path.trim();
                if (!normalized.isEmpty()) {
                    files.add(normalized);
                }
            }
        }

        if (!files.isEmpty()) {
            config.setLogFiles(files);
        }
    }

    private static void readDeploymentArtifacts(@NotNull Element element, @NotNull com.dev.idea.plugins.tomcat.model.DeploymentConfig deploymentConfig) {
        Element deployments = element.getChild(TAG_DEPLOYMENTS);
        java.util.List<DeploymentArtifact> artifacts = new java.util.ArrayList<>();
        if (deployments != null) {
            for (Element art : deployments.getChildren(TAG_ARTIFACT)) {
                DeploymentArtifact artifact = new DeploymentArtifact();
                artifact.setName(StringUtil.notNullize(art.getAttributeValue(ATTR_ARTIFACT_NAME)));
                artifact.setPath(StringUtil.notNullize(art.getAttributeValue(ATTR_ARTIFACT_PATH)));
                artifact.setType(StringUtil.notNullize(art.getAttributeValue(ATTR_ARTIFACT_TYPE), DeploymentArtifact.TYPE_WAR));
                artifact.setContextPath(StringUtil.notNullize(art.getAttributeValue(ATTR_ARTIFACT_CONTEXT), "/"));
                String deployedAttr = art.getAttributeValue(ATTR_ARTIFACT_DEPLOYED);
                if (deployedAttr != null) {
                    artifact.setDeployed(Boolean.parseBoolean(deployedAttr));
                }
                artifacts.add(artifact);
            }
        }
        deploymentConfig.setArtifacts(artifacts);
    }

    private static void readTomcatInfo(@NotNull Element element, java.util.function.Consumer<TomcatInfo> setter) {
        Element tomcat = element.getChild("tomcatInfo");
        if (tomcat == null) return;

        String name = tomcat.getAttributeValue("name");
        String path = tomcat.getAttributeValue("path");

        if (name != null && path != null && !name.isEmpty() && !path.isEmpty()) {
            TomcatInfo info = new TomcatInfo();
            info.setName(name);
            info.setVersion(StringUtil.notNullize(tomcat.getAttributeValue("version")));
            info.setPath(path);
            info.setId(StringUtil.notNullize(tomcat.getAttributeValue("id")));
            setter.accept(info);
        }
    }

    private static void writeCoverageConfig(@NotNull Element element, @NotNull CoverageConfig config) {
        java.util.List<String> includes = config.getIncludePatterns();
        java.util.List<String> excludes = config.getExcludePatterns();
        if (includes.isEmpty() && excludes.isEmpty()) return;

        Element coverageElem = new Element(TAG_COVERAGE);
        for (String pattern : includes) {
            Element inc = new Element(TAG_COVERAGE_INCLUDE);
            inc.setAttribute("pattern", pattern);
            coverageElem.addContent(inc);
        }
        for (String pattern : excludes) {
            Element exc = new Element(TAG_COVERAGE_EXCLUDE);
            exc.setAttribute("pattern", pattern);
            coverageElem.addContent(exc);
        }
        element.addContent(coverageElem);
    }

    private static void readCoverageConfig(@NotNull Element element, @NotNull CoverageConfig config) {
        Element coverageElem = element.getChild(TAG_COVERAGE);
        if (coverageElem == null) return;

        java.util.List<String> includes = new java.util.ArrayList<>();
        for (Element inc : coverageElem.getChildren(TAG_COVERAGE_INCLUDE)) {
            String pattern = inc.getAttributeValue("pattern");
            if (pattern != null && !pattern.trim().isEmpty()) {
                includes.add(pattern.trim());
            }
        }
        config.setIncludePatterns(includes);

        java.util.List<String> excludes = new java.util.ArrayList<>();
        for (Element exc : coverageElem.getChildren(TAG_COVERAGE_EXCLUDE)) {
            String pattern = exc.getAttributeValue("pattern");
            if (pattern != null && !pattern.trim().isEmpty()) {
                excludes.add(pattern.trim());
            }
        }
        config.setExcludePatterns(excludes);
    }
}
