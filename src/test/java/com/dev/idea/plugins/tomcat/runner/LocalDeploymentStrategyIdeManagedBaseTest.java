package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Path;

/**
 * Platform-fixture coverage of
 * {@link LocalDeploymentStrategy#isIdeManagedCatalinaBase} — the gate that
 * decides whether the pre-launch stale-deployment cleanup is allowed to run.
 *
 * <p>The gate must agree with
 * {@link com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils#getCatalinaBase},
 * which only honours a pinned {@code CATALINA_BASE} when it points at an
 * existing directory and otherwise silently falls back to the IDE system base.
 * A pin that does not resolve (typo, deleted dir, path from another machine)
 * therefore leaves the launch running in the IDE-managed base, where cleanup
 * must still run.
 */
public class LocalDeploymentStrategyIdeManagedBaseTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    public void testBlankPinIsIdeManaged() {
        TomcatRunConfiguration cfg = createConfig("blank-pin");
        cfg.getConfigData().setCatalinaBase("");

        // catalinaBase argument is whatever getCatalinaBase resolved to (IDE base).
        assertTrue(LocalDeploymentStrategy.isIdeManagedCatalinaBase(
                Path.of(System.getProperty("java.io.tmpdir"), "devtomcat-system-base"), cfg));
    }

    public void testPinnedExistingDirIsUserManaged() throws Exception {
        File pinned = FileUtil.createTempDirectory("devtomcat-pinned-base", null);
        TomcatRunConfiguration cfg = createConfig("valid-pin");
        cfg.getConfigData().setCatalinaBase(pinned.getAbsolutePath());

        // The launch actually ran in the pinned dir, so it is user-managed.
        assertFalse(LocalDeploymentStrategy.isIdeManagedCatalinaBase(
                pinned.toPath(), cfg));
    }

    public void testPinnedNonexistentDirIsIdeManaged() {
        File pinned = new File(FileUtil.getTempDirectory(), "devtomcat-does-not-exist-" + System.nanoTime());
        TomcatRunConfiguration cfg = createConfig("stale-pin");
        cfg.getConfigData().setCatalinaBase(pinned.getAbsolutePath());

        // getCatalinaBase would reject the missing pin and fall back to the IDE
        // system base — so cleanup must run and the gate must report IDE-managed,
        // even though the pinned string is non-blank.
        Path resolvedIdeBase = Path.of(System.getProperty("java.io.tmpdir"), "devtomcat-system-base");
        assertTrue(LocalDeploymentStrategy.isIdeManagedCatalinaBase(resolvedIdeBase, cfg));
    }
}
