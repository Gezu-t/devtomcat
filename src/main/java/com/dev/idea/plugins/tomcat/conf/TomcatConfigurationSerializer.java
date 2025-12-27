package com.dev.idea.plugins.tomcat.conf;

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

    // ---- Attribute names (keep stable) ----
    private static final String ATTR_HTTP_PORT = "httpPort";
    private static final String ATTR_SHUTDOWN_PORT = "shutdownPort";
    private static final String ATTR_HTTPS_PORT = "httpsPort";
    private static final String ATTR_HTTPS_ENABLED = "httpsEnabled";
    private static final String ATTR_JMX_PORT = "jmxPort";
    private static final String ATTR_JMX_ENABLED = "jmxEnabled";

    private static final String ATTR_CONTEXT_PATH = "contextPath";
    private static final String ATTR_SERVER_MODE = "serverMode";

    private static final String ATTR_VM_OPTIONS = "vmOptions";
    private static final String ATTR_PASS_PARENT_ENVS = "passParentEnvs";

    // BrowserConfig (new)
    private static final String ATTR_BROWSER_URL = "browserUrl";
    private static final String ATTR_AFTER_LAUNCH_ENABLED = "afterLaunchEnabled";
    private static final String ATTR_WITH_JS_DEBUGGER = "withJsDebugger";

    // Backward compatibility (old)
    private static final String ATTR_WITH_JS_DEBUGGER_OLD = "withJavaScriptDebugger";
    private static final String ATTR_BROWSER_NAME_OLD = "browserName";

    // Deployment config (unchanged here)
    private static final String ATTR_HOT_DEPLOYMENT_ENABLED = "hotDeploymentEnabled";
    private static final String ATTR_UPDATE_CLASSES_AND_RESOURCES = "updateClassesAndResources";
    private static final String ATTR_PRESERVE_SESSIONS = "preserveSessions";

    // UpdateConfig (new)
    private static final String ATTR_ON_UPDATE = "onUpdate";
    private static final String ATTR_ON_FRAME_DEACTIVATION = "onFrameDeactivation";
    private static final String ATTR_SHOW_UPDATE_DIALOG = "showUpdateDialog";
    private static final String ATTR_SHOW_FRAME_DEACTIVATION_DIALOG = "showFrameDeactivationDialog";

    // Backward compatibility (old)
    private static final String ATTR_UPDATE_ACTION_OLD = "updateAction";
    private static final String ATTR_SHOW_DIALOG_OLD = "showDialog";

    // UI config (unchanged here)
    private static final String ATTR_ACTIVATE_TOOL_WINDOW = "activateToolWindow";
    private static final String ATTR_FOCUS_TOOL_WINDOW = "focusToolWindow";

    // Instance settings
    private static final String ATTR_JRE_SELECTION = "jreSelection";
    private static final String ATTR_ALLOW_MULTIPLE_INSTANCES = "allowMultipleInstances";
    private static final String ATTR_STORE_AS_PROJECT_FILE = "storeAsProjectFile";

    // Debug config
    private static final String ATTR_DEBUG_PORT = "debugPort";
    private static final String ATTR_DEBUG_TRANSPORT = "debugTransport";
    private static final String ATTR_USE_MODULE_CLASSPATH = "useModuleClasspath";

    // Remote config
    private static final String ATTR_MANAGER_URL = "managerUrl";
    private static final String ATTR_REMOTE_USERNAME = "remoteUsername";
    private static final String ATTR_REMOTE_PASSWORD = "remotePassword";
    private static final String ATTR_USE_REMOTE_CREDENTIALS = "useRemoteCredentials";

    // Deployment artifacts
    private static final String TAG_DEPLOYMENTS = "deployments";
    private static final String TAG_ARTIFACT = "artifact";
    private static final String ATTR_ARTIFACT_NAME = "name";
    private static final String ATTR_ARTIFACT_PATH = "path";
    private static final String ATTR_ARTIFACT_TYPE = "type";
    private static final String ATTR_ARTIFACT_CONTEXT = "contextPath";
    private static final String ATTR_ARTIFACT_DEPLOYED = "deployed";

    // === WRITE ===
    public static void write(@NotNull TomcatRunConfiguration config, @NotNull Element element) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            TomcatConfigurationData data = config.getConfigData();

            // === PORT CONFIGURATION ===
            PortConfig pc = data.getPortConfig();
            writeInt(element, ATTR_HTTP_PORT, pc.getHttp());
            writeInt(element, ATTR_SHUTDOWN_PORT, pc.getShutdown());
            writeInt(element, ATTR_HTTPS_PORT, pc.getHttps());
            element.setAttribute(ATTR_HTTPS_ENABLED, String.valueOf(pc.isHttpsEnabled()));
            writeInt(element, ATTR_JMX_PORT, pc.getJmx());
            element.setAttribute(ATTR_JMX_ENABLED, String.valueOf(pc.isJmxEnabled()));

            // === PATHS ===
            element.setAttribute(ATTR_CONTEXT_PATH, StringUtil.notNullize(data.getContextPath()));
            element.setAttribute(ATTR_SERVER_MODE, StringUtil.notNullize(data.getServerMode()));

            // === VM CONFIG ===
            var vmConfig = data.getVmConfig();
            element.setAttribute(ATTR_VM_OPTIONS, StringUtil.notNullize(vmConfig.getVmOptions()));
            element.setAttribute(ATTR_PASS_PARENT_ENVS, String.valueOf(vmConfig.isPassParentEnvs()));
            writeEnvironmentVariables(element, vmConfig.getEnvironmentVariables());

            // === BROWSER CONFIG (FIXED) ===
            var browserConfig = data.getBrowserConfig();
            element.setAttribute(ATTR_BROWSER_URL, StringUtil.notNullize(browserConfig.getBrowserUrl()));
            element.setAttribute(ATTR_AFTER_LAUNCH_ENABLED, String.valueOf(browserConfig.isAfterLaunchEnabled()));
            element.setAttribute(ATTR_WITH_JS_DEBUGGER, String.valueOf(browserConfig.isWithJsDebugger()));

            // NOTE: Do NOT write unsupported attributes like browserName / withJavaScriptDebugger

            // === DEPLOYMENT CONFIG ===
            var deploymentConfig = data.getDeploymentConfig();
            element.setAttribute(ATTR_HOT_DEPLOYMENT_ENABLED, String.valueOf(deploymentConfig.isHotDeploymentEnabled()));
            element.setAttribute(ATTR_UPDATE_CLASSES_AND_RESOURCES, String.valueOf(deploymentConfig.isUpdateClassesAndResources()));
            element.setAttribute(ATTR_PRESERVE_SESSIONS, String.valueOf(deploymentConfig.isPreserveSessions()));
            writeDeploymentArtifacts(element, deploymentConfig.getArtifacts());

            // === UPDATE CONFIG (FIXED) ===
            var updateConfig = data.getUpdateConfig();
            element.setAttribute(ATTR_ON_UPDATE, StringUtil.notNullize(updateConfig.getOnUpdate()));
            element.setAttribute(ATTR_ON_FRAME_DEACTIVATION, StringUtil.notNullize(updateConfig.getOnFrameDeactivation()));
            element.setAttribute(ATTR_SHOW_UPDATE_DIALOG, String.valueOf(updateConfig.isShowUpdateDialog()));
            element.setAttribute(ATTR_SHOW_FRAME_DEACTIVATION_DIALOG, String.valueOf(updateConfig.isShowFrameDeactivationDialog()));

            // === UI CONFIG ===
            var uiConfig = data.getUiConfig();
            element.setAttribute(ATTR_ACTIVATE_TOOL_WINDOW, String.valueOf(uiConfig.isActivateToolWindow()));
            element.setAttribute(ATTR_FOCUS_TOOL_WINDOW, String.valueOf(uiConfig.isFocusToolWindow()));

            // === JRE & INSTANCE SETTINGS ===
            element.setAttribute(ATTR_JRE_SELECTION, StringUtil.notNullize(data.getJreSelection()));
            element.setAttribute(ATTR_ALLOW_MULTIPLE_INSTANCES, String.valueOf(data.isAllowMultipleInstances()));
            element.setAttribute(ATTR_STORE_AS_PROJECT_FILE, String.valueOf(data.isStoreAsProjectFile()));

            // === DEBUG CONFIG ===
            DebugConfig dc = data.getDebugConfig();
            writeInt(element, ATTR_DEBUG_PORT, dc.getPort());
            element.setAttribute(ATTR_DEBUG_TRANSPORT, StringUtil.notNullize(dc.getTransport(), "Socket"));
            element.setAttribute(ATTR_USE_MODULE_CLASSPATH, String.valueOf(dc.isUseModuleClasspath()));

            // === REMOTE CONFIG ===
            RemoteConfig rc = data.getRemoteConfig();
            element.setAttribute(ATTR_MANAGER_URL, StringUtil.notNullize(rc.getManagerUrl()));
            element.setAttribute(ATTR_REMOTE_USERNAME, StringUtil.notNullize(rc.getUsername()));
            element.setAttribute(ATTR_REMOTE_PASSWORD, StringUtil.notNullize(rc.getPassword()));
            element.setAttribute(ATTR_USE_REMOTE_CREDENTIALS, String.valueOf(rc.isUseCredentials()));

            // === LOG FILE CONFIG ===
            writeLogFileConfig(element, data.getLogFileConfig());

            // === DEPLOYMENT ARTIFACTS ===
            writeDeploymentArtifacts(element, data.getDeploymentConfig().getArtifacts());

            // === TOMCAT INFO ===
            writeTomcatInfo(element, data.getTomcatInfo());

            LOG.debug("Wrote configuration: " + config.getName());
        } catch (Exception e) {
            LOG.error("Failed to write configuration: " + config.getName(), e);
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

        // New format: list of log file paths
        for (String path : config.getLogFiles()) {
            if (path == null) continue;

            String normalized = path.trim();
            if (normalized.isEmpty()) continue;

            Element file = new Element("file");
            file.setAttribute("path", normalized);
            logElem.addContent(file);
        }

        // Only add if we have something meaningful (optional, but keeps XML clean)
        if (!logElem.getChildren("file").isEmpty()) {
            element.addContent(logElem);
        }
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

    // === READ ===
    public static void read(@NotNull TomcatRunConfiguration config, @NotNull Element element) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            TomcatConfigurationData data = config.getConfigData();

            // === PORT CONFIGURATION ===
            PortConfig pc = data.getPortConfig();
            readInt(element, ATTR_HTTP_PORT, pc::setHttp);
            readInt(element, ATTR_SHUTDOWN_PORT, pc::setShutdown);
            readInt(element, ATTR_HTTPS_PORT, pc::setHttps);
            readBool(element, ATTR_HTTPS_ENABLED, pc::setHttpsEnabled);
            readInt(element, ATTR_JMX_PORT, pc::setJmx);
            readBool(element, ATTR_JMX_ENABLED, pc::setJmxEnabled);

            // === PATHS ===
            data.setContextPath(element.getAttributeValue(ATTR_CONTEXT_PATH));
            data.setServerMode(element.getAttributeValue(ATTR_SERVER_MODE));

            // === VM CONFIGURATION ===
            var vmConfig = data.getVmConfig();
            vmConfig.setVmOptions(element.getAttributeValue(ATTR_VM_OPTIONS));
            readBool(element, ATTR_PASS_PARENT_ENVS, vmConfig::setPassParentEnvs);
            readEnvironmentVariables(element, vmConfig::setEnvironmentVariables);

            // === BROWSER CONFIGURATION (FIXED + backward compatible) ===
            var browserConfig = data.getBrowserConfig();
            browserConfig.setBrowserUrl(StringUtil.notNullize(element.getAttributeValue(ATTR_BROWSER_URL)));
            readBool(element, ATTR_AFTER_LAUNCH_ENABLED, browserConfig::setAfterLaunchEnabled);

            // new attribute
            if (element.getAttributeValue(ATTR_WITH_JS_DEBUGGER) != null) {
                readBool(element, ATTR_WITH_JS_DEBUGGER, browserConfig::setWithJsDebugger);
            } else {
                // backward compatibility: old "withJavaScriptDebugger"
                readBool(element, ATTR_WITH_JS_DEBUGGER_OLD, browserConfig::setWithJsDebugger);
            }

            // ignore old browserName if present
            // element.getAttributeValue(ATTR_BROWSER_NAME_OLD);

            // === DEPLOYMENT CONFIGURATION ===
            var deploymentConfig = data.getDeploymentConfig();
            readBool(element, ATTR_HOT_DEPLOYMENT_ENABLED, deploymentConfig::setHotDeploymentEnabled);
            readBool(element, ATTR_UPDATE_CLASSES_AND_RESOURCES, deploymentConfig::setUpdateClassesAndResources);
            readBool(element, ATTR_PRESERVE_SESSIONS, deploymentConfig::setPreserveSessions);
            readDeploymentArtifacts(element, deploymentConfig);

            // === UPDATE CONFIGURATION (FIXED + backward compatible) ===
            var updateConfig = data.getUpdateConfig();

            String onUpdate = element.getAttributeValue(ATTR_ON_UPDATE);
            if (StringUtil.isEmpty(onUpdate)) {
                // backward compatibility: old attribute name
                onUpdate = element.getAttributeValue(ATTR_UPDATE_ACTION_OLD);
            }
            if (!StringUtil.isEmpty(onUpdate)) {
                updateConfig.setOnUpdate(onUpdate);
            }

            String onFrame = element.getAttributeValue(ATTR_ON_FRAME_DEACTIVATION);
            if (!StringUtil.isEmpty(onFrame)) {
                updateConfig.setOnFrameDeactivation(onFrame);
            }

            // new flags
            if (element.getAttributeValue(ATTR_SHOW_UPDATE_DIALOG) != null) {
                readBool(element, ATTR_SHOW_UPDATE_DIALOG, updateConfig::setShowUpdateDialog);
            } else {
                // backward compatibility: old "showDialog" mapped to showUpdateDialog
                readBool(element, ATTR_SHOW_DIALOG_OLD, updateConfig::setShowUpdateDialog);
            }

            readBool(element, ATTR_SHOW_FRAME_DEACTIVATION_DIALOG, updateConfig::setShowFrameDeactivationDialog);

            // === UI CONFIGURATION ===
            var uiConfig = data.getUiConfig();
            readBool(element, ATTR_ACTIVATE_TOOL_WINDOW, uiConfig::setActivateToolWindow);
            readBool(element, ATTR_FOCUS_TOOL_WINDOW, uiConfig::setFocusToolWindow);

            // === JRE & INSTANCE SETTINGS ===
            data.setJreSelection(element.getAttributeValue(ATTR_JRE_SELECTION));
            readBool(element, ATTR_ALLOW_MULTIPLE_INSTANCES, data::setAllowMultipleInstances);
            readBool(element, ATTR_STORE_AS_PROJECT_FILE, data::setStoreAsProjectFile);

            // === DEBUG CONFIG ===
            DebugConfig dc = data.getDebugConfig();
            readInt(element, ATTR_DEBUG_PORT, dc::setPort);
            dc.setTransport(StringUtil.notNullize(element.getAttributeValue(ATTR_DEBUG_TRANSPORT), "Socket"));
            readBool(element, ATTR_USE_MODULE_CLASSPATH, dc::setUseModuleClasspath);

            // === REMOTE CONFIG ===
            RemoteConfig rc = data.getRemoteConfig();
            rc.setManagerUrl(element.getAttributeValue(ATTR_MANAGER_URL));
            rc.setUsername(element.getAttributeValue(ATTR_REMOTE_USERNAME));
            rc.setPassword(element.getAttributeValue(ATTR_REMOTE_PASSWORD));
            readBool(element, ATTR_USE_REMOTE_CREDENTIALS, rc::setUseCredentials);

            // === LOG FILE CONFIG ===
            readLogFileConfig(element, data.getLogFileConfig());

            // === TOMCAT INFO ===
            readTomcatInfo(element, data::setTomcatInfo);

            LOG.debug("Read configuration: " + config.getName());
        } catch (Exception e) {
            LOG.error("Failed to read configuration: " + config.getName(), e);
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
            return;
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
}
