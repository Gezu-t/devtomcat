package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentArtifact")
class DeploymentArtifactTest {

    @Test
    @DisplayName("default constructor creates empty artifact")
    void defaultConstructor() {
        DeploymentArtifact art = new DeploymentArtifact();
        assertEquals("", art.getName());
        assertEquals("", art.getPath());
        assertEquals("war", art.getType());
        assertEquals("/", art.getContextPath());
    }

    @Test
    @DisplayName("three-arg constructor sets fields")
    void threeArgConstructor() {
        DeploymentArtifact art = new DeploymentArtifact("myapp", "/path/to/myapp.war", "war");
        assertEquals("myapp", art.getName());
        assertEquals("/path/to/myapp.war", art.getPath());
        assertEquals("war", art.getType());
    }

    @Test
    @DisplayName("setName normalizes null to empty")
    void setNameNull() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setName(null);
        assertEquals("", art.getName());
    }

    @Test
    @DisplayName("setType defaults to war on null")
    void setTypeNull() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setType(null);
        assertEquals("war", art.getType());
    }

    @Test
    @DisplayName("setContextPath normalizes null to /")
    void setContextPathNormalizesNull() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setContextPath(null);
        assertEquals("/", art.getContextPath());
    }

    @Test
    @DisplayName("setContextPath normalizes empty string to / (regression: StringUtil.notNullize omission)")
    void setContextPathNormalizesEmptyString() {
        // StringUtil.notNullize("", "/") returns "" not "/" — the fix must not use notNullize.
        DeploymentArtifact art = new DeploymentArtifact();
        art.setContextPath("");
        assertEquals("/", art.getContextPath(),
                "Empty string context path must normalize to root '/', not stay empty");
    }

    @Test
    @DisplayName("setContextPath normalizes whitespace-only to /")
    void setContextPathNormalizesWhitespace() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setContextPath("   ");
        assertEquals("/", art.getContextPath());
    }

    @Test
    @DisplayName("setContextPath preserves non-empty paths")
    void setContextPathPreservesNonEmpty() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setContextPath("/myapp");
        assertEquals("/myapp", art.getContextPath());
        art.setContextPath("portal");   // no leading slash — stored as-is
        assertEquals("portal", art.getContextPath());
    }

    @Test
    @DisplayName("contextPath aliases work correctly")
    void contextPathAliases() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setApplicationContext("/app");
        assertEquals("/app", art.getApplicationContext());
        assertEquals("/app", art.getContextPath());
        assertEquals("/app", art.getServerPath());

        art.setServerPath("/other");
        assertEquals("/other", art.getContextPath());
    }

    @Test
    @DisplayName("isUsingDefaultContext checks / and empty")
    void isUsingDefaultContext() {
        DeploymentArtifact art = new DeploymentArtifact();
        assertTrue(art.isUsingDefaultContext());

        art.setContextPath("/myapp");
        assertFalse(art.isUsingDefaultContext());
    }

    @Test
    @DisplayName("getDisplayName falls back to path filename")
    void displayNameFallback() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setPath("/path/to/myapp.war");
        assertEquals("myapp.war", art.getDisplayName());

        art.setName("My Application");
        assertEquals("My Application", art.getDisplayName());
    }

    @Test
    @DisplayName("clone produces equal independent copy")
    void cloneIsIndependent() {
        DeploymentArtifact original = new DeploymentArtifact("app", "/path", "war");
        original.setContextPath("/app");

        DeploymentArtifact cloned = original.clone();
        assertEquals(original, cloned);

        cloned.setName("other");
        assertNotEquals(original, cloned);
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void equalsAndHashCode() {
        DeploymentArtifact a = new DeploymentArtifact("app", "/path", "war");
        a.setContextPath("/app");
        DeploymentArtifact b = new DeploymentArtifact("app", "/path", "war");
        b.setContextPath("/app");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setName("other");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("type constants are defined")
    void typeConstants() {
        assertEquals("war", DeploymentArtifact.TYPE_WAR);
        assertEquals("exploded", DeploymentArtifact.TYPE_EXPLODED);
        assertEquals("external", DeploymentArtifact.TYPE_EXTERNAL);
    }

    @Test
    @DisplayName("default source is INTELLIJ_ARTIFACT")
    void defaultSourceIsIntelliJArtifact() {
        DeploymentArtifact a = new DeploymentArtifact();
        assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT, a.getSource());
    }

    @Test
    @DisplayName("setType('external') route-maps to source=EXTERNAL + type=WAR for legacy configs")
    void legacyExternalTypeMapsToSource() {
        // Pre-source-field configs wrote type="external" as the only marker for
        // user-picked files. On the way in, setType() must split that overloaded
        // value into its two real facets so the rest of the code (validator,
        // refresher) sees a proper Source.EXTERNAL.
        DeploymentArtifact a = new DeploymentArtifact();
        a.setType(DeploymentArtifact.TYPE_EXTERNAL);
        assertEquals(DeploymentArtifact.TYPE_WAR, a.getType(),
                "legacy 'external' type must collapse to packaging=WAR");
        assertEquals(DeploymentArtifact.Source.EXTERNAL, a.getSource(),
                "legacy 'external' type must also set source=EXTERNAL");
    }

    @Test
    @DisplayName("clone carries source across")
    void cloneCarriesSource() {
        DeploymentArtifact a = new DeploymentArtifact("ext.war", "/tmp/ext.war", "war");
        a.setSource(DeploymentArtifact.Source.EXTERNAL);

        DeploymentArtifact copy = a.clone();
        assertEquals(DeploymentArtifact.Source.EXTERNAL, copy.getSource());
    }

    @Test
    @DisplayName("equals distinguishes artifacts by source")
    void equalsDistinguishesSource() {
        DeploymentArtifact a = new DeploymentArtifact("app", "/p", "war");
        DeploymentArtifact b = new DeploymentArtifact("app", "/p", "war");
        assertEquals(a, b);
        b.setSource(DeploymentArtifact.Source.EXTERNAL);
        assertNotEquals(a, b,
                "same name/path/type with different source must not be equal — "
                + "they behave differently downstream (validator, refresher)");
    }

    @Test
    @DisplayName("Source.fromSerialized defaults to INTELLIJ_ARTIFACT for absent or unknown values")
    void fromSerializedDefaults() {
        assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT,
                DeploymentArtifact.Source.fromSerialized(null));
        assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT,
                DeploymentArtifact.Source.fromSerialized("bogus"));
        assertEquals(DeploymentArtifact.Source.EXTERNAL,
                DeploymentArtifact.Source.fromSerialized("EXTERNAL"));
        assertEquals(DeploymentArtifact.Source.AUTO_DETECTED,
                DeploymentArtifact.Source.fromSerialized("AUTO_DETECTED"));
    }
}
