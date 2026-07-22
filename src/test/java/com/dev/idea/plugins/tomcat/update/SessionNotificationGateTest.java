package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the once-per-session semantics of {@link SessionNotificationGate}:
 * a balloon fires on first contact and whenever its candidate set CHANGES,
 * but an identical repeat within the session stays silent — repeated
 * identical prompts train users to dismiss them unread.
 */
@DisplayName("SessionNotificationGate")
class SessionNotificationGateTest {

    @Test
    @DisplayName("first call for a scope notifies")
    void firstCallNotifies() {
        SessionNotificationGate gate = new SessionNotificationGate();
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module")));
    }

    @Test
    @DisplayName("identical candidate set stays silent for the rest of the session")
    void identicalSetSilent() {
        SessionNotificationGate gate = new SessionNotificationGate();
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module", "app-1.0.0")));
        assertFalse(gate.shouldNotify("cfg-1", Set.of("web-module", "app-1.0.0")));
        assertFalse(gate.shouldNotify("cfg-1", Set.of("app-1.0.0", "web-module")),
                "set equality, not iteration order, decides");
    }

    @Test
    @DisplayName("a changed candidate set re-notifies")
    void changedSetReNotifies() {
        SessionNotificationGate gate = new SessionNotificationGate();
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module")));
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module", "app-1.0.0")),
                "a new candidate is new information");
        assertFalse(gate.shouldNotify("cfg-1", Set.of("web-module", "app-1.0.0")));
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module")),
                "shrinking back is also a change worth surfacing");
    }

    @Test
    @DisplayName("scopes are independent — one config's balloon does not silence another's")
    void scopesIndependent() {
        SessionNotificationGate gate = new SessionNotificationGate();
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module")));
        assertTrue(gate.shouldNotify("cfg-2", Set.of("web-module")));
        assertTrue(gate.shouldNotify("other-kind|cfg-1", Set.of("web-module")),
                "different balloon kinds gate separately even for the same config");
    }

    @Test
    @DisplayName("an empty candidate set participates like any other value")
    void emptySetParticipates() {
        SessionNotificationGate gate = new SessionNotificationGate();
        assertTrue(gate.shouldNotify("cfg-1", Set.of()));
        assertFalse(gate.shouldNotify("cfg-1", Set.of()));
        assertTrue(gate.shouldNotify("cfg-1", Set.of("web-module")));
    }
}
