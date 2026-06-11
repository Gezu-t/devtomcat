package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Round-trip coverage of the Code Coverage tab's config adapter —
 * {@code resetFrom} / {@code isModified} / {@code applyTo}. This is the
 * settings-panel logic that decides whether "Apply" lights up and whether
 * include/exclude patterns survive a save: OUR logic, not Swing's. The pattern
 * model ({@code CoverageConfig}) is covered separately; these tests pin that
 * the tab maps BOTH lists in BOTH directions — the classic place a settings
 * panel silently drops a field or compares only half its state.
 */
public class CodeCoverageTabPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], name);
    }

    private static CoverageConfig coverage(TomcatRunConfiguration cfg) {
        return cfg.getConfigData().getCoverageConfig();
    }

    public void testResetFromLoadsBothListsAndIsNotDirty() {
        TomcatRunConfiguration cfg = createConfig("CoverageReset");
        coverage(cfg).setIncludePatterns(List.of("org.example.*", "org.example.svc.*"));
        coverage(cfg).setExcludePatterns(List.of("org.example.generated.*"));

        CodeCoverageTab tab = new CodeCoverageTab(getProject());
        tab.resetFrom(cfg);

        assertEquals(List.of("org.example.*", "org.example.svc.*"), tab.getIncludePatterns());
        assertEquals(List.of("org.example.generated.*"), tab.getExcludePatterns());
        assertFalse("resetFrom must leave the editor matching the config (Apply disabled)",
                tab.isModified(cfg));
    }

    public void testIsModifiedDetectsAnExcludeOnlyDivergence() {
        // Guards the 'compared only the include list' regression: a change to
        // the exclude list alone must still register as modified.
        TomcatRunConfiguration cfg = createConfig("CoverageExcludeDiff");
        coverage(cfg).setIncludePatterns(List.of("org.example.*"));
        coverage(cfg).setExcludePatterns(List.of("org.example.gen.*"));

        CodeCoverageTab tab = new CodeCoverageTab(getProject());
        tab.resetFrom(cfg);
        assertFalse(tab.isModified(cfg));

        coverage(cfg).setExcludePatterns(List.of());
        assertTrue("a divergence in the exclude list alone must register as modified",
                tab.isModified(cfg));
    }

    public void testApplyToPersistsBothListsRoundTrip() throws Exception {
        TomcatRunConfiguration source = createConfig("CoverageSource");
        coverage(source).setIncludePatterns(List.of("org.example.*"));
        coverage(source).setExcludePatterns(List.of("org.example.test.*"));

        CodeCoverageTab tab = new CodeCoverageTab(getProject());
        tab.resetFrom(source);

        TomcatRunConfiguration target = createConfig("CoverageTarget");
        tab.applyTo(target);

        assertEquals("applyTo must persist the include list",
                List.of("org.example.*"), coverage(target).getIncludePatterns());
        assertEquals("applyTo must persist the exclude list",
                List.of("org.example.test.*"), coverage(target).getExcludePatterns());
        assertFalse("after applyTo the tab must compare clean against the target",
                tab.isModified(target));
    }
}
