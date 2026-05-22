package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PortConfig")
class PortConfigTest {

    @Test
    @DisplayName("defaults are correct")
    void defaultValues() {
        PortConfig pc = new PortConfig();
        assertEquals(8080, pc.getHttp());
        assertEquals(8443, pc.getHttps());
        assertEquals(1099, pc.getJmx());
        assertEquals(8009, pc.getAjp());
        assertEquals(8005, pc.getShutdown());
        assertFalse(pc.isHttpsEnabled());
        assertFalse(pc.isJmxEnabled());
        assertFalse(pc.isAjpEnabled());
    }

    @Test
    @DisplayName("two-arg constructor sets http and shutdown")
    void twoArgConstructor() {
        PortConfig pc = new PortConfig(9090, 9005);
        assertEquals(9090, pc.getHttp());
        assertEquals(9005, pc.getShutdown());
        // Others remain default
        assertEquals(8443, pc.getHttps());
    }

    @Test
    @DisplayName("four-arg constructor sets http, https, jmx, shutdown")
    void fourArgConstructor() {
        PortConfig pc = new PortConfig(9090, 9443, 9099, 9005);
        assertEquals(9090, pc.getHttp());
        assertEquals(9443, pc.getHttps());
        assertEquals(9099, pc.getJmx());
        assertEquals(9005, pc.getShutdown());
    }

    @Test
    @DisplayName("copy constructor produces independent copy")
    void copyConstructor() {
        PortConfig original = new PortConfig();
        original.setHttp(9090);
        original.setHttpsEnabled(true);
        original.setJmxEnabled(true);

        PortConfig copy = new PortConfig(original);
        assertEquals(9090, copy.getHttp());
        assertTrue(copy.isHttpsEnabled());
        assertTrue(copy.isJmxEnabled());

        // Mutating copy does not affect original
        copy.setHttp(7070);
        assertEquals(9090, original.getHttp());
    }

    @Test
    @DisplayName("clone produces equal but independent copy")
    void cloneIsIndependent() {
        PortConfig original = new PortConfig();
        original.setHttp(9090);
        original.setHttpsEnabled(true);

        PortConfig cloned = original.clone();
        assertEquals(original, cloned);

        cloned.setHttp(7070);
        assertNotEquals(original, cloned);
    }

    @Test
    @DisplayName("validate catches out-of-range ports")
    void validateOutOfRange() {
        PortConfig pc = new PortConfig();
        pc.setHttp(0);
        ValidationResult result = pc.validate();
        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessage().contains("HTTP"));
    }

    @Test
    @DisplayName("validate catches port conflicts")
    void validateConflicts() {
        PortConfig pc = new PortConfig();
        pc.setHttp(8080);
        pc.setShutdown(8080); // Conflict
        ValidationResult result = pc.validate();
        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessage().contains("multiple services"));
    }

    @Test
    @DisplayName("validate with all ports unique passes without errors")
    void validateCleanConfig() {
        PortConfig pc = new PortConfig(8080, 8005);
        ValidationResult result = pc.validate();
        // May have warnings (port in use) but should not have errors for valid range
        assertFalse(result.getErrorMessage().contains("must be between"));
    }

    @Test
    @DisplayName("validate only checks enabled optional ports")
    void validateSkipsDisabledPorts() {
        PortConfig pc = new PortConfig();
        pc.setHttps(0); // Invalid but disabled
        pc.setHttpsEnabled(false);
        ValidationResult result = pc.validate();
        // Should not have HTTPS error since it's disabled
        assertFalse(result.getErrorMessage().contains("HTTPS"));
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void equalsAndHashCode() {
        PortConfig a = new PortConfig(8080, 8443, 1099, 8005);
        PortConfig b = new PortConfig(8080, 8443, 1099, 8005);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setHttp(9090);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("setters update values correctly")
    void settersWork() {
        PortConfig pc = new PortConfig();
        pc.setHttp(1234);
        pc.setHttps(5678);
        pc.setJmx(9012);
        pc.setAjp(3456);
        pc.setShutdown(7890);
        pc.setHttpsEnabled(true);
        pc.setJmxEnabled(true);
        pc.setAjpEnabled(true);

        assertEquals(1234, pc.getHttp());
        assertEquals(5678, pc.getHttps());
        assertEquals(9012, pc.getJmx());
        assertEquals(3456, pc.getAjp());
        assertEquals(7890, pc.getShutdown());
        assertTrue(pc.isHttpsEnabled());
        assertTrue(pc.isJmxEnabled());
        assertTrue(pc.isAjpEnabled());
    }

    // --- Preferred-vs-resolved port semantics (1.1.0, Section 6.3) ---

    @Test
    @DisplayName("getPreferredHttp falls back to http when no snapshot exists")
    void preferredHttpFallbackToHttp() {
        PortConfig pc = new PortConfig();
        pc.setHttp(8081);
        // No setHttpResolved call has happened — preferred returns current.
        assertEquals(8081, pc.getPreferredHttp());
    }

    @Test
    @DisplayName("setHttpResolved snapshots current http into preferred ONCE")
    void setHttpResolvedSnapshotsPreferred() {
        PortConfig pc = new PortConfig();
        pc.setHttp(8081);                  // user's intent
        pc.setHttpResolved(8082);          // first bump — snapshot 8081
        assertEquals(8082, pc.getHttp());
        assertEquals(8081, pc.getPreferredHttp());

        pc.setHttpResolved(8083);          // second bump — DON'T re-snapshot
        assertEquals(8083, pc.getHttp());
        assertEquals(8081, pc.getPreferredHttp(),
                "second bump must NOT overwrite the original preferred snapshot");
    }

    @Test
    @DisplayName("setHttp (UI intent) clears the preferred snapshot")
    void setHttpClearsPreferred() {
        PortConfig pc = new PortConfig();
        pc.setHttp(8081);
        pc.setHttpResolved(8082);
        assertEquals(8081, pc.getPreferredHttp());

        // User edits in the UI to a different port — new intent.
        pc.setHttp(9090);
        assertEquals(9090, pc.getHttp());
        assertEquals(9090, pc.getPreferredHttp(),
                "explicit UI edit must reset preferred so it tracks the new value");
    }

    @Test
    @DisplayName("setHttp with unchanged value preserves the preferred snapshot")
    void setHttpUnchangedPreservesPreferred() {
        // Editor form-binding calls setHttp on every save, even when the value
        // didn't change. That must NOT wipe the preferred — otherwise opening
        // and closing the editor would lose drift state silently.
        PortConfig pc = new PortConfig();
        pc.setHttp(8081);
        pc.setHttpResolved(8082);
        assertEquals(8081, pc.getPreferredHttp());

        // Form save with no change → setHttp(8082) — current value
        pc.setHttp(8082);
        assertEquals(8081, pc.getPreferredHttp(),
                "no-change setHttp must preserve preferred snapshot (form save semantics)");
    }

    @Test
    @DisplayName("preferred-shutdown follows the same snapshot semantics as http")
    void preferredShutdownMirrors() {
        PortConfig pc = new PortConfig();
        pc.setShutdown(8005);
        pc.setShutdownResolved(8006);
        assertEquals(8006, pc.getShutdown());
        assertEquals(8005, pc.getPreferredShutdown());

        pc.setShutdown(9999); // user UI edit
        assertEquals(9999, pc.getPreferredShutdown());
    }

    @Test
    @DisplayName("copy constructor preserves preferred snapshots")
    void copyConstructorCarriesPreferred() {
        PortConfig original = new PortConfig();
        original.setHttp(8081);
        original.setHttpResolved(8082);
        original.setShutdown(8005);
        original.setShutdownResolved(8006);

        PortConfig copy = new PortConfig(original);
        assertEquals(8081, copy.getPreferredHttp());
        assertEquals(8005, copy.getPreferredShutdown());
    }
}
