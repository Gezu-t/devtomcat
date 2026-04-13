package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationCleanupListener")
class TomcatConfigurationCleanupListenerTest {

    @Test
    @DisplayName("identity key matches the same object instance")
    void identityKeyMatchesSameInstance() {
        EqualValue config = new EqualValue(1);

        assertEquals(TomcatConfigurationCleanupListener.identityKey(config),
                TomcatConfigurationCleanupListener.identityKey(config));
    }

    @Test
    @DisplayName("identity key does not collapse different equal instances")
    void identityKeySeparatesEqualInstances() {
        EqualValue first = new EqualValue(1);
        EqualValue second = new EqualValue(1);

        assertNotEquals(TomcatConfigurationCleanupListener.identityKey(first),
                TomcatConfigurationCleanupListener.identityKey(second));
    }

    @Test
    @DisplayName("identity-keyed map returns previous name for the same configuration instance")
    void identityKeyedMapSupportsRenameTracking() {
        ConcurrentHashMap<TomcatConfigurationCleanupListener.IdentityKey<EqualValue>, String> names =
                new ConcurrentHashMap<>();
        EqualValue config = new EqualValue(7);

        assertNull(names.put(TomcatConfigurationCleanupListener.identityKey(config), "OldName"));
        assertEquals("OldName", names.put(TomcatConfigurationCleanupListener.identityKey(config), "NewName"));
        assertEquals("NewName", names.get(TomcatConfigurationCleanupListener.identityKey(config)));
    }

    private static final class EqualValue {
        private final int value;

        private EqualValue(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EqualValue other && other.value == value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }
}
