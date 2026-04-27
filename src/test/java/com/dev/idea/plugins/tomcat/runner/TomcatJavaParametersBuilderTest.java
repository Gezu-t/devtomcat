package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.configurations.ParametersList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TomcatJavaParametersBuilder")
class TomcatJavaParametersBuilderTest {

    @Test
    @DisplayName("injectJdwpAgent writes canonical -agentlib:jdwp on requested port")
    void injectJdwpAgentWritesCanonicalAgent() {
        ParametersList params = new ParametersList();

        TomcatJavaParametersBuilder.injectJdwpAgent(params, 5007);

        // Canonical form: transport=dt_socket, server=y, suspend=n, address=*:<port>.
        // The address must be *:port (not just port) so the JVM listens on all
        // interfaces on JDK 9+ — without the wildcard JDK 9+ binds only to
        // localhost and cross-container debug attaches fail.
        String expected = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5007";
        assertTrue(params.getParameters().contains(expected),
                "expected params to contain " + expected + " but got " + params.getParameters());
    }

    @Test
    @DisplayName("injectJdwpAgent doesn't duplicate when called twice — caller responsibility")
    void injectJdwpAgentAppendsEachCall() {
        // Helper is intentionally a plain append — gating on debugMode is the
        // caller's job. If we later need duplicate-prevention the test pins
        // current behaviour so the decision is explicit.
        ParametersList params = new ParametersList();

        TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);
        TomcatJavaParametersBuilder.injectJdwpAgent(params, 5006);

        assertEquals(2, params.getParameters().stream()
                .filter(p -> p.startsWith("-agentlib:jdwp="))
                .count());
    }
}
