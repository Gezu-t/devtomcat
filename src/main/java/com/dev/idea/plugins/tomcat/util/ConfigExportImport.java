package com.dev.idea.plugins.tomcat.util;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.diagnostic.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration Export/Import utility for DevTomcat.
 *
 * Enables sharing Tomcat run configurations across teams and machines
 * via portable XML files. This is a DevTomcat-exclusive feature —
 * IntelliJ Ultimate has no equivalent for Tomcat configuration sharing.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class ConfigExportImport {

    private static final Logger LOG = Logger.getInstance(ConfigExportImport.class);

    private static final String ROOT_ELEMENT = "devtomcat-config";
    private static final String VERSION_ATTR = "version";
    private static final String EXPORTED_AT_ATTR = "exportedAt";
    private static final String CONFIG_VERSION = "1.0";

    private ConfigExportImport() {}

    // =========================================================================
    // Export
    // =========================================================================

    /**
     * Export a TomcatConfigurationData to an XML string.
     *
     * @param data the configuration data to export
     * @return XML string representation
     */
    @NotNull
    public static String exportToXml(@NotNull TomcatConfigurationData data) {
        Element root = new Element(ROOT_ELEMENT);
        root.setAttribute(VERSION_ATTR, CONFIG_VERSION);
        root.setAttribute(EXPORTED_AT_ATTR,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // Server info
        TomcatInfo info = data.getTomcatInfo();
        if (info != null) {
            Element serverEl = new Element("server");
            serverEl.addContent(createElement("name", info.getName()));
            serverEl.addContent(createElement("version", info.getVersion()));
            serverEl.addContent(createElement("path", info.getPath()));
            root.addContent(serverEl);
        }

        // Context path
        root.addContent(createElement("contextPath", data.getContextPath()));
        root.addContent(createElement("serverMode", data.getServerMode()));

        // Ports
        PortConfig ports = data.getPortConfig();
        Element portsEl = new Element("ports");
        portsEl.addContent(createElement("http", String.valueOf(ports.getHttp())));
        portsEl.addContent(createElement("https", String.valueOf(ports.getHttps())));
        portsEl.addContent(createElement("shutdown", String.valueOf(ports.getShutdown())));
        portsEl.addContent(createElement("jmx", String.valueOf(ports.getJmx())));
        portsEl.addContent(createElement("ajp", String.valueOf(ports.getAjp())));
        portsEl.addContent(createElement("httpsEnabled", String.valueOf(ports.isHttpsEnabled())));
        portsEl.addContent(createElement("jmxEnabled", String.valueOf(ports.isJmxEnabled())));
        portsEl.addContent(createElement("ajpEnabled", String.valueOf(ports.isAjpEnabled())));
        root.addContent(portsEl);

        // VM options
        VmConfig vm = data.getVmConfig();
        Element vmEl = new Element("vm");
        vmEl.addContent(createElement("options", vm.getVmOptions()));
        // Serialize environment variables as key=value entries (falling back to Run profile)
        Element envVarsEl = new Element("envVars");
        RunnerSettings runSettings = data.getRunnerSettings("Run");
        for (Map.Entry<String, String> entry : runSettings.getEnvironmentVariables().entrySet()) {
            Element varEl = new Element("var");
            varEl.setAttribute("key", entry.getKey());
            varEl.setAttribute("value", entry.getValue());
            envVarsEl.addContent(varEl);
        }
        vmEl.addContent(envVarsEl);
        root.addContent(vmEl);

        // Browser config
        BrowserConfig browser = data.getBrowserConfig();
        Element browserEl = new Element("browser");
        browserEl.addContent(createElement("afterLaunch", String.valueOf(browser.isOpenBrowser())));
        browserEl.addContent(createElement("url", browser.getBrowserUrl()));
        browserEl.addContent(createElement("name", browser.getBrowserName()));
        root.addContent(browserEl);

        // Deployment artifacts
        DeploymentConfig deployment = data.getDeploymentConfig();
        Element deployEl = new Element("deployment");
        List<DeploymentArtifact> artifacts = deployment.getArtifacts();
        for (DeploymentArtifact artifact : artifacts) {
            Element artEl = new Element("artifact");
            artEl.addContent(createElement("name", artifact.getName()));
            artEl.addContent(createElement("sourcePath", artifact.getPath()));
            artEl.addContent(createElement("contextPath", artifact.getContextPath()));
            artEl.addContent(createElement("type", artifact.getType()));
            deployEl.addContent(artEl);
        }
        root.addContent(deployEl);

        // JRE selection
        root.addContent(createElement("jreSelection", data.getJreSelection()));

        // Format output
        XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
        return outputter.outputString(new Document(root));
    }

    /**
     * Export configuration to an XML file.
     *
     * @param data the configuration data
     * @param file the target file
     * @throws IOException if writing fails
     */
    public static void exportToFile(@NotNull TomcatConfigurationData data,
                                    @NotNull File file) throws IOException {
        String xml = exportToXml(data);
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(xml);
        }
        LOG.info("Configuration exported to: " + file.getAbsolutePath());
    }

    // =========================================================================
    // Import
    // =========================================================================

    /**
     * Import a TomcatConfigurationData from an XML string.
     *
     * @param xml the XML string to parse
     * @return the imported configuration data
     * @throws Exception if parsing fails
     */
    @NotNull
    public static TomcatConfigurationData importFromXml(@NotNull String xml) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(new StringReader(xml));
        Element root = doc.getRootElement();

        if (!ROOT_ELEMENT.equals(root.getName())) {
            throw new IllegalArgumentException(
                    "Invalid configuration file: expected root element '" + ROOT_ELEMENT + "'");
        }

        TomcatConfigurationData data = new TomcatConfigurationData();

        // Server info
        Element serverEl = root.getChild("server");
        if (serverEl != null) {
            TomcatInfo info = new TomcatInfo(
                    getChildText(serverEl, "name", ""),
                    getChildText(serverEl, "version", ""),
                    getChildText(serverEl, "path", ""));
            data.setTomcatInfo(info);
        }

        // Context path
        data.setContextPath(getChildText(root, "contextPath", "/"));
        data.setServerMode(getChildText(root, "serverMode", "Local"));

        // Ports
        Element portsEl = root.getChild("ports");
        if (portsEl != null) {
            PortConfig ports = data.getPortConfig();
            ports.setHttp(getChildInt(portsEl, "http", 8080));
            ports.setHttps(getChildInt(portsEl, "https", 8443));
            ports.setShutdown(getChildInt(portsEl, "shutdown", 8005));
            ports.setJmx(getChildInt(portsEl, "jmx", 1099));
            ports.setAjp(getChildInt(portsEl, "ajp", 8009));
            ports.setHttpsEnabled(getChildBool(portsEl, "httpsEnabled", false));
            ports.setJmxEnabled(getChildBool(portsEl, "jmxEnabled", false));
            ports.setAjpEnabled(getChildBool(portsEl, "ajpEnabled", false));
        }

        // VM options
        Element vmEl = root.getChild("vm");
        if (vmEl != null) {
            VmConfig vm = data.getVmConfig();
            vm.setVmOptions(getChildText(vmEl, "options", ""));
            // Deserialize environment variables (falling back to Run profile)
            Element envVarsEl = vmEl.getChild("envVars");
            RunnerSettings runSettings = data.getRunnerSettings("Run");
            if (envVarsEl != null) {
                Map<String, String> envMap = new java.util.LinkedHashMap<>();
                for (Element varEl : envVarsEl.getChildren("var")) {
                    String key = varEl.getAttributeValue("key");
                    String value = varEl.getAttributeValue("value");
                    if (key != null && !key.isEmpty()) {
                        envMap.put(key, value != null ? value : "");
                    }
                }
                runSettings.setEnvironmentVariables(envMap);
            }
        }

        // Browser config
        Element browserEl = root.getChild("browser");
        if (browserEl != null) {
            BrowserConfig browser = data.getBrowserConfig();
            browser.setOpenBrowser(getChildBool(browserEl, "afterLaunch", true));
            browser.setBrowserUrl(getChildText(browserEl, "url", ""));
            browser.setBrowserName(getChildText(browserEl, "name", ""));
        }

        // Deployment artifacts
        Element deployEl = root.getChild("deployment");
        if (deployEl != null) {
            DeploymentConfig deployment = data.getDeploymentConfig();
            List<DeploymentArtifact> artifacts = new ArrayList<>();
            for (Element artEl : deployEl.getChildren("artifact")) {
                DeploymentArtifact artifact = new DeploymentArtifact();
                artifact.setName(getChildText(artEl, "name", ""));
                artifact.setPath(getChildText(artEl, "sourcePath", ""));
                artifact.setContextPath(getChildText(artEl, "contextPath", "/"));
                artifact.setType(getChildText(artEl, "type", ""));
                artifacts.add(artifact);
            }
            deployment.setArtifacts(artifacts);
        }

        // JRE selection
        data.setJreSelection(getChildText(root, "jreSelection", "Project default"));

        LOG.info("Configuration imported successfully");
        return data;
    }

    /**
     * Import configuration from an XML file.
     *
     * @param file the source file
     * @return the imported configuration data
     * @throws Exception if reading or parsing fails
     */
    @NotNull
    public static TomcatConfigurationData importFromFile(@NotNull File file) throws Exception {
        try (Reader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return importFromXml(sb.toString());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Element createElement(String name, String text) {
        Element el = new Element(name);
        el.setText(text != null ? text : "");
        return el;
    }

    private static String getChildText(Element parent, String childName, String defaultValue) {
        Element child = parent.getChild(childName);
        if (child == null) return defaultValue;
        String text = child.getText();
        return (text != null && !text.isEmpty()) ? text : defaultValue;
    }

    private static int getChildInt(Element parent, String childName, int defaultValue) {
        try {
            return Integer.parseInt(getChildText(parent, childName, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getChildBool(Element parent, String childName, boolean defaultValue) {
        String text = getChildText(parent, childName, String.valueOf(defaultValue));
        return Boolean.parseBoolean(text);
    }
}
