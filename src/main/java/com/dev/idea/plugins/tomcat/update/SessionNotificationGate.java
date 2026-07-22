package com.dev.idea.plugins.tomcat.update;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Once-per-IDE-session gate for balloons that fire from every update action.
 *
 * <p>The reclaim / no-hot-reload balloons in {@link TomcatApplicationUpdater}
 * describe a CONFIGURATION state, not an event — re-popping the identical
 * balloon on every Ctrl+F10 trains the user to dismiss it unread. The gate
 * remembers, per scope (run configuration + balloon kind), the candidate set
 * last notified this IDE session: an identical set stays silent, a CHANGED set
 * re-notifies (new candidates are new information). Console logging is not
 * gated — the run console stays the authoritative, every-action surface.
 *
 * <p>In-memory only by design: an IDE restart re-notifies once, which is the
 * intended reminder cadence. Thread-safe (updates arrive from compiler
 * callback threads and the EDT).
 */
final class SessionNotificationGate {

    /** Process-wide gate used by production callers. */
    static final SessionNotificationGate INSTANCE = new SessionNotificationGate();

    private final Map<String, Set<String>> lastNotified = new ConcurrentHashMap<>();

    // Package-visible so tests can use fresh, isolated instances.
    SessionNotificationGate() {}

    /**
     * Returns {@code true} when {@code candidateKey} differs from what was last
     * notified for {@code scope} this session (including the first call), and
     * records it as notified. A later call with a different set re-arms the
     * gate — a changed candidate set may re-notify.
     */
    boolean shouldNotify(@NotNull String scope, @NotNull Set<String> candidateKey) {
        Set<String> previous = lastNotified.put(scope, Set.copyOf(candidateKey));
        return !candidateKey.equals(previous);
    }
}
