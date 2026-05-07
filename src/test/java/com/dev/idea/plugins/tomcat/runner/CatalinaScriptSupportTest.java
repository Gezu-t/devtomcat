package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.projectRoots.Sdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalinaScriptSupport} — the catalina-script
 * identification and JDWP/JPDA injection helpers extracted from
 * {@link TomcatCommandLineState}.
 *
 * <p>These primitives are pure functions, so the test suite is exhaustive:
 * every branch of every method is pinned with an explicit case. The launcher
 * delegates to these helpers in two places (debug-mode JDWP duplication
 * warning, custom-script debug wiring), and the entire 1.0.7 debug-mode
 * fix depends on the contract held here. A regression in any of these
 * functions silently breaks Tomcat debugging end-to-end.
 */
@DisplayName("CatalinaScriptSupport")
class CatalinaScriptSupportTest {

    @Nested
    @DisplayName("hasManualJdwpAgent(vmOptions)")
    class HasManualJdwpAgent {

        @Test
        @DisplayName("matches canonical -agentlib:jdwp=... form")
        void matchesCanonicalForm() {
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent(
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"));
        }

        @Test
        @DisplayName("matches when JDWP is embedded among other VM options")
        void matchesEmbedded() {
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent(
                    "-Xmx512m -agentlib:jdwp=transport=dt_socket,server=y,address=5005 -Dfoo=bar"));
        }

        @Test
        @DisplayName("matches bare -agentlib:jdwp at end of string (no '=')")
        void matchesBareAtEnd() {
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent("-agentlib:jdwp"));
        }

        @Test
        @DisplayName("matches -agentlib:jdwp followed by a space")
        void matchesFollowedBySpace() {
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent("-agentlib:jdwp -Xmx512m"));
        }

        @Test
        @DisplayName("matches -agentlib:jdwp followed by a tab")
        void matchesFollowedByTab() {
            // Character.isWhitespace covers tab/newline/vertical-tab; ensure the
            // implementation honors the whole whitespace class, not just space.
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent("-agentlib:jdwp\t-Xmx512m"));
        }

        @Test
        @DisplayName("does not match unrelated agent like -agentlib:jdwp_other")
        void doesNotMatchSimilarlyNamedAgent() {
            // The boundary check (end-of-string / '=' / whitespace) exists
            // specifically to prevent false positives on -agentlib:jdwp_other
            // and similar adjacent identifiers.
            assertFalse(CatalinaScriptSupport.hasManualJdwpAgent("-agentlib:jdwp_other"));
        }

        @Test
        @DisplayName("does not match -agentlib:jdwpfoo (no separator)")
        void doesNotMatchAdjacentLetters() {
            assertFalse(CatalinaScriptSupport.hasManualJdwpAgent("-agentlib:jdwpfoo"));
        }

        @Test
        @DisplayName("returns false for null")
        void returnsFalseForNull() {
            assertFalse(CatalinaScriptSupport.hasManualJdwpAgent(null));
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmpty() {
            assertFalse(CatalinaScriptSupport.hasManualJdwpAgent(""));
        }

        @Test
        @DisplayName("returns false when JDWP not present at all")
        void returnsFalseWhenAbsent() {
            assertFalse(CatalinaScriptSupport.hasManualJdwpAgent("-Xmx512m -Dfoo=bar"));
        }

        @Test
        @DisplayName("real agent later in the string is detected past a leading -agentlib:jdwp_other")
        void rejectedLeadingMatchDoesNotMaskRealAgent() {
            // Regression: a single-shot indexOf would stop at the first match
            // (-agentlib:jdwp_other), reject it on the boundary check, and miss
            // the real -agentlib:jdwp= later in the string. The duplicate-agent
            // warning relies on this method, so a miss = silent two-agent JVM.
            assertTrue(CatalinaScriptSupport.hasManualJdwpAgent(
                    "-agentlib:jdwp_other -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"));
        }
    }

    @Nested
    @DisplayName("isCatalinaCommand(tokens)")
    class IsCatalinaCommand {

        @Test
        @DisplayName("matches catalina.sh as bare command")
        void matchesCatalinaShBare() {
            assertTrue(CatalinaScriptSupport.isCatalinaCommand(List.of("catalina.sh", "run")));
        }

        @Test
        @DisplayName("matches catalina.bat as bare command")
        void matchesCatalinaBatBare() {
            assertTrue(CatalinaScriptSupport.isCatalinaCommand(List.of("catalina.bat", "run")));
        }

        @Test
        @DisplayName("matches catalina.sh via absolute path")
        void matchesCatalinaShAbsolute() {
            // The implementation extracts just the filename via Path.of(...).getFileName(),
            // so an installation-prefixed path must still be recognized.
            assertTrue(CatalinaScriptSupport.isCatalinaCommand(
                    List.of("/usr/local/tomcat/bin/catalina.sh", "run")));
        }

        @Test
        @DisplayName("matches Catalina.sh case-insensitively")
        void matchesMixedCase() {
            assertTrue(CatalinaScriptSupport.isCatalinaCommand(List.of("Catalina.sh", "run")));
        }

        @Test
        @DisplayName("matches catalina (no extension)")
        void matchesCatalinaNoExtension() {
            // The check is startsWith("catalina") after lowercasing, so an
            // extension-less path still qualifies.
            assertTrue(CatalinaScriptSupport.isCatalinaCommand(List.of("catalina", "run")));
        }

        @Test
        @DisplayName("does not match an unrelated command")
        void doesNotMatchJava() {
            assertFalse(CatalinaScriptSupport.isCatalinaCommand(List.of("java", "-jar", "tomcat.jar")));
        }

        @Test
        @DisplayName("does not match a command that merely contains 'catalina'")
        void doesNotMatchSubstringMatch() {
            // startsWith, not contains — the script name must lead the filename.
            assertFalse(CatalinaScriptSupport.isCatalinaCommand(List.of("my-catalina-wrapper.sh", "run")));
        }

        @Test
        @DisplayName("returns false for an empty token list")
        void returnsFalseForEmptyList() {
            assertFalse(CatalinaScriptSupport.isCatalinaCommand(List.of()));
        }
    }

    @Nested
    @DisplayName("usesCatalinaJpda(tokens)")
    class UsesCatalinaJpda {

        @Test
        @DisplayName("returns true when 'jpda' is present")
        void returnsTrueWhenJpdaPresent() {
            assertTrue(CatalinaScriptSupport.usesCatalinaJpda(
                    List.of("catalina.sh", "jpda", "run")));
        }

        @Test
        @DisplayName("matches 'JPDA' case-insensitively")
        void matchesUpperCase() {
            assertTrue(CatalinaScriptSupport.usesCatalinaJpda(
                    List.of("catalina.sh", "JPDA", "run")));
        }

        @Test
        @DisplayName("returns false when 'jpda' is absent")
        void returnsFalseWhenAbsent() {
            assertFalse(CatalinaScriptSupport.usesCatalinaJpda(
                    List.of("catalina.sh", "run")));
        }

        @Test
        @DisplayName("returns false for empty list")
        void returnsFalseForEmptyList() {
            assertFalse(CatalinaScriptSupport.usesCatalinaJpda(List.of()));
        }
    }

    @Nested
    @DisplayName("enableCatalinaJpda(tokens)")
    class EnableCatalinaJpda {

        @Test
        @DisplayName("inserts 'jpda' before 'run'")
        void insertsBeforeRun() {
            assertEquals(
                    List.of("catalina.sh", "jpda", "run"),
                    CatalinaScriptSupport.enableCatalinaJpda(List.of("catalina.sh", "run"))
            );
        }

        @Test
        @DisplayName("inserts 'jpda' before 'start'")
        void insertsBeforeStart() {
            // Tomcat's catalina script accepts both 'run' (foreground) and
            // 'start' (background) — both must accept JPDA injection in debug mode.
            assertEquals(
                    List.of("catalina.sh", "jpda", "start"),
                    CatalinaScriptSupport.enableCatalinaJpda(List.of("catalina.sh", "start"))
            );
        }

        @Test
        @DisplayName("inserts 'jpda' before 'RUN' (case-insensitive)")
        void insertsBeforeMixedCaseRun() {
            assertEquals(
                    List.of("catalina.sh", "jpda", "RUN"),
                    CatalinaScriptSupport.enableCatalinaJpda(List.of("catalina.sh", "RUN"))
            );
        }

        @Test
        @DisplayName("does not duplicate 'jpda' when already present")
        void doesNotDuplicate() {
            List<String> input = List.of("catalina.sh", "jpda", "run");
            assertEquals(input, CatalinaScriptSupport.enableCatalinaJpda(input));
        }

        @Test
        @DisplayName("returns input unchanged for non-catalina commands")
        void leavesNonCatalinaUntouched() {
            // A custom startup script that doesn't use catalina (e.g. a Spring
            // Boot fat-jar) must not have its arguments rewritten — JPDA is a
            // catalina-specific feature.
            List<String> input = List.of("java", "-jar", "myapp.jar");
            assertSame(input, CatalinaScriptSupport.enableCatalinaJpda(input),
                    "non-catalina commands should be returned by reference, untouched");
        }

        @Test
        @DisplayName("returns input unchanged for catalina with neither 'run' nor 'start'")
        void leavesCatalinaStopUntouched() {
            // catalina.sh stop / version / configtest don't take JPDA — the
            // function shouldn't speculatively insert it.
            List<String> input = List.of("catalina.sh", "stop");
            assertSame(input, CatalinaScriptSupport.enableCatalinaJpda(input));
        }

        @Test
        @DisplayName("returns input unchanged for empty list")
        void returnsEmptyListUntouched() {
            List<String> input = List.of();
            assertSame(input, CatalinaScriptSupport.enableCatalinaJpda(input));
        }

        @Test
        @DisplayName("preserves additional arguments after 'run'")
        void preservesTrailingArguments() {
            // Real-world catalina invocations often have extra args after 'run'
            // (e.g. -security). The insertion must not drop them.
            assertEquals(
                    List.of("catalina.sh", "jpda", "run", "-security"),
                    CatalinaScriptSupport.enableCatalinaJpda(
                            List.of("catalina.sh", "run", "-security"))
            );
        }
    }

    @Nested
    @DisplayName("appendVmOptIfMissing(currentValue, vmOpt)")
    class AppendVmOptIfMissing {

        private static final String JDWP =
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005";

        @Test
        @DisplayName("appends to non-empty value with single space separator")
        void appendsToExisting() {
            assertEquals("-Xmx512m " + JDWP,
                    CatalinaScriptSupport.appendVmOptIfMissing("-Xmx512m", JDWP));
        }

        @Test
        @DisplayName("returns vmOpt alone when current value is null")
        void appendsToNull() {
            assertEquals(JDWP,
                    CatalinaScriptSupport.appendVmOptIfMissing(null, JDWP));
        }

        @Test
        @DisplayName("returns vmOpt alone when current value is empty")
        void appendsToEmpty() {
            assertEquals(JDWP,
                    CatalinaScriptSupport.appendVmOptIfMissing("", JDWP));
        }

        @Test
        @DisplayName("returns vmOpt alone when current value is whitespace")
        void appendsToWhitespace() {
            // notNullize().trim() produces "" for "   ", which is the empty
            // branch — the result must be just the new option, not "   <opt>".
            assertEquals(JDWP,
                    CatalinaScriptSupport.appendVmOptIfMissing("   ", JDWP));
        }

        @Test
        @DisplayName("returns trimmed current value when JDWP already present (no duplicate)")
        void doesNotDuplicateWhenJdwpAlreadyPresent() {
            // The whole point of this helper: prevent two -agentlib:jdwp args
            // in CATALINA_OPTS / JAVA_OPTS, which would cause the JVM to bind
            // a second debug port that the IDE never connects to.
            assertEquals(JDWP,
                    CatalinaScriptSupport.appendVmOptIfMissing(JDWP, JDWP));
        }

        @Test
        @DisplayName("returns trimmed current value when JDWP present alongside other opts")
        void doesNotDuplicateWhenJdwpEmbedded() {
            String current = "  -Xmx512m " + JDWP + " -Dfoo=bar  ";
            assertEquals(current.trim(),
                    CatalinaScriptSupport.appendVmOptIfMissing(current, JDWP));
        }
    }

    @Nested
    @DisplayName("applyCustomScriptDebugSupport(commandLine, tokens, debugPort)")
    class ApplyCustomScriptDebugSupport {

        @Test
        @DisplayName("for catalina script: sets all JPDA_* env vars and JDWP opts")
        void setsAllJpdaEnvVarsForCatalinaScript() {
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "jpda", "run");
            commandLine.withEnvironment(TomcatConstants.ENV_CATALINA_OPTS, "-Dfoo=bar");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "jpda", "run"), 5005);

            assertEquals("5005", commandLine.getEnvironment().get(TomcatConstants.ENV_DEBUG_PORT));
            assertEquals("5005", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_ADDRESS));
            assertEquals("dt_socket", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_TRANSPORT));
            assertEquals("n", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_SUSPEND));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS).contains("address=*:5005"));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_OPTS).contains("address=*:5005"));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS).contains("address=*:5005"));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS).contains("address=*:5005"));
        }

        @Test
        @DisplayName("for non-catalina script: skips JPDA_* env vars but still sets JDWP/CATALINA/JAVA opts")
        void skipsJpdaEnvVarsForNonCatalinaScript() {
            // A custom script using a non-catalina launcher (e.g. Spring Boot
            // fat-jar) won't read JPDA_* — those vars are catalina-specific.
            // The generic CATALINA_OPTS/JAVA_OPTS path still applies because
            // any embedded catalina invocation downstream may pick them up.
            GeneralCommandLine commandLine = new GeneralCommandLine("java", "-jar", "myapp.jar");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("java", "-jar", "myapp.jar"), 5005);

            assertEquals("5005", commandLine.getEnvironment().get(TomcatConstants.ENV_DEBUG_PORT));
            assertNull(commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_ADDRESS),
                    "JPDA_ADDRESS must not be set for non-catalina commands");
            assertNull(commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_TRANSPORT));
            assertNull(commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_SUSPEND));
            assertNull(commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_OPTS));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS).contains("address=*:5005"));
            // CATALINA_OPTS/JAVA_OPTS get the JDWP opt regardless — they are the
            // universal "JVM args" channel, not catalina-specific.
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS).contains("address=*:5005"));
            assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS).contains("address=*:5005"));
        }

        @Test
        @DisplayName("creates CATALINA_OPTS from scratch when not previously set")
        void createsCatalinaOptsWhenAbsent() {
            // No environment seeded — appendVmOptIfMissing(null, jdwp) must
            // produce just the JDWP arg (no leading space, no null literal).
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "run");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "run"), 5005);

            String catalinaOpts = commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS);
            assertNotNull(catalinaOpts);
            assertTrue(catalinaOpts.startsWith("-agentlib:jdwp="),
                    "CATALINA_OPTS should start with -agentlib:jdwp= when previously empty, was: " + catalinaOpts);
            assertFalse(catalinaOpts.contains("null"),
                    "Must not contain literal 'null'");
        }

        @Test
        @DisplayName("creates JAVA_OPTS from scratch when not previously set")
        void createsJavaOptsWhenAbsent() {
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "run");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "run"), 5005);

            String javaOpts = commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS);
            assertNotNull(javaOpts);
            assertTrue(javaOpts.startsWith("-agentlib:jdwp="),
                    "JAVA_OPTS should start with -agentlib:jdwp= when previously empty, was: " + javaOpts);
            assertFalse(javaOpts.contains("null"));
        }

        @Test
        @DisplayName("does not duplicate JDWP when JAVA_OPTS already contains an agent")
        void doesNotDuplicateJdwpInJavaOpts() {
            // Defensive against a user who manually exported JAVA_OPTS with
            // -agentlib:jdwp before launching. We don't want two debug agents
            // on different ports — appendVmOptIfMissing prevents the duplicate.
            String existing = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:6000";
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "run");
            commandLine.withEnvironment(TomcatConstants.ENV_JAVA_OPTS, existing);

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "run"), 5005);

            String javaOpts = commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS);
            int first = javaOpts.indexOf("-agentlib:jdwp=");
            int second = javaOpts.indexOf("-agentlib:jdwp=", first + 1);
            assertTrue(first >= 0, "expected at least one JDWP agent");
            assertEquals(-1, second, "must not have two JDWP agents in JAVA_OPTS, was: " + javaOpts);
        }

        @Test
        @DisplayName("preserves existing CATALINA_OPTS values when appending JDWP")
        void preservesExistingCatalinaOpts() {
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "run");
            commandLine.withEnvironment(TomcatConstants.ENV_CATALINA_OPTS, "-Xmx1g -Dfoo=bar");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "run"), 5005);

            String catalinaOpts = commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS);
            assertTrue(catalinaOpts.contains("-Xmx1g"), "must preserve heap size");
            assertTrue(catalinaOpts.contains("-Dfoo=bar"), "must preserve user property");
            assertTrue(catalinaOpts.contains("-agentlib:jdwp="), "must add JDWP");
        }

        @Test
        @DisplayName("Java 8 JDK uses no-host JDWP address across every env var (TRANSPORT_INIT(510) repro)")
        void java8UsesNoHostAddressAcrossEnvVars() {
            // Bug repro for the Java 8 + custom-script path: every channel that
            // carries JDWP (TOMCAT_JDWP_OPTS, JPDA_OPTS, CATALINA_OPTS, JAVA_OPTS)
            // must use the no-host form on Java 8 so the JVM does not bail out
            // with TRANSPORT_INIT(510) before Tomcat boots.
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "jpda", "run");
            Sdk java8 = mockSdk("1.8.0_392");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "jpda", "run"), 5005, java8);

            String expected = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005";
            assertAll(
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS)),
                    // Negative — none of the channels should leak the wildcard form.
                    () -> assertFalse(
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS).contains("address=*:")),
                    () -> assertFalse(
                            commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS).contains("address=*:"))
            );
        }

        @Test
        @DisplayName("Java 9+ JDK keeps wildcard JDWP address in every env var")
        void java9KeepsWildcardAcrossEnvVars() {
            GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "jpda", "run");
            Sdk java17 = mockSdk("17.0.10");

            CatalinaScriptSupport.applyCustomScriptDebugSupport(
                    commandLine, List.of("catalina.sh", "jpda", "run"), 5005, java17);

            String expected = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005";
            assertAll(
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS)),
                    () -> assertEquals(expected,
                            commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS))
            );
        }

        private static Sdk mockSdk(String versionString) {
            Sdk sdk = mock(Sdk.class);
            when(sdk.getVersionString()).thenReturn(versionString);
            return sdk;
        }
    }
}
