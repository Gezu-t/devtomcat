package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import com.intellij.configurationStore.XmlSerializer;
import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jdom.Element;

import java.util.ArrayList;
import java.util.List;

public class TomcatConfigurationClonerPlatformTest extends BasePlatformTestCase {

    public void testClonePreservesDisabledStateOnDefaultEnabledLog() {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        LogFileOptions catalinaLog = findLogFile(original, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        assertTrue("catalina log is enabled by default", catalinaLog.isEnabled());
        catalinaLog.setEnabled(false);

        TomcatRunConfiguration clone = TomcatConfigurationCloner.clone(original);

        LogFileOptions clonedCatalinaLog = findLogFile(clone, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        assertFalse("disabled state must survive cloning", clonedCatalinaLog.isEnabled());
    }

    public void testWriteReadRoundTripPreservesDisabledLogState() throws Exception {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        LogFileOptions catalinaLog = findLogFile(original, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        assertTrue("catalina log is enabled by default", catalinaLog.isEnabled());
        catalinaLog.setEnabled(false);

        Element element = new Element("configuration");
        original.writeExternal(element);

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );
        restored.readExternal(element);

        LogFileOptions restoredCatalinaLog = findLogFile(restored, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        assertFalse("disabled state must survive write/read round-trip",
                restoredCatalinaLog.isEnabled());
    }

    /**
     * Reproduces the user-reported "checkbox selection not saving" scenario when
     * the user removes a Tomcat log entry in the Logs tab. Without the fix,
     * syncTomcatLogFiles() restores deleted defaults on the next read, so the
     * user's deletion is silently reverted.
     */
    public void testRemovedLogEntryStaysRemovedAfterRoundTrip() throws Exception {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        // Mimic LogConfigurationPanel.applyEditorTo with the user having removed
        // every Tomcat default except "Catalina Log".
        List<LogFileOptions> keep = new ArrayList<>();
        for (LogFileOptions opt : original.getAllLogFiles()) {
            if (TomcatLogFile.TOMCAT_CATALINA_LOG_ID.equals(opt.getName())) {
                keep.add(new LogFileOptions(opt.getName(), opt.getPathPattern(),
                        opt.isEnabled(), opt.isSkipContent(), opt.isShowAll()));
            }
        }
        original.removeAllLogFiles();
        for (LogFileOptions opt : keep) {
            original.addLogFile(opt.getPathPattern(), opt.getName(),
                    opt.isEnabled(), opt.isSkipContent(), opt.isShowAll());
        }

        Element element = new Element("configuration");
        original.writeExternal(element);

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );
        restored.readExternal(element);

        assertEquals("user's log deletions must survive round-trip",
                1, restored.getLogFiles().size());
        assertEquals("Tomcat Catalina Log", restored.getLogFiles().get(0).getName());
    }

    /**
     * User removes every log entry in the Logs tab. The deletion must persist
     * across write/read — an absence of {@code <log_file>} children alone is
     * ambiguous (matches legacy XML), so the {@code logsSeeded} marker decides
     * between "fresh config — seed defaults" and "user emptied the list".
     */
    public void testDeleteAllLogsSurvivesRoundTrip() throws Exception {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        // Mimic the user removing every row in the Logs tab.
        original.removeAllLogFiles();

        Element element = new Element("configuration");
        original.writeExternal(element);

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );
        restored.readExternal(element);

        assertEquals("an explicit delete-all must not be resurrected on reload",
                0, restored.getLogFiles().size());
    }

    /**
     * User edits the path of a standard Tomcat log entry (e.g. points it at a
     * custom directory). The daily path-refresh in {@code getAllLogFiles()}
     * must recognise that the path no longer conforms to the plugin-managed
     * shape and leave it untouched.
     */
    public void testCustomPathForStandardLogIsPreserved() {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration config = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        String customPath = myFixture.getTempDirFixture().getTempDirPath()
                + "/my-tomcat-logs/my-catalina.log";
        LogFileOptions catalinaLog = findLogFile(config, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        catalinaLog.setPathPattern(customPath);

        // Trigger the daily path-refresh code path. Without the guard it would
        // overwrite customPath with today's catalina.YYYY-MM-DD.log.
        LogFileOptions afterRefresh = findLogFile(config, TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        assertEquals("custom path on a standard log id must not be overwritten",
                customPath, afterRefresh.getPathPattern());
    }

    /**
     * Legacy configs written before the Tomcat logs feature (no &lt;log_file&gt;
     * children in XML) must still receive the default Tomcat log entries. The
     * backward-compat seed path kicks in only when myLogFiles is empty.
     */
    public void testLegacyConfigWithoutLogEntriesStillGetsDefaults() throws Exception {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();

        Element legacy = new Element("configuration");
        legacy.setAttribute("httpPort", "8080");

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );
        restored.readExternal(legacy);

        assertEquals("legacy config should be seeded with the Tomcat defaults",
                6, restored.getLogFiles().size());
    }

    public void testClonePreservesPlatformManagedLogState() {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        LogFileOptions catalinaOut = findLogFile(original, TomcatLogFile.TOMCAT_CATALINA_OUT_ID);
        catalinaOut.setEnabled(true);
        catalinaOut.setSkipContent(true);
        catalinaOut.setShowAll(false);

        String customLogPath = myFixture.getTempDirFixture().getTempDirPath() + "/custom.log";
        original.addLogFile(customLogPath, "Custom Log", true, true, false);
        original.setShowConsoleOnStdOut(false);
        original.setShowConsoleOnStdErr(false);
        original.setSaveOutputToFile(true);
        original.setFileOutputPath(myFixture.getTempDirFixture().getTempDirPath() + "/console.log");

        TomcatRunConfiguration clone = TomcatConfigurationCloner.clone(original);

        LogFileOptions clonedCatalinaOut = findLogFile(clone, TomcatLogFile.TOMCAT_CATALINA_OUT_ID);
        assertTrue(clonedCatalinaOut.isEnabled());
        assertTrue(clonedCatalinaOut.isSkipContent());
        assertFalse(clonedCatalinaOut.isShowAll());

        LogFileOptions customLog = findLogFile(clone, "Custom Log");
        assertEquals(customLogPath, customLog.getPathPattern());
        assertTrue(customLog.isEnabled());
        assertTrue(customLog.isSkipContent());
        assertFalse(customLog.isShowAll());

        assertFalse(clone.isShowConsoleOnStdOut());
        assertFalse(clone.isShowConsoleOnStdErr());
        assertTrue(clone.isSaveOutputToFile());
        assertEquals(myFixture.getTempDirFixture().getTempDirPath() + "/console.log", clone.getOutputFilePath());
    }

    public void testBuildArtifactsTaskPersistentStateRoundTripPreservesEnabledAndArtifacts() {
        TomcatBuildArtifactsTask original = new TomcatBuildArtifactsTask(TomcatBuildArtifactsTaskProvider.ID);
        original.setEnabled(false);
        original.setArtifactNames(List.of("app.war", "admin.war"));

        Element element = new Element("option");
        XmlSerializer.serializeStateInto(original, element);

        TomcatBuildArtifactsTask restored = new TomcatBuildArtifactsTask(TomcatBuildArtifactsTaskProvider.ID);
        XmlSerializer.deserializeAndLoadState(restored, element);

        assertFalse("disabled state must survive serializer round-trip", restored.isEnabled());
        assertEquals(List.of("app.war", "admin.war"), restored.getArtifactNames());
    }

    public void testWriteReadRoundTripPreservesPlatformCoverageState() throws Exception {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );

        CoverageEnabledConfiguration originalCoverage = CoverageEnabledConfiguration.getOrCreate(original);
        Element coverageState = new Element("coverage");
        coverageState.setAttribute("enabled", "true");
        coverageState.setAttribute("track_test_folders", "true");
        originalCoverage.readExternal(coverageState);

        Element element = new Element("configuration");
        original.writeExternal(element);

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                "Tomcat"
        );
        restored.readExternal(element);

        CoverageEnabledConfiguration restoredCoverage = CoverageEnabledConfiguration.getOrCreate(restored);
        Element restoredCoverageState = new Element("coverage");
        restoredCoverage.writeExternal(restoredCoverageState);

        assertEquals("true", restoredCoverageState.getAttributeValue("enabled"));
        assertEquals("true", restoredCoverageState.getAttributeValue("track_test_folders"));
    }

    public void testSetAllowMultipleInstancesSyncsPlatformFlag() {
        // Pins the contract that toggling the user-facing parallel-run flag
        // re-syncs the platform's isAllowRunningInParallel() the same EDT tick.
        // Without this contract, the toolbar Run-vs-Rerun presentation would
        // lag behind the user's checkbox edit until the next config reload.
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration cfg = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], "Sync");

        assertFalse("default is single-instance", cfg.isAllowRunningInParallel());

        cfg.setAllowMultipleInstances(true);
        assertTrue("toggling on must lift the platform flag", cfg.isAllowRunningInParallel());

        cfg.setAllowMultipleInstances(false);
        assertFalse("toggling off must drop the platform flag", cfg.isAllowRunningInParallel());
    }

    public void testWriteReadRoundTripPreservesPlatformFlag() throws Exception {
        // The serializer round-trip must keep allowRunningInParallel in sync
        // with the persisted allowMultipleInstances bit. readExternal already
        // calls syncPlatformFlags after deserialisation; this test guards
        // against a future refactor breaking that link silently.
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration original = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], "Roundtrip");
        original.setAllowMultipleInstances(true);
        assertTrue(original.isAllowRunningInParallel());

        Element element = new Element("configuration");
        original.writeExternal(element);

        TomcatRunConfiguration restored = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], "Roundtrip");
        restored.readExternal(element);

        assertTrue("restored config must keep allowMultipleInstances",
                restored.isAllowMultipleInstances());
        assertTrue("restored config must keep allowRunningInParallel (platform flag)",
                restored.isAllowRunningInParallel());
    }

    public void testClonePreservesAllowRunningInParallelFlag() {
        // Regression guard for the cloner's platform-flag sync.
        // syncPlatformFlags fires from the TomcatRunConfiguration constructor
        // with an empty TomcatConfigurationData, so the freshly-constructed
        // clone always starts at allowRunningInParallel=false (the platform
        // default). The cloner then copies the source's UiConfig (which carries
        // the allowMultipleInstances bit), but UiConfig replacement alone does
        // NOT re-trigger the platform-flag sync. Without an explicit final
        // syncPlatformFlags call at the end of the clone, a parallel-run
        // config's clone would silently revert to single-instance from the
        // platform's perspective even though the UI checkbox still reads true.
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration parallelOriginal = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], "Parallel");
        parallelOriginal.setAllowMultipleInstances(true);
        assertTrue("guard: original must report parallel",
                parallelOriginal.isAllowRunningInParallel());

        TomcatRunConfiguration parallelClone = TomcatConfigurationCloner.clone(parallelOriginal);
        assertTrue("clone of a parallel-run config must keep allowMultipleInstances",
                parallelClone.isAllowMultipleInstances());
        assertTrue("clone of a parallel-run config must keep allowRunningInParallel " +
                        "(platform flag) — without this the toolbar would silently flip " +
                        "the config back to single-instance after the first edit-and-Apply",
                parallelClone.isAllowRunningInParallel());

        TomcatRunConfiguration singleOriginal = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], "Single");
        // setAllowMultipleInstances(false) is the default but make it explicit
        singleOriginal.setAllowMultipleInstances(false);
        assertFalse(singleOriginal.isAllowRunningInParallel());

        TomcatRunConfiguration singleClone = TomcatConfigurationCloner.clone(singleOriginal);
        assertFalse("clone of a single-instance config must stay single-instance " +
                        "in the platform flag — needed so the toolbar Run/Rerun icon swap works",
                singleClone.isAllowRunningInParallel());
    }

    private static LogFileOptions findLogFile(TomcatRunConfiguration configuration, String name) {
        for (LogFileOptions logFile : configuration.getAllLogFiles()) {
            if (name.equals(logFile.getName())) {
                return logFile;
            }
        }
        fail("Log file not found: " + name);
        return null;
    }
}
