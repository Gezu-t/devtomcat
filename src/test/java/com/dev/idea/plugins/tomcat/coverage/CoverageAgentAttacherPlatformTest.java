package com.dev.idea.plugins.tomcat.coverage;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.coverage.CoverageSuite;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.coverage.JavaCoverageEnabledConfiguration;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class CoverageAgentAttacherPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name
        );
    }

    public void testAttachSucceedsInsideReadActionAndKeepsCurrentSuite() {
        TomcatRunConfiguration config = createConfig("Coverage");

        JavaParameters params = TomcatReadActions.compute(() -> {
            JavaParameters javaParameters = new JavaParameters();
            CoverageAgentAttacher.attach(config, javaParameters);
            return javaParameters;
        });

        JavaCoverageEnabledConfiguration coverage = JavaCoverageEnabledConfiguration.getFrom(config);
        assertNotNull("coverage configuration must be created for Tomcat configs", coverage);

        CoverageSuite suite = coverage.getCurrentCoverageSuite();
        assertNotNull("attach must create/select a coverage suite without CoverageHelper", suite);
        assertEquals("current suite should stay aligned with the generated coverage data file",
                suite.getCoverageDataFileName(), coverage.getCoverageFilePath());
        assertSame("ensureCoverageSuite must reuse the launch-time suite instead of replacing it",
                suite, CoverageAgentAttacher.ensureCoverageSuite(config));
    }
}
