package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of {@link TomcatModuleUtils#isWebFrameworkLibrary}, the
 * predicate behind the structural Spring-MVC classpath signal. Matching is on the
 * resolved jar name, not a build-file coordinate. It is deliberately scoped to
 * {@code spring-webmvc} (the servlet web framework that hosts {@code DispatcherServlet}),
 * so it must accept that library and reject the non-servlet Spring artifacts that
 * share the {@code spring-web} prefix — the HTTP-client base ({@code spring-web}),
 * the reactive stack ({@code spring-webflux}), and {@code spring-websocket} — none
 * of which denote a Tomcat-deployable servlet webapp.
 */
@DisplayName("TomcatModuleUtils.isWebFrameworkLibrary")
class TomcatModuleUtilsTest {

    @Test
    @DisplayName("accepts the resolved spring-webmvc library")
    void acceptsSpringWebMvc() {
        assertTrue(TomcatModuleUtils.isWebFrameworkLibrary("spring-webmvc-6.1.0.jar"));
    }

    @Test
    @DisplayName("matches case-insensitively")
    void matchesCaseInsensitively() {
        assertTrue(TomcatModuleUtils.isWebFrameworkLibrary("SPRING-WEBMVC-6.1.0.JAR"));
    }

    @Test
    @DisplayName("rejects non-servlet Spring web libraries and unrelated libraries")
    void rejectsNonServletLibraries() {
        // The precision the narrowed prefix buys: HTTP-client base, reactive stack,
        // and websocket are not servlet-webapp signals and must not be flagged.
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("spring-web-6.1.0.jar"));
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("spring-webflux-6.1.0.jar"));
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("spring-websocket-6.1.0.jar"));
        // And the plainly non-web Spring / unrelated artifacts.
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("spring-boot-3.2.0.jar"));
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("spring-core-6.1.0.jar"));
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary("commons-lang3-3.14.0.jar"));
    }

    @Test
    @DisplayName("null-safe")
    void nullSafe() {
        assertFalse(TomcatModuleUtils.isWebFrameworkLibrary(null));
    }
}
