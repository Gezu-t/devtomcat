package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the strict-registration gate in {@link TomcatConfigurationValidator}: a
 * run configuration referencing an unregistered {@link TomcatInfo} must fail
 * validation so the toolbar Run button blocks with a visible warning instead
 * of silently launching from the embedded snapshot.
 */
public class TomcatConfigurationValidatorPlatformTest extends BasePlatformTestCase {

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

    private String tempServerPath(String hint) throws IOException {
        File dir = FileUtil.createTempDirectory("devtomcat-validator-" + hint + "-", null, true);
        tempDirs.add(dir);
        return dir.getAbsolutePath();
    }

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration cfg = new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
        // Baseline ports so the port validator doesn't fire before the Tomcat
        // server validator does.
        cfg.setHttpPort(8080);
        cfg.setShutdownPort(8005);
        return cfg;
    }

    private static TomcatInfo server(String id, String name, String path) {
        TomcatInfo info = new TomcatInfo(name, "10.1.28", path);
        info.setId(id);
        return info;
    }

    public void testRegisteredServerPassesValidation() throws IOException {
        TomcatInfo registered = server("id-A", "Tomcat A", tempServerPath("registered"));
        state.setTomcatInfos(List.of(registered));

        TomcatRunConfiguration cfg = createConfig("RegisteredPasses");
        cfg.setTomcatInfo(registered);

        try {
            TomcatConfigurationValidator.validate(cfg);
        } catch (RuntimeConfigurationException e) {
            fail("registered server must pass the registration gate: " + e.getMessage());
        }
    }

    public void testUnregisteredServerBlocksToolbarRun() throws IOException {
        // Config references a server whose snapshot is valid-looking and whose
        // path exists on disk, but it is NOT in Application Servers. This is
        // the scenario the user hit: toolbar Run silently launched from the
        // embedded snapshot. The new strict gate must throw so IntelliJ shows
        // a warning and blocks execution.
        state.setTomcatInfos(List.of(
                server("other-id", "Other", tempServerPath("other"))));

        TomcatInfo ghost = server("ghost-id", "Imported Tomcat", tempServerPath("ghost"));
        TomcatRunConfiguration cfg = createConfig("UnregisteredBlocks");
        cfg.setTomcatInfo(ghost);

        try {
            TomcatConfigurationValidator.validate(cfg);
            fail("unregistered server must throw at the validation gate");
        } catch (RuntimeConfigurationException e) {
            String msg = e.getLocalizedMessage();
            assertNotNull("exception must carry a message", msg);
            assertTrue("message must name the server (was: " + msg + ")",
                    msg.contains("Imported Tomcat"));
            assertTrue("message must explain what to do (was: " + msg + ")",
                    msg.toLowerCase().contains("not registered"));
        }
    }

    public void testDriftedIdStillResolvesByPath() throws IOException {
        // Same path as registered, different ID — the resolver must match by
        // path and the validator must accept it. If it didn't, imported/cloned
        // configs would be blocked even when the registered list has the
        // equivalent server.
        String path = tempServerPath("shared");
        TomcatInfo registered = server("canonical", "Tomcat", path);
        state.setTomcatInfos(List.of(registered));

        TomcatInfo drifted = server("stale-id", "Tomcat", path);
        TomcatRunConfiguration cfg = createConfig("DriftedPasses");
        cfg.setTomcatInfo(drifted);

        try {
            TomcatConfigurationValidator.validate(cfg);
        } catch (RuntimeConfigurationException e) {
            fail("ID drift against a matching registered path must not block: " + e.getMessage());
        }
    }
}
