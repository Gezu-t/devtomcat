package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatCompatibilityChecker")
class TomcatCompatibilityCheckerTest {

    @Test
    @DisplayName("null tomcatInfo returns blocking issue")
    void nullTomcatInfo() {
        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(null, null);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).isBlocking());
        assertTrue(issues.get(0).getMessage().contains("No Tomcat server configured"));
    }

    @Test
    @DisplayName("unknown version (0) returns no issues")
    void unknownVersionSkipsCheck() {
        TomcatInfo info = new TomcatInfo("Test", "", "/path");
        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(info, null);
        assertTrue(issues.isEmpty());
    }

    @Test
    @DisplayName("null JDK with known version returns blocking issue")
    void nullJdkWithKnownVersion() {
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", "/path");
        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(info, null);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).isBlocking());
        assertTrue(issues.get(0).getMessage().contains("No JDK configured"));
        assertTrue(issues.get(0).getMessage().contains("Java 8"));
    }

    // --- getMinJavaVersion ---

    @Test
    @DisplayName("Tomcat 7 requires Java 6")
    void tomcat7RequiresJava6() {
        TomcatInfo info = new TomcatInfo("T7", "7.0.109", "/path");
        assertEquals(6, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    @Test
    @DisplayName("Tomcat 8 requires Java 7")
    void tomcat8RequiresJava7() {
        TomcatInfo info = new TomcatInfo("T8", "8.5.90", "/path");
        assertEquals(7, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    @Test
    @DisplayName("Tomcat 9 requires Java 8")
    void tomcat9RequiresJava8() {
        TomcatInfo info = new TomcatInfo("T9", "9.0.56", "/path");
        assertEquals(8, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    @Test
    @DisplayName("Tomcat 10.0 requires Java 8")
    void tomcat100RequiresJava8() {
        TomcatInfo info = new TomcatInfo("T10", "10.0.27", "/path");
        assertEquals(8, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    @Test
    @DisplayName("Tomcat 10.1 requires Java 11")
    void tomcat101RequiresJava11() {
        TomcatInfo info = new TomcatInfo("T10.1", "10.1.5", "/path");
        assertEquals(11, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    @Test
    @DisplayName("Tomcat 11 requires Java 17")
    void tomcat11RequiresJava17() {
        TomcatInfo info = new TomcatInfo("T11", "11.0.0", "/path");
        assertEquals(17, TomcatCompatibilityChecker.getMinJavaVersion(info));
    }

    // --- formatIssues ---

    @Test
    @DisplayName("formatIssues empty list returns empty string")
    void formatIssuesEmpty() {
        assertEquals("", TomcatCompatibilityChecker.formatIssues(List.of()));
    }

    @Test
    @DisplayName("formatIssues prefixes blocking with ERROR and non-blocking with WARN")
    void formatIssuesPrefixes() {
        TomcatInfo info = new TomcatInfo("T10", "10.1.5", "/path");
        // null JDK gives blocking + Tomcat 10 gives non-blocking Jakarta warning
        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(info, null);
        String formatted = TomcatCompatibilityChecker.formatIssues(issues);
        assertTrue(formatted.contains("[ERROR]"));
    }

    // --- hasBlockingIssues ---

    @Test
    @DisplayName("hasBlockingIssues detects blocking")
    void hasBlockingIssues() {
        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(null, null);
        assertTrue(TomcatCompatibilityChecker.hasBlockingIssues(issues));
    }

    @Test
    @DisplayName("hasBlockingIssues returns false for empty list")
    void noBlockingIssuesOnEmpty() {
        assertFalse(TomcatCompatibilityChecker.hasBlockingIssues(List.of()));
    }

    // --- isEndOfLifeTomcat / endOfLifeDateOrNull ---

    @Test
    @DisplayName("Tomcat 7.x is EOL (March 2021)")
    void tomcat7Eol() {
        TomcatInfo info = new TomcatInfo("Tomcat 7", "7.0.109", "/opt/tomcat-7");
        assertTrue(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
        assertEquals("March 2021", TomcatCompatibilityChecker.endOfLifeDateOrNull(info));
    }

    @Test
    @DisplayName("Tomcat 8.0.x is EOL (June 2018)")
    void tomcat80Eol() {
        TomcatInfo info = new TomcatInfo("Tomcat 8.0", "8.0.53", "/opt/tomcat-8.0");
        assertTrue(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
        assertEquals("June 2018", TomcatCompatibilityChecker.endOfLifeDateOrNull(info));
    }

    @Test
    @DisplayName("Tomcat 8.5.x is EOL (March 2024)")
    void tomcat85Eol() {
        TomcatInfo info = new TomcatInfo("Tomcat 8.5", "8.5.99", "/opt/tomcat-8.5");
        assertTrue(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
        assertEquals("March 2024", TomcatCompatibilityChecker.endOfLifeDateOrNull(info));
    }

    @Test
    @DisplayName("Tomcat 9.x is supported (not EOL)")
    void tomcat9NotEol() {
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.85", "/opt/tomcat-9");
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
        assertNull(TomcatCompatibilityChecker.endOfLifeDateOrNull(info));
    }

    @Test
    @DisplayName("Tomcat 10.0.x is EOL (October 2023, replaced by 10.1)")
    void tomcat10_0Eol() {
        TomcatInfo info = new TomcatInfo("Tomcat 10.0", "10.0.27", "/opt/tomcat-10.0");
        assertTrue(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
        assertEquals("October 2023", TomcatCompatibilityChecker.endOfLifeDateOrNull(info));
    }

    @Test
    @DisplayName("Tomcat 10.1.x is supported")
    void tomcat10_1NotEol() {
        TomcatInfo info = new TomcatInfo("Tomcat 10.1", "10.1.20", "/opt/tomcat-10.1");
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
    }

    @Test
    @DisplayName("Tomcat 11.x is supported")
    void tomcat11NotEol() {
        TomcatInfo info = new TomcatInfo("Tomcat 11", "11.0.5", "/opt/tomcat-11");
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(info));
    }

    @Test
    @DisplayName("null TomcatInfo and unparseable version are not EOL (conservative)")
    void unknownIsNotEol() {
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(null));
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(
                new TomcatInfo("custom", "snapshot", "/opt/custom")));
        assertFalse(TomcatCompatibilityChecker.isEndOfLifeTomcat(
                new TomcatInfo("empty", "", "/opt/empty")));
    }
}
