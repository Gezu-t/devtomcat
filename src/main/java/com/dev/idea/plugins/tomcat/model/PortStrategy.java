package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Per-config port-conflict policy. See LOCAL_NOTES.md (1.1.0) for design rationale.
public enum PortStrategy {
    // Kill own orphans; if port still busy, fail with a clear error.
    // Default for new configs in 1.1.0+.
    RECLAIM_THEN_FAIL,
    // Existing behavior — find next available port.
    // Default for migrated pre-1.1.0 configs (no behavior change on upgrade).
    AUTO_BUMP,
    // Use preferred port or refuse to launch. No reclaim, no bump.
    STRICT;

    @NotNull
    public static PortStrategy fromSerialized(@Nullable String name) {
        if (name == null || name.isBlank()) return AUTO_BUMP;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return AUTO_BUMP; }
    }
}
