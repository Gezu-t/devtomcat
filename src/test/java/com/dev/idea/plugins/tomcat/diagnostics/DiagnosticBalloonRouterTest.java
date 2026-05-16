package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DiagnosticBalloonRouter} — verifies dedup behaviour,
 * disposed-project gating, and notifier delegation without standing up the
 * platform NotificationGroupManager.
 *
 * <p>The router is constructed with the injectable {@link DiagnosticBalloonRouter.Notifier}
 * overload so each test can capture exactly which balloons would have fired.
 */
class DiagnosticBalloonRouterTest {

    private final Project project = mock(Project.class);
    private final TomcatRunConfiguration configuration = mock(TomcatRunConfiguration.class);

    private final List<CapturedCall> calls = new ArrayList<>();
    private final DiagnosticBalloonRouter.Notifier capturingNotifier =
            (p, title, content, actionLabel, action) ->
                    calls.add(new CapturedCall(title, content, actionLabel));

    private DiagnosticBalloonRouter router;

    @BeforeEach
    void setUp() {
        when(project.isDisposed()).thenReturn(false);
        router = new DiagnosticBalloonRouter(project, configuration, capturingNotifier);
    }

    private record CapturedCall(String title, String content, String actionLabel) {}

    private static TomcatErrorDiagnostics.Diagnostic diag(String category, String message,
                                                          String suggestion, String quickFixId) {
        return new TomcatErrorDiagnostics.Diagnostic(
                TomcatErrorDiagnostics.Severity.CRITICAL,
                category, message, suggestion, quickFixId);
    }

    @Nested
    @DisplayName("happy path: emits balloon for new diagnostic")
    class HappyPath {

        @Test
        @DisplayName("balloon fires with category+message as title and suggestion as content")
        void firstRouteEmits() {
            router.route(diag("Port Conflict",
                    "Address already in use on port 8080",
                    "Change the port in the Server tab.",
                    "FIX_PORT"));

            assertEquals(1, calls.size());
            CapturedCall call = calls.get(0);
            assertEquals("Port Conflict: Address already in use on port 8080", call.title());
            assertEquals("Change the port in the Server tab.", call.content());
            assertEquals("Open Run Configuration", call.actionLabel());
        }

        @Test
        @DisplayName("shownCount tracks distinct keys")
        void shownCountTracksDistinctKeys() {
            router.route(diag("Port Conflict", "Port 8080", "...", "FIX_PORT"));
            router.route(diag("Missing Class", "com.foo.Bar", "...", "FIX_CLASSPATH"));
            assertEquals(2, router.shownCount());
        }
    }

    @Nested
    @DisplayName("dedup")
    class Dedup {

        @Test
        @DisplayName("same category + message routes only once even across many invocations")
        void sameKeyIsIdempotent() {
            TomcatErrorDiagnostics.Diagnostic d =
                    diag("Port Conflict", "Address already in use on port 8080", "...", "FIX_PORT");
            for (int i = 0; i < 50; i++) {
                router.route(d);
            }
            assertEquals(1, calls.size(), "cascading log lines should not multiply the balloon");
        }

        @Test
        @DisplayName("distinct messages within the same category each pop a balloon")
        void differentMessagesEachShow() {
            router.route(diag("Port Conflict", "Address already in use on port 8080", "...", "FIX_PORT"));
            router.route(diag("Port Conflict", "Address already in use on port 8443", "...", "FIX_PORT"));
            assertEquals(2, calls.size());
        }

        @Test
        @DisplayName("distinct categories with same message each pop a balloon")
        void differentCategoriesEachShow() {
            router.route(diag("Port Conflict", "boom", "...", "FIX_PORT"));
            router.route(diag("Missing Class", "boom", "...", "FIX_CLASSPATH"));
            assertEquals(2, calls.size());
        }

        @Test
        @DisplayName("dedup is stable across all four quickFixIds")
        void allFourQuickFixTypesDedupIndependently() {
            router.route(diag("Port Conflict", "x", "y", "FIX_PORT"));
            router.route(diag("Missing Class", "x", "y", "FIX_CLASSPATH"));
            router.route(diag("Out of Memory", "x", "y", "FIX_MEMORY"));
            router.route(diag("Java Version Mismatch", "x", "y", "FIX_JRE"));
            assertEquals(4, calls.size());

            // Re-route every one — should still be 4
            router.route(diag("Port Conflict", "x", "y", "FIX_PORT"));
            router.route(diag("Missing Class", "x", "y", "FIX_CLASSPATH"));
            router.route(diag("Out of Memory", "x", "y", "FIX_MEMORY"));
            router.route(diag("Java Version Mismatch", "x", "y", "FIX_JRE"));
            assertEquals(4, calls.size());
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("disposed project is a no-op — no balloon, no dedup-set growth")
        void disposedProjectShortCircuits() {
            when(project.isDisposed()).thenReturn(true);
            router.route(diag("Port Conflict", "Port 8080", "...", "FIX_PORT"));
            assertTrue(calls.isEmpty());
            assertEquals(0, router.shownCount());
        }
    }
}
