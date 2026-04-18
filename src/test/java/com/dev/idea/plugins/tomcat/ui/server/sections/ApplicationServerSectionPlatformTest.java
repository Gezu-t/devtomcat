package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the resolver-based UI contract for the Application Server combo: every dialog
 * open goes through {@link TomcatServerManagerState#resolve} so the combo, validator,
 * and apply flow share a single interpretation of the persisted server reference.
 */
public class ApplicationServerSectionPlatformTest extends BasePlatformTestCase {

    private TomcatServerManagerState state;
    private List<TomcatInfo> savedServers;
    private final List<File> tempDirs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        state = TomcatServerManagerState.getInstance();
        savedServers = state.getTomcatInfos();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            state.setTomcatInfos(savedServers);
            for (File dir : tempDirs) {
                FileUtil.delete(dir);
            }
        } finally {
            super.tearDown();
        }
    }

    /**
     * Allocate a real directory on disk so {@link TomcatInfo#validate} (which checks
     * {@code new File(path).exists()}) succeeds for tests that want a passing
     * validator. Cleaned up in tearDown.
     */
    private String tempServerPath(String hint) throws IOException {
        File dir = FileUtil.createTempDirectory("devtomcat-" + hint + "-", null, true);
        tempDirs.add(dir);
        return dir.getAbsolutePath();
    }

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    private static TomcatInfo server(String id, String name, String path) {
        TomcatInfo info = new TomcatInfo(name, "10.1.28", path);
        info.setId(id);
        return info;
    }

    private ApplicationServerSection createdSection() {
        ApplicationServerSection section = new ApplicationServerSection(getProject());
        section.createPanel();
        section.loadConfiguration();
        return section;
    }

    public void testRegisteredServerIsSelected() throws IOException {
        String path = tempServerPath("registered");
        TomcatInfo registered = server("id-A", "Tomcat A", path);
        state.setTomcatInfos(List.of(registered));

        TomcatRunConfiguration cfg = createConfig("RegisteredSelected");
        cfg.setTomcatInfo(registered);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);

        TomcatInfo selected = section.getSelectedTomcatServer();
        assertSame("live instance must be selected when persisted ID matches",
                registered, selected);
        assertTrue("registered selection must validate",
                section.validateSettings().isEmpty());
    }

    public void testDriftedIdReconcilesToRegisteredByPath() throws IOException {
        // Persisted reference has a stale ID but a path that still matches.
        String path = tempServerPath("drift");
        TomcatInfo registered = server("canonical-id", "Tomcat", path);
        state.setTomcatInfos(List.of(registered));

        TomcatInfo driftedSnapshot = server("stale-id", "Tomcat", path);
        TomcatRunConfiguration cfg = createConfig("DriftedId");
        cfg.setTomcatInfo(driftedSnapshot);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);

        TomcatInfo selected = section.getSelectedTomcatServer();
        assertSame("resetFrom must upgrade to the canonical registered instance",
                registered, selected);
        // Applying now rewrites the config with the canonical ID — self-healing.
        try {
            section.applyTo(cfg);
        } catch (Exception e) {
            fail("applyTo must not throw for a reconciled registered selection: " + e.getMessage());
        }
        assertEquals("config must hold the canonical ID after apply",
                "canonical-id", cfg.getTomcatInfo().getId());
    }

    public void testBrokenReferenceIsSelectedAndBlocksRun() {
        // Persisted server is unresolved AND its path is missing from disk —
        // the runtime can't launch, so the UI matches with a hard error.
        state.setTomcatInfos(List.of(server("other-id", "Other Server", "/opt/other")));

        TomcatInfo brokenSnapshot = server("ghost-id", "Ghost Tomcat", "/missing/path");
        TomcatRunConfiguration cfg = createConfig("Broken");
        cfg.setTomcatInfo(brokenSnapshot);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);

        TomcatInfo selected = section.getSelectedTomcatServer();
        assertNotNull("broken snapshot must be injected and selected, not silently dropped",
                selected);
        assertEquals("ghost-id", selected.getId());
        assertFalse("broken selection must block Run",
                section.isConfigurationValid());

        List<ValidationInfo> errors = section.validateSettings();
        assertEquals("broken selection must block validation with exactly one error",
                1, errors.size());
        String message = errors.get(0).message;
        assertTrue("error must name the broken server and the path problem (was: " + message + ")",
                message.contains("Ghost Tomcat")
                        && message.toLowerCase().contains("cannot be launched"));
    }

    public void testUnregisteredButUsablePathBlocksRun() throws IOException {
        // Registration is required to launch. An embedded snapshot whose path
        // exists on disk is NOT enough — the user has to add the server to
        // Application Servers first. The toolbar, the dialog validator, and
        // the runtime all enforce this uniformly; no path lets a config slip
        // through to launch without a registered reference.
        String usablePath = tempServerPath("usable");
        state.setTomcatInfos(List.of(
                server("other-id", "Other Server", tempServerPath("other"))));

        TomcatInfo unregistered = server("foreign-id", "Imported Tomcat", usablePath);
        TomcatRunConfiguration cfg = createConfig("UsableUnregistered");
        cfg.setTomcatInfo(unregistered);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);

        TomcatInfo selected = section.getSelectedTomcatServer();
        assertNotNull("unregistered snapshot must be injected so the user sees it,"
                        + " not silently dropped", selected);
        assertEquals("foreign-id", selected.getId());
        assertFalse("unregistered-but-usable must block Run until the user registers the server",
                section.isConfigurationValid());
        List<ValidationInfo> errors = section.validateSettings();
        assertEquals("unregistered selection must surface exactly one validator error",
                1, errors.size());
        String message = errors.get(0).message;
        assertTrue("error must name the server and explain the fix (was: " + message + ")",
                message.contains("Imported Tomcat")
                        && message.toLowerCase().contains("not registered"));
    }

    public void testNullPersistedLeavesCombSelectionClearWithValidationError() {
        state.setTomcatInfos(List.of(server("id-A", "Tomcat A", "/opt/a")));

        TomcatRunConfiguration cfg = createConfig("NullPersisted");
        cfg.getConfigData().setTomcatInfo(null);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);

        List<ValidationInfo> errors = section.validateSettings();
        assertFalse("no selection must fail validation", errors.isEmpty());
        assertTrue("error must name the absent server",
                errors.get(0).message.toLowerCase().contains("no tomcat server"));
    }

    public void testReregisterCycleClearsUnresolvedMarkersAcrossReopens() throws IOException {
        // First open: the server is unregistered but its path is usable — the
        // section injects it AND blocks Run with a "not registered" error.
        // After re-registering and reopening, the resolver matches by path and
        // the marker is gone (otherwise the live entry would render as
        // unregistered forever).
        String canonicalPath = tempServerPath("canon");
        TomcatInfo unregisteredSnapshot = server("ghost-id", "Tomcat", canonicalPath);
        state.setTomcatInfos(List.of(
                server("other-id", "Other", tempServerPath("other"))));

        TomcatRunConfiguration cfg = createConfig("ReregisterCycle");
        cfg.setTomcatInfo(unregisteredSnapshot);

        ApplicationServerSection section = createdSection();
        section.resetFrom(cfg);
        assertSame("first open: the injected snapshot is selected",
                unregisteredSnapshot, section.getSelectedTomcatServer());
        assertFalse("first open: unregistered must block Run",
                section.isConfigurationValid());
        assertFalse("first open: validator surfaces the not-registered error",
                section.validateSettings().isEmpty());

        // User re-registers via the global settings.
        TomcatInfo registered = server("new-canonical-id", "Tomcat", canonicalPath);
        state.setTomcatInfos(List.of(registered));

        // Re-open: the section reloads and should resolve by path.
        section.loadConfiguration();
        section.resetFrom(cfg);

        assertSame("after re-register + reopen, selection must be the canonical instance",
                registered, section.getSelectedTomcatServer());
        assertTrue("after re-register: no validator error",
                section.validateSettings().isEmpty());
        assertTrue("after re-register: fully valid",
                section.isConfigurationValid());
    }
}
