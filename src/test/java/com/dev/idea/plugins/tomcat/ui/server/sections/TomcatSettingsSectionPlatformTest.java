package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform-fixture coverage of {@link TomcatSettingsSection} — specifically
 * the round-trip contract for the optional connectors (HTTPS, JMX, AJP).
 *
 * <h2>Why this exists</h2>
 * The optional connectors all share one rule:
 * <ul>
 *   <li>An empty port field ⇒ connector disabled.</li>
 *   <li>A numeric port field ⇒ connector enabled with that port.</li>
 * </ul>
 * The {@code applyTo} path computes {@code enabled} from
 * {@code parsedPort != null} and writes it to the model. So the
 * {@code resetFrom} path must produce an <i>empty</i> field for disabled
 * connectors — pre-filling with a default would silently re-enable the
 * connector on the next save without any user action.
 *
 * <p>HTTPS and AJP have always had this right. JMX previously pre-filled
 * with {@code DynamicTomcatEnvironment.getJmxPort()} which silently flipped
 * {@code jmxEnabled} from {@code false} to {@code true} on every editor
 * round-trip. The fix in this commit closes that asymmetry; the test below
 * pins it for all three connectors so the same drift cannot recur.
 */
public class TomcatSettingsSectionPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    /**
     * Build a config in which every optional connector is explicitly disabled.
     * Mirrors the state the factory produces for a fresh config when the
     * registry default for each connector is "off" — the most common shape
     * a user actually has on disk.
     */
    private TomcatRunConfiguration disabledConnectorsConfig(String name) {
        TomcatRunConfiguration cfg = createConfig(name);
        PortConfig pc = cfg.getConfigData().getPortConfig();
        pc.setHttp(8080);
        pc.setShutdown(8005);
        pc.setHttpsEnabled(false);
        pc.setJmxEnabled(false);
        pc.setAjpEnabled(false);
        return cfg;
    }

    public void testJmxDisabledRoundTripStaysDisabled() throws ConfigurationException {
        // The bug fix this test pins. Pre-fix, resetFrom would populate the
        // JMX field with "1099" (the registry default) even when JMX was
        // disabled in the config. applyTo would then read "1099", parse it
        // to a non-null Integer, and set jmxEnabled=true on the way back —
        // silently flipping JMX on with every save.
        TomcatRunConfiguration cfg = disabledConnectorsConfig("JmxDisabledRT");
        TomcatSettingsSection section = new TomcatSettingsSection(getProject());
        section.createPanel();

        section.resetFrom(cfg);
        section.applyTo(cfg);

        assertFalse("JMX must remain disabled after a no-op editor round-trip",
                cfg.getConfigData().getPortConfig().isJmxEnabled());
        assertNull("getJmxPort() must be null when the connector is disabled",
                cfg.getJmxPort());
    }

    public void testHttpsDisabledRoundTripStaysDisabled() throws ConfigurationException {
        // Sibling pin for HTTPS — the connector that has always behaved
        // correctly. Pinned so a future "consistency" refactor that
        // accidentally reintroduces the JMX-style fallback gets caught
        // by both tests, not just one.
        TomcatRunConfiguration cfg = disabledConnectorsConfig("HttpsDisabledRT");
        TomcatSettingsSection section = new TomcatSettingsSection(getProject());
        section.createPanel();

        section.resetFrom(cfg);
        section.applyTo(cfg);

        assertFalse("HTTPS must remain disabled after a no-op editor round-trip",
                cfg.getConfigData().getPortConfig().isHttpsEnabled());
        assertNull(cfg.getHttpsPort());
    }

    public void testAjpDisabledRoundTripStaysDisabled() throws ConfigurationException {
        // Same pin for AJP. The three optional connectors must all share one
        // contract: disabled in storage ⇒ empty field ⇒ disabled after save.
        TomcatRunConfiguration cfg = disabledConnectorsConfig("AjpDisabledRT");
        TomcatSettingsSection section = new TomcatSettingsSection(getProject());
        section.createPanel();

        section.resetFrom(cfg);
        section.applyTo(cfg);

        assertFalse("AJP must remain disabled after a no-op editor round-trip",
                cfg.getConfigData().getPortConfig().isAjpEnabled());
    }

    public void testJmxEnabledRoundTripPreservesPort() throws ConfigurationException {
        // Positive case: a config with JMX enabled at a custom port survives
        // an editor round-trip with both the enabled flag AND the port intact.
        // Without this pin, a future change that "fixes" the JMX field by
        // always emptying it would silently disable JMX on every save.
        TomcatRunConfiguration cfg = createConfig("JmxEnabledRT");
        PortConfig pc = cfg.getConfigData().getPortConfig();
        pc.setHttp(8080);
        pc.setShutdown(8005);
        pc.setJmxEnabled(true);
        pc.setJmx(9999);

        TomcatSettingsSection section = new TomcatSettingsSection(getProject());
        section.createPanel();

        section.resetFrom(cfg);
        section.applyTo(cfg);

        assertTrue("JMX must stay enabled when configured so",
                cfg.getConfigData().getPortConfig().isJmxEnabled());
        assertEquals("JMX port must round-trip exactly",
                Integer.valueOf(9999), cfg.getJmxPort());
    }
}
