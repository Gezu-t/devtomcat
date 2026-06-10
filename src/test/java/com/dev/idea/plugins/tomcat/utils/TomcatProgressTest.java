package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Environment-safety contract for {@link TomcatProgress}: both helpers must
 * be callable from any code path — including plain unit tests with no
 * platform and production threads with no indicator attached — without
 * throwing. The cancellation behavior under a real, canceled indicator is
 * covered by the platform-fixture test in the update package.
 */
@DisplayName("TomcatProgress")
class TomcatProgressTest {

    @Test
    @DisplayName("checkCanceled is a no-op without an indicator")
    void checkCanceledIsNoOpWithoutIndicator() {
        assertDoesNotThrow(TomcatProgress::checkCanceled);
    }

    @Test
    @DisplayName("setDetail is a no-op without an indicator")
    void setDetailIsNoOpWithoutIndicator() {
        assertDoesNotThrow(() -> TomcatProgress.setDetail("any detail text"));
    }
}
