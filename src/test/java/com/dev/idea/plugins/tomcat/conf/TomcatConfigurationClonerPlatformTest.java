package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import com.intellij.execution.configurations.LogFileOptions;
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
