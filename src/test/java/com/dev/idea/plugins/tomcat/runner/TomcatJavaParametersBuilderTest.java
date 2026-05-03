package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.configurations.ParametersList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static helpers in {@link TomcatJavaParametersBuilder}.
 *
 * <p>The instance pipeline (Catalina home/base resolution, classpath wiring,
 * VM options composition, deployment artifact wiring) is exercised by the
 * companion platform-fixture test {@link TomcatJavaParametersBuilderPlatformTest}
 * — it needs a real {@link com.intellij.openapi.project.Project} and a
 * registered {@link com.dev.idea.plugins.tomcat.setting.TomcatInfo}, which
 * pure unit tests can't construct.
 *
 * <p>This file pins the contract for the one method that is genuinely
 * standalone: {@link TomcatJavaParametersBuilder#injectJdwpAgent}, the canonical
 * JDWP-agent appender. The shape of the emitted argument is contractual — the
 * IDE's debugger reads it back to know which port to attach to, and several
 * downstream pieces (TomcatCommandLineState's manual-JDWP detector,
 * {@link com.dev.idea.plugins.tomcat.TomcatConstants}'s format constants) hard-depend
 * on the exact form.
 */
@DisplayName("TomcatJavaParametersBuilder — static helpers")
class TomcatJavaParametersBuilderTest {

    @Nested
    @DisplayName("injectJdwpAgent(vmParams, port)")
    class InjectJdwpAgent {

        @Test
        @DisplayName("emits canonical -agentlib:jdwp form on the requested port")
        void emitsCanonicalForm() {
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5007);

            // Canonical: transport=dt_socket, server=y, suspend=n, address=*:<port>.
            // The address must be *:port (not just port) so the JVM listens on all
            // interfaces — JDK 9+ binds only to localhost without the wildcard,
            // which breaks debug attach from containers and remote IDEs.
            String expected = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5007";
            assertTrue(params.getParameters().contains(expected),
                    "expected params to contain " + expected + " but got " + params.getParameters());
        }

        @Test
        @DisplayName("appends each call without deduping — caller responsibility")
        void doesNotDedupOnSecondCall() {
            // The helper is intentionally a plain append: gating on debugMode
            // is the caller's job. Pinning current behaviour makes the design
            // decision explicit so a future contributor doesn't quietly add
            // a "convenient" dedup that masks double-injection bugs upstream.
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);
            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5006);

            assertEquals(2, params.getParameters().stream()
                    .filter(p -> p.startsWith("-agentlib:jdwp="))
                    .count());
        }

        @Test
        @DisplayName("address uses *:port wildcard for cross-host attach")
        void wildcardAddressBinding() {
            // Without "*:" the JVM (>= JDK 9) binds JDWP only to 127.0.0.1.
            // Container -> host attach, Codespaces, and remote IDEs all fail.
            // Encode the wildcard as a hard contract — anyone removing it must
            // own up to the regression in the test name.
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);

            String agent = onlyJdwpAgent(params);
            assertTrue(agent.contains("address=*:5005"),
                    "JDWP address must be wildcard-bound, was: " + agent);
        }

        @Test
        @DisplayName("transport is dt_socket (not dt_shmem)")
        void socketTransport() {
            // Shared-memory JDWP was dropped from the model; socket-only is
            // the documented contract end-to-end. Pin it here so a refactor
            // that re-introduces a transport switch can't bypass review.
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);

            String agent = onlyJdwpAgent(params);
            assertTrue(agent.contains("transport=dt_socket"),
                    "JDWP transport must be dt_socket, was: " + agent);
            assertFalse(agent.contains("dt_shmem"),
                    "shared-memory transport must not be emitted");
        }

        @Test
        @DisplayName("server mode is y (Tomcat listens, IDE attaches)")
        void serverModeY() {
            // server=n would make the JVM try to attach to the IDE — wrong
            // direction. server=y is what makes Tomcat the listener.
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);

            assertTrue(onlyJdwpAgent(params).contains("server=y"));
        }

        @Test
        @DisplayName("suspend is n (Tomcat starts immediately; IDE attaches when ready)")
        void suspendModeN() {
            // suspend=y makes the JVM block at startup until the IDE attaches.
            // For the IDE-launched debug flow we want the JVM running so the
            // IDE can attach without ordering ceremony. Pin n so an accidental
            // change doesn't cause every debug launch to hang.
            ParametersList params = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);

            assertTrue(onlyJdwpAgent(params).contains("suspend=n"));
        }

        @Test
        @DisplayName("preserves pre-existing VM parameters and appends agent at the end")
        void preservesExistingParameters() {
            ParametersList params = new ParametersList();
            params.add("-Xmx512m");
            params.add("-Dspring.profiles.active=dev");

            TomcatJavaParametersBuilder.injectJdwpAgent(params, 5005);

            List<String> all = params.getParameters();
            assertEquals(3, all.size(), "expected exactly 3 params, was: " + all);
            assertEquals("-Xmx512m", all.get(0));
            assertEquals("-Dspring.profiles.active=dev", all.get(1));
            assertTrue(all.get(2).startsWith("-agentlib:jdwp="),
                    "agent must be appended at the end, was: " + all);
        }

        @Test
        @DisplayName("renders different ports independently")
        void rendersArbitraryPorts() {
            // The format string uses %d, not a hardcoded port. Verify two
            // distant ports both round-trip cleanly so we don't regress on
            // a misuse like %s that passes for 5005 but mangles 65535.
            ParametersList lo = new ParametersList();
            ParametersList hi = new ParametersList();

            TomcatJavaParametersBuilder.injectJdwpAgent(lo, 1024);
            TomcatJavaParametersBuilder.injectJdwpAgent(hi, 65535);

            assertTrue(onlyJdwpAgent(lo).contains("address=*:1024"));
            assertTrue(onlyJdwpAgent(hi).contains("address=*:65535"));
        }

        // --- helpers ---

        private static String onlyJdwpAgent(ParametersList params) {
            return params.getParameters().stream()
                    .filter(p -> p.startsWith("-agentlib:jdwp="))
                    .reduce((a, b) -> {
                        throw new AssertionError("expected exactly one JDWP agent, got two: " + a + " | " + b);
                    })
                    .orElseThrow(() -> new AssertionError("no JDWP agent in: " + params.getParameters()));
        }
    }
}
