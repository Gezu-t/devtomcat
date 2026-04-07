package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DOM-based mutation engine for Tomcat's {@code server.xml}.
 *
 * <p>Replaces the regex-based approach in {@code TomcatJavaParametersBuilder}
 * with structural XML parsing that preserves unknown elements, comments,
 * custom attributes, and non-standard connector layouts.
 *
 * <p>Reports unresolved mutations (e.g., missing connectors) via
 * {@link MutationResult#getWarnings()} so callers can log them.
 */
public final class ServerXmlMutator {

    private static final Logger LOG = Logger.getInstance(ServerXmlMutator.class);

    private ServerXmlMutator() {}

    /** Result of a server.xml mutation, including the transformed XML and any warnings. */
    public static final class MutationResult {
        private final String xml;
        private final List<String> warnings;

        MutationResult(@NotNull String xml, @NotNull List<String> warnings) {
            this.xml = xml;
            this.warnings = warnings;
        }

        @NotNull public String getXml() { return xml; }
        @NotNull public List<String> getWarnings() { return warnings; }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    /**
     * Customizes a server.xml string by updating ports for the shutdown port,
     * HTTP connector, HTTPS connector (optional), and AJP connector (optional).
     *
     * <p>Uses DOM parsing to locate elements structurally rather than regex,
     * preserving comments, custom attributes, and unknown content.
     *
     * @param serverXml    the source server.xml content
     * @param shutdownPort the desired shutdown port
     * @param httpPort     the desired HTTP connector port
     * @param httpsPort    the desired HTTPS connector port (used only if httpsEnabled)
     * @param httpsEnabled whether to configure/inject an HTTPS connector
     * @param ajpPort      the desired AJP connector port (used only if ajpEnabled)
     * @param ajpEnabled   whether to configure/inject an AJP connector
     * @return mutation result with the transformed XML and any warnings
     */
    @NotNull
    public static MutationResult customize(@NotNull String serverXml,
                                           int shutdownPort, int httpPort,
                                           int httpsPort, boolean httpsEnabled,
                                           int ajpPort, boolean ajpEnabled) {
        List<String> warnings = new ArrayList<>();

        try {
            Document doc = parse(serverXml);

            // 1. Update <Server port="...">
            setShutdownPort(doc, shutdownPort, warnings);

            // 2. Update HTTP connector port
            setHttpConnectorPort(doc, httpPort, warnings);

            // 3. Update HTTP connector's redirectPort to match HTTPS
            if (httpsEnabled) {
                setHttpRedirectPort(doc, httpsPort);
            }

            // 4. Handle HTTPS connector
            if (httpsEnabled) {
                setOrInjectHttpsConnector(doc, httpsPort, warnings);
            }

            // 5. Handle AJP connector
            if (ajpEnabled) {
                setOrInjectAjpConnector(doc, ajpPort, httpsPort, httpsEnabled, warnings);
            }

            // 6. Disable autoDeploy — DevTomcat manages deployments via context descriptors.
            // Tomcat's background HostConfig scanner causes redeploy loops when the IDE
            // modifies files in the exploded artifact output directory.
            disableAutoDeploy(doc);

            String result = serialize(doc);

            // Verify critical ports made it into the output
            if (!result.contains("port=\"" + httpPort + "\"")) {
                warnings.add("HTTP port " + httpPort + " may not appear in server.xml — the source may have a non-standard format");
            }
            if (!result.contains("port=\"" + shutdownPort + "\"")) {
                warnings.add("Shutdown port " + shutdownPort + " may not appear in server.xml");
            }

            return new MutationResult(result, warnings);

        } catch (Exception e) {
            LOG.warn("DOM-based server.xml mutation failed, returning original", e);
            warnings.add("XML parsing failed: " + e.getMessage() + " — server.xml was not modified");
            return new MutationResult(serverXml, warnings);
        }
    }

    // --- Shutdown port ---

    private static void disableAutoDeploy(@NotNull Document doc) {
        NodeList hosts = doc.getElementsByTagName("Host");
        for (int i = 0; i < hosts.getLength(); i++) {
            Node node = hosts.item(i);
            if (node instanceof Element host) {
                host.setAttribute("autoDeploy", "false");
            }
        }
    }

    private static void setShutdownPort(@NotNull Document doc, int port, @NotNull List<String> warnings) {
        NodeList servers = doc.getElementsByTagName("Server");
        if (servers.getLength() == 0) {
            warnings.add("No <Server> element found in server.xml");
            return;
        }
        Element server = (Element) servers.item(0);
        server.setAttribute("port", String.valueOf(port));
    }

    // --- HTTP connector ---

    private static void setHttpConnectorPort(@NotNull Document doc, int port, @NotNull List<String> warnings) {
        Element connector = findHttpConnector(doc);
        if (connector == null) {
            warnings.add("No HTTP/1.1 Connector found in server.xml — HTTP port not updated");
            return;
        }
        connector.setAttribute("port", String.valueOf(port));
    }

    private static void setHttpRedirectPort(@NotNull Document doc, int httpsPort) {
        Element connector = findHttpConnector(doc);
        if (connector != null && connector.hasAttribute("redirectPort")) {
            connector.setAttribute("redirectPort", String.valueOf(httpsPort));
        }
    }

    private static Element findHttpConnector(@NotNull Document doc) {
        NodeList connectors = doc.getElementsByTagName("Connector");
        for (int i = 0; i < connectors.getLength(); i++) {
            Element el = (Element) connectors.item(i);
            String protocol = el.getAttribute("protocol");
            // Match HTTP connectors: explicit "HTTP/1.1", empty (default), or full
            // class names like org.apache.coyote.http11.Http11NioProtocol / Http11Nio2Protocol
            if (isHttpProtocol(protocol)) {
                // Exclude connectors that are HTTPS (SSLEnabled or scheme=https)
                if (isHttpsConnector(el)) continue;
                return el;
            }
        }
        return null;
    }

    private static boolean isHttpProtocol(@NotNull String protocol) {
        return protocol.isEmpty()
                || TomcatConstants.PROTOCOL_HTTP.equals(protocol)
                || protocol.startsWith("org.apache.coyote.http11.");
    }

    // --- HTTPS connector ---

    private static void setOrInjectHttpsConnector(@NotNull Document doc, int port,
                                                   @NotNull List<String> warnings) {
        Element existing = findHttpsConnector(doc);
        if (existing != null) {
            existing.setAttribute("port", String.valueOf(port));
            return;
        }

        // Inject after the HTTP connector
        Element httpConnector = findHttpConnector(doc);
        if (httpConnector == null) {
            warnings.add("Cannot inject HTTPS connector — no HTTP connector found as reference point");
            return;
        }

        Element httpsConnector = doc.createElement("Connector");
        httpsConnector.setAttribute("port", String.valueOf(port));
        httpsConnector.setAttribute("protocol", TomcatConstants.PROTOCOL_HTTPS);
        httpsConnector.setAttribute("maxThreads", "150");
        httpsConnector.setAttribute("SSLEnabled", "true");

        Node parent = httpConnector.getParentNode();
        Node nextSibling = httpConnector.getNextSibling();
        if (nextSibling != null) {
            parent.insertBefore(doc.createTextNode("\n    "), nextSibling);
            parent.insertBefore(httpsConnector, nextSibling);
        } else {
            parent.appendChild(doc.createTextNode("\n    "));
            parent.appendChild(httpsConnector);
        }
    }

    private static Element findHttpsConnector(@NotNull Document doc) {
        NodeList connectors = doc.getElementsByTagName("Connector");
        for (int i = 0; i < connectors.getLength(); i++) {
            Element el = (Element) connectors.item(i);
            if (isHttpsConnector(el)) {
                return el;
            }
        }
        return null;
    }

    private static boolean isHttpsConnector(@NotNull Element connector) {
        return "true".equalsIgnoreCase(connector.getAttribute("SSLEnabled"))
                || "https".equalsIgnoreCase(connector.getAttribute("scheme"));
    }

    // --- AJP connector ---

    private static void setOrInjectAjpConnector(@NotNull Document doc, int port,
                                                 int httpsPort, boolean httpsEnabled,
                                                 @NotNull List<String> warnings) {
        Element existing = findAjpConnector(doc);
        if (existing != null) {
            existing.setAttribute("port", String.valueOf(port));
            return;
        }

        // Check for commented-out AJP connectors — DOM strips comments' inner XML,
        // so we inject a fresh one instead. Place it before the <Engine> element.
        NodeList engines = doc.getElementsByTagName("Engine");
        if (engines.getLength() == 0) {
            warnings.add("Cannot inject AJP connector — no <Engine> element found");
            return;
        }

        Element engine = (Element) engines.item(0);
        Element ajpConnector = doc.createElement("Connector");
        ajpConnector.setAttribute("port", String.valueOf(port));
        ajpConnector.setAttribute("protocol", TomcatConstants.PROTOCOL_AJP);
        ajpConnector.setAttribute("secretRequired", "false");
        // Use the resolved HTTPS port when HTTPS is enabled; otherwise use the HTTP
        // connector's redirectPort if one exists, falling back to the AJP port itself.
        int redirectPort = httpsEnabled ? httpsPort : resolveRedirectPort(doc, port);
        ajpConnector.setAttribute("redirectPort", String.valueOf(redirectPort));

        Node parent = engine.getParentNode();
        parent.insertBefore(doc.createTextNode("\n    "), engine);
        parent.insertBefore(ajpConnector, engine);
    }

    /**
     * Resolves the redirectPort for an injected connector by reading the HTTP connector's
     * existing redirectPort attribute. Falls back to the given default if not found.
     */
    private static int resolveRedirectPort(@NotNull Document doc, int fallback) {
        Element http = findHttpConnector(doc);
        if (http != null && http.hasAttribute("redirectPort")) {
            try {
                return Integer.parseInt(http.getAttribute("redirectPort"));
            } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static Element findAjpConnector(@NotNull Document doc) {
        NodeList connectors = doc.getElementsByTagName("Connector");
        for (int i = 0; i < connectors.getLength(); i++) {
            Element el = (Element) connectors.item(i);
            if (TomcatConstants.PROTOCOL_AJP.equals(el.getAttribute("protocol"))) {
                return el;
            }
        }
        return null;
    }

    // --- XML parse/serialize ---

    @NotNull
    static Document parse(@NotNull String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Full XXE hardening — defense-in-depth across all major parser implementations
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @NotNull
    static String serialize(@NotNull Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        // Secure processing prevents SSRF via stylesheet directives
        tf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
