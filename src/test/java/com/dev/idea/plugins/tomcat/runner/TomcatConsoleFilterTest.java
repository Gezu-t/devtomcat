package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TomcatConsoleFilter URL and file path regex patterns.
 * Since the filter requires a Project instance for full integration,
 * we test the regex patterns directly.
 */
@DisplayName("TomcatConsoleFilter patterns")
class TomcatConsoleFilterTest {

    // Mirror the patterns from TomcatConsoleFilter
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w.-]+(:\\d+)?(/[\\w./?%&=~#-]*)?");

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "(/[\\w./-]+\\.(?:xml|properties|war|jar|class|java|log))");

    @Test
    @DisplayName("matches simple HTTP URL")
    void matchesHttpUrl() {
        Matcher m = URL_PATTERN.matcher("http://localhost:8080/myapp");
        assertTrue(m.find());
        assertEquals("http://localhost:8080/myapp", m.group());
    }

    @Test
    @DisplayName("matches HTTPS URL")
    void matchesHttpsUrl() {
        Matcher m = URL_PATTERN.matcher("https://example.com/path?q=1");
        assertTrue(m.find());
        assertEquals("https://example.com/path?q=1", m.group());
    }

    @Test
    @DisplayName("matches URL without port")
    void matchesUrlWithoutPort() {
        Matcher m = URL_PATTERN.matcher("http://localhost/");
        assertTrue(m.find());
        assertEquals("http://localhost/", m.group());
    }

    @Test
    @DisplayName("matches URL embedded in log line")
    void matchesUrlInLogLine() {
        String line = "INFO: Server started at http://localhost:8080/myapp in 3456ms";
        Matcher m = URL_PATTERN.matcher(line);
        assertTrue(m.find());
        assertEquals("http://localhost:8080/myapp", m.group());
    }

    @Test
    @DisplayName("no URL match on plain text")
    void noUrlMatchOnPlainText() {
        Matcher m = URL_PATTERN.matcher("INFO: Server startup in 1234 ms");
        assertFalse(m.find());
    }

    @Test
    @DisplayName("matches .xml file path")
    void matchesXmlFilePath() {
        Matcher m = FILE_PATH_PATTERN.matcher("Reading /opt/tomcat/conf/server.xml");
        assertTrue(m.find());
        assertEquals("/opt/tomcat/conf/server.xml", m.group());
    }

    @Test
    @DisplayName("matches .war file path")
    void matchesWarFilePath() {
        Matcher m = FILE_PATH_PATTERN.matcher("Deploying /opt/tomcat/webapps/myapp.war");
        assertTrue(m.find());
        assertEquals("/opt/tomcat/webapps/myapp.war", m.group());
    }

    @Test
    @DisplayName("matches .properties file path")
    void matchesPropertiesFilePath() {
        Matcher m = FILE_PATH_PATTERN.matcher("Loading /opt/tomcat/conf/logging.properties");
        assertTrue(m.find());
        assertEquals("/opt/tomcat/conf/logging.properties", m.group());
    }

    @Test
    @DisplayName("matches .java file path")
    void matchesJavaFilePath() {
        Matcher m = FILE_PATH_PATTERN.matcher("at /src/main/java/com/example/App.java");
        assertTrue(m.find());
        assertEquals("/src/main/java/com/example/App.java", m.group());
    }

    @Test
    @DisplayName("matches .log file path")
    void matchesLogFilePath() {
        Matcher m = FILE_PATH_PATTERN.matcher("See /var/log/tomcat/catalina.log for details");
        assertTrue(m.find());
        assertEquals("/var/log/tomcat/catalina.log", m.group());
    }

    @Test
    @DisplayName("no file match on non-matching extension")
    void noFileMatchOnOtherExtension() {
        Matcher m = FILE_PATH_PATTERN.matcher("Using /opt/tomcat/bin/catalina.sh");
        assertFalse(m.find());
    }

    @Test
    @DisplayName("multiple URLs in single line")
    void multipleUrlsInLine() {
        String line = "Forwarding http://localhost:8080/ to https://example.com/api";
        Matcher m = URL_PATTERN.matcher(line);
        assertTrue(m.find());
        String first = m.group();
        assertTrue(m.find());
        String second = m.group();
        assertTrue(first.contains("localhost"));
        assertTrue(second.contains("example.com"));
    }

    @Test
    @DisplayName("URL with query parameters and fragment")
    void urlWithQueryAndFragment() {
        Matcher m = URL_PATTERN.matcher("http://localhost:8080/app?foo=bar&baz=1#section");
        assertTrue(m.find());
        String matched = m.group();
        assertTrue(matched.contains("foo=bar"));
        assertTrue(matched.contains("#section"));
    }
}
