package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PortStrategy")
class PortStrategyTest {

    @Test
    @DisplayName("fromSerialized returns AUTO_BUMP for null/blank/unknown")
    void defaultsToAutoBump() {
        assertEquals(PortStrategy.AUTO_BUMP, PortStrategy.fromSerialized(null));
        assertEquals(PortStrategy.AUTO_BUMP, PortStrategy.fromSerialized(""));
        assertEquals(PortStrategy.AUTO_BUMP, PortStrategy.fromSerialized("   "));
        assertEquals(PortStrategy.AUTO_BUMP, PortStrategy.fromSerialized("NONEXISTENT_VALUE"));
    }

    @Test
    @DisplayName("fromSerialized round-trips known names")
    void knownNames() {
        for (PortStrategy s : PortStrategy.values()) {
            assertEquals(s, PortStrategy.fromSerialized(s.name()));
        }
    }

    @Test
    @DisplayName("PortConfig defaults strategy to AUTO_BUMP (preserves pre-1.1.0 behavior)")
    void portConfigDefaults() {
        assertEquals(PortStrategy.AUTO_BUMP, new PortConfig().getStrategy());
    }

    @Test
    @DisplayName("setStrategy round-trips through PortConfig")
    void portConfigRoundTrip() {
        PortConfig pc = new PortConfig();
        pc.setStrategy(PortStrategy.STRICT);
        assertEquals(PortStrategy.STRICT, pc.getStrategy());
        PortConfig clone = new PortConfig(pc);
        assertEquals(PortStrategy.STRICT, clone.getStrategy());
    }
}
