package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of {@link TomcatModuleUtils#isServletContainerInitializerService},
 * the predicate behind the spec-based classpath signal. It recognises the Servlet
 * specification's own bootstrap hook in both API eras and nothing else — no
 * framework name, no jar name, no build-file coordinate.
 */
@DisplayName("TomcatModuleUtils.isServletContainerInitializerService")
class TomcatModuleUtilsTest {

    @Test
    @DisplayName("accepts the spec's service file in both API eras")
    void acceptsBothApiEras() {
        assertTrue(TomcatModuleUtils.isServletContainerInitializerService(
                "META-INF/services/jakarta.servlet.ServletContainerInitializer"));
        assertTrue(TomcatModuleUtils.isServletContainerInitializerService(
                "META-INF/services/javax.servlet.ServletContainerInitializer"));
    }

    @Test
    @DisplayName("rejects any other service file, class, or resource")
    void rejectsEverythingElse() {
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService(
                "META-INF/services/com.example.spi.OtherService"));
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService(
                "jakarta/servlet/ServletContainerInitializer.class"));
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService("META-INF/MANIFEST.MF"));
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService("app-web-1.0.jar"));
    }

    @Test
    @DisplayName("case-sensitive, as JAR entries are")
    void caseSensitive() {
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService(
                "META-INF/services/jakarta.servlet.servletcontainerinitializer"));
    }

    @Test
    @DisplayName("null-safe")
    void nullSafe() {
        assertFalse(TomcatModuleUtils.isServletContainerInitializerService(null));
    }
}
