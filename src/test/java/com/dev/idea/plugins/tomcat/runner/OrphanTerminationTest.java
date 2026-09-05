package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The polite stage waits for a real exit up to the grace, and only survivors are force-killed. */
@DisplayName("OrphanTomcatReclaimer.terminate")
class OrphanTerminationTest {

    private static ProcessHandle exitingAfter(long pid, long millis) {
        ProcessHandle p = mock(ProcessHandle.class);
        when(p.pid()).thenReturn(pid);
        CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
        when(p.onExit()).thenReturn(exit);
        when(p.destroy()).thenAnswer(inv -> {
            CompletableFuture.delayedExecutor(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> exit.complete(p));
            return true;
        });
        when(p.isAlive()).thenAnswer(inv -> !exit.isDone());
        return p;
    }

    private static ProcessHandle neverExiting(long pid) {
        ProcessHandle p = mock(ProcessHandle.class);
        when(p.pid()).thenReturn(pid);
        when(p.onExit()).thenReturn(new CompletableFuture<>());
        when(p.destroy()).thenReturn(true);
        when(p.isAlive()).thenReturn(true);
        when(p.destroyForcibly()).thenReturn(true);
        return p;
    }

    @Test
    @DisplayName("a process that exits within the grace is never force-killed, and the wait ends when it exits")
    void cleanExitIsNotForceKilled() {
        ProcessHandle p = exitingAfter(41L, 100);

        OrphanTomcatReclaimer.Termination t = OrphanTomcatReclaimer.terminate(List.of(p), 5_000);

        assertTrue(t.forceKilled().isEmpty());
        verify(p, never()).destroyForcibly();
        assertTrue(t.politeWaitMs() < 4_000, "the wait must end at the exit, not at the grace deadline: " + t.politeWaitMs());
    }

    @Test
    @DisplayName("a process still alive after the grace is force-killed")
    void survivorIsForceKilled() {
        ProcessHandle p = neverExiting(42L);

        OrphanTomcatReclaimer.Termination t = OrphanTomcatReclaimer.terminate(List.of(p), 100);

        assertEquals(List.of(42L), t.forceKilled());
        verify(p).destroyForcibly();
        assertTrue(t.politeWaitMs() >= 100, "the full grace was given first: " + t.politeWaitMs());
    }

    @Test
    @DisplayName("the production grace is long enough for a multi-app Tomcat to stop cleanly")
    void productionGraceIsSeconds() {
        assertTrue(OrphanTomcatReclaimer.GRACE_PERIOD_MS >= 5_000,
                "a Tomcat hosting several apps and an embedded database needs seconds, not 1.5 s");
    }
}
