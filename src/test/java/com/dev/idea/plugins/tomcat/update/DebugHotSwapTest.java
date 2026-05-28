package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.update.DebugHotSwap.FollowUp;
import com.dev.idea.plugins.tomcat.update.DebugHotSwap.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link DebugHotSwap#decideFollowUp} — the side-effect-free decision that
 * maps a platform hot-swap {@link Outcome} (plus whether the deployed classpath
 * actually gained fresh files this round) onto what the caller must still do:
 * nothing, a full context restart, or a "not applied" warning.
 *
 * <p>The whole point of isolating this as a pure function is that it can be
 * exhaustively verified without a live JVM, a debug session, or the platform
 * hot-swap machinery. The matrix below is the full cross-product of the four
 * outcomes and the two classpath states (8 cases); each assertion documents
 * <em>why</em> that follow-up is the correct one, so the safety contract — a
 * change is never silently dropped — is locked down here rather than relying on
 * an integration test that needs a running server.
 */
class DebugHotSwapTest {

    @Nested
    @DisplayName("SUCCESS — changed classes were redefined live")
    class Success {

        @Test
        @DisplayName("classpath changed → NONE (live redefine already applied it)")
        void successWithClasspathChange() {
            assertEquals(FollowUp.NONE, DebugHotSwap.decideFollowUp(Outcome.SUCCESS, true));
        }

        @Test
        @DisplayName("classpath unchanged → NONE (still nothing left to do)")
        void successWithoutClasspathChange() {
            assertEquals(FollowUp.NONE, DebugHotSwap.decideFollowUp(Outcome.SUCCESS, false));
        }
    }

    @Nested
    @DisplayName("NOTHING_TO_RELOAD — the VM had nothing newer to redefine")
    class NothingToReload {

        @Test
        @DisplayName("classpath changed → RESTART (redefine scan missed the fresh files)")
        void nothingToReloadButFilesCopied() {
            // We copied fresh bytecode this round, yet the VM redefined nothing.
            // That means the redefine scan didn't see our files (e.g. the
            // session's output roots don't cover this module), so the only way
            // to apply the change is a fresh classloader — restart.
            assertEquals(FollowUp.RESTART, DebugHotSwap.decideFollowUp(Outcome.NOTHING_TO_RELOAD, true));
        }

        @Test
        @DisplayName("classpath unchanged → NONE (genuinely nothing to apply)")
        void nothingToReloadAndNothingCopied() {
            // Nothing redefined and nothing copied — the running JVM is already
            // current. Restarting here would throw away session state for no gain.
            assertEquals(FollowUp.NONE, DebugHotSwap.decideFollowUp(Outcome.NOTHING_TO_RELOAD, false));
        }
    }

    @Nested
    @DisplayName("FAILURE — a structural change or other redefine error")
    class Failure {

        @Test
        @DisplayName("classpath changed → RESTART (only a new classloader can load the new shape)")
        void failureWithClasspathChange() {
            assertEquals(FollowUp.RESTART, DebugHotSwap.decideFollowUp(Outcome.FAILURE, true));
        }

        @Test
        @DisplayName("classpath unchanged → RESTART (still must restart regardless)")
        void failureWithoutClasspathChange() {
            // FAILURE is independent of the classpath flag: the redefine itself
            // could not apply the change, so a restart is required either way.
            assertEquals(FollowUp.RESTART, DebugHotSwap.decideFollowUp(Outcome.FAILURE, false));
        }
    }

    @Nested
    @DisplayName("CANCELLED — the user declined the reload")
    class Cancelled {

        @Test
        @DisplayName("classpath changed → WARN_NOT_APPLIED (respect the decline, don't restart)")
        void cancelledWithClasspathChange() {
            assertEquals(FollowUp.WARN_NOT_APPLIED, DebugHotSwap.decideFollowUp(Outcome.CANCELLED, true));
        }

        @Test
        @DisplayName("classpath unchanged → WARN_NOT_APPLIED (never restart behind the user's back)")
        void cancelledWithoutClasspathChange() {
            // The user said no (e.g. declined the "VM may hang" prompt). Restarting
            // anyway would override that choice, so we only warn that the change
            // is not yet live.
            assertEquals(FollowUp.WARN_NOT_APPLIED, DebugHotSwap.decideFollowUp(Outcome.CANCELLED, false));
        }
    }
}
