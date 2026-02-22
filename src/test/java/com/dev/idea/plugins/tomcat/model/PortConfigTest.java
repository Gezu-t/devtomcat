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
}
