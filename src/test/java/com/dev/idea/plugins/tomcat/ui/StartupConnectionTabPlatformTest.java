package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;

/**
 * Round-trip coverage of the Startup/Connection tab's config adapter
 * ({@code resetFrom} / {@code applyTo}) — the part that is OUR logic, not Swing.
 *
 * <p>Two behaviours that are easy to break silently and live nowhere else:
 * <ul>
 *   <li>the global debug port is written to BOTH {@code DebugConfig} and every
 *       per-mode {@code RunnerSettings}, so a clone/serialize pass can't
 *       resurrect a stale per-mode value;</li>
 *   <li>per-mode startup scripts map default↔custom: a mode on the default
 *       persists an empty value, a custom script persists its path.</li>
 * </ul>
 *
 * <p>The port-range guard in the tab is not separately exercised here: the
 * model ({@code DebugConfig}) clamps to the same {@code [1024,65535]} range
 * before the value ever reaches the field, so the guard is defense-in-depth
 * against raw field typing, not reachable through the round-trip API. Env-var
 * state is owned by {@code EnvVarPanel} and covered separately.
 */
public class StartupConnectionTabPlatformTest extends BasePlatformTestCase {

    private File tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = FileUtil.createTempDirectory("devtomcat-startup", null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (tempDir != null) {
                FileUtil.delete(tempDir);
            }
        } finally {
            super.tearDown();
        }
    }

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], name);
    }

    public void testDebugPortRoundTripsToDebugConfigAndEveryMode() throws Exception {
        TomcatRunConfiguration source = createConfig("StartupPortSource");
        source.getConfigData().getDebugConfig().setPort(6006);

        StartupConnectionTab tab = new StartupConnectionTab(getProject(), source);
        tab.resetFrom(source);

        TomcatRunConfiguration target = createConfig("StartupPortTarget");
        tab.applyTo(target);

        assertEquals("the global JDWP port must persist to DebugConfig",
                6006, target.getConfigData().getDebugConfig().getPort());
        for (String mode : new String[]{"Run", "Debug", "Coverage", "Profile"}) {
            assertEquals("mode '" + mode + "' must carry the resolved debug port",
                    6006, target.getConfigData().getRunnerSettings(mode).getDebugPort());
        }
    }

    public void testStartupScriptRoundTripsCustomAndDefaultPerMode() throws Exception {
        File script = new File(tempDir, "catalina-run.sh");
        Files.writeString(script.toPath(), "#!/bin/sh\n");

        TomcatRunConfiguration source = createConfig("StartupScriptSource");
        source.getConfigData().getDebugConfig().setPort(DebugConfig.DEFAULT_DEBUG_PORT);
        // Run mode gets a custom (existing) startup script; the other modes are
        // left empty == "use the default catalina script".
        source.getConfigData().getRunnerSettings("Run")
                .setStartupScript(script.getAbsolutePath());

        StartupConnectionTab tab = new StartupConnectionTab(getProject(), source);
        tab.resetFrom(source);

        TomcatRunConfiguration target = createConfig("StartupScriptTarget");
        tab.applyTo(target);

        assertEquals("a custom startup script must round-trip to the same mode",
                script.getAbsolutePath(),
                target.getConfigData().getRunnerSettings("Run").getStartupScript());
        assertTrue("a mode left on the default must persist an empty script, not a path",
                target.getConfigData().getRunnerSettings("Debug").getStartupScript().isEmpty());
    }
}
