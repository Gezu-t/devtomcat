package com.dev.idea.plugins.tomcat.coverage;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.coverage.CoverageDataManager;
import com.intellij.coverage.CoverageSuite;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration;
import com.intellij.execution.configurations.coverage.JavaCoverageEnabledConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.classFilter.ClassFilter;
import org.jetbrains.annotations.NotNull;

/**
 * Orchestrates the hand-off from DevTomcat's {@link CoverageConfig} to the
 * IntelliJ coverage pipeline for a single Tomcat launch.
 *
 * <p>Runs once per coverage launch from {@code TomcatJavaParametersBuilder}
 * immediately before the process starts. The sequence:
 * <ol>
 *   <li>Obtain the {@link JavaCoverageEnabledConfiguration} for the run config
 *       (lazily created by the platform when
 *       {@link TomcatJavaCoverageEngineExtension#isApplicableTo} returns true).</li>
 *   <li>Push our include/exclude patterns into it via {@link CoverageConfigBridge}
 *       so the platform's own view of the filters matches what the user
 *       entered in our tab.</li>
 *   <li>Ensure there is exactly one current suite via
 *       {@link CoverageDataManager#addCoverageSuite(CoverageEnabledConfiguration)}.
 *       This path is safe inside the Java-parameter build read action, unlike
 *       {@code CoverageHelper.resetCoverageSuit}, which now performs EDT
 *       {@code invokeAndWait} and deadlocks when called from launch-time read
 *       actions on newer IDEs.</li>
 *   <li>Delegate the {@code -javaagent} construction to
 *       {@link JavaCoverageEnabledConfiguration#appendCoverageArgument} so the
 *       bundled coverage runner (JaCoCo or IntelliJ's own agent) is chosen and
 *       wired correctly for the host IDE version — we never hand-roll the
 *       argument string.</li>
 * </ol>
 *
 * <p>Safety: all failures are logged and swallowed so a coverage misconfiguration
 * never prevents Tomcat from starting. A coverage executor run without any
 * agent still produces a working server; the user sees an empty coverage
 * report and an entry in the IDE log.
 */
public final class CoverageAgentAttacher {

    private static final Logger LOG = Logger.getInstance(CoverageAgentAttacher.class);

    private CoverageAgentAttacher() {}

    /**
     * Attaches the coverage agent to {@code javaParameters} for the given
     * run configuration. No-op when {@link CoverageEnabledConfiguration#getOrCreate}
     * returns a non-Java configuration (defensive — should not happen once
     * the engine extension is registered).
     */
    public static void attach(@NotNull TomcatRunConfiguration config,
                              @NotNull JavaParameters javaParameters) {
        try {
            CoverageEnabledConfiguration cec = CoverageEnabledConfiguration.getOrCreate(config);
            if (!(cec instanceof JavaCoverageEnabledConfiguration jcec)) {
                LOG.warn("Coverage configuration for '" + config.getName()
                        + "' is not a JavaCoverageEnabledConfiguration — coverage will not be attached. "
                        + "Check that the Coverage plugin is enabled and that TomcatJavaCoverageEngineExtension "
                        + "is registered.");
                return;
            }

            syncPatterns(jcec, config.getConfigData().getCoverageConfig());

            CoverageSuite suite = ensureCoverageSuite(config, jcec);
            if (suite == null) {
                LOG.warn("DevTomcat: coverage suite setup returned null for '"
                        + config.getName() + "' — skipping agent injection");
                return;
            }

            // Agent argument wiring is the platform's responsibility — version
            // skew on the agent jar or JaCoCo dropped in recent builds would
            // break a hand-rolled -javaagent string; delegating to the platform
            // keeps us resilient to those swaps.
            jcec.appendCoverageArgument(suite, javaParameters);

            LOG.info("DevTomcat: attached coverage agent for '" + config.getName()
                    + "' — " + jcec.getPatterns().length + " include, "
                    + jcec.getExcludePatterns().length + " exclude");

        } catch (Throwable t) {
            // Never let a coverage problem block the Tomcat launch. The user
            // sees the server come up; the console log line explains why no
            // coverage appeared.
            LOG.warn("DevTomcat: failed to attach coverage agent for '" + config.getName()
                    + "' — Tomcat will run without coverage instrumentation", t);
        }
    }

    /**
     * Ensures the platform has a current coverage suite selected for this
     * configuration without routing through {@code CoverageHelper}, whose
     * launch-time EDT handoff is not safe inside read actions.
     */
    public static CoverageSuite ensureCoverageSuite(@NotNull TomcatRunConfiguration config) {
        CoverageEnabledConfiguration cec = CoverageEnabledConfiguration.getOrCreate(config);
        if (!(cec instanceof JavaCoverageEnabledConfiguration jcec)) {
            return null;
        }
        syncPatterns(jcec, config.getConfigData().getCoverageConfig());
        return ensureCoverageSuite(config, jcec);
    }

    private static CoverageSuite ensureCoverageSuite(@NotNull TomcatRunConfiguration config,
                                                     @NotNull CoverageEnabledConfiguration cec) {
        CoverageSuite current = cec.getCurrentCoverageSuite();
        if (current != null) {
            return current;
        }

        CoverageSuite suite = CoverageDataManager.getInstance(config.getProject()).addCoverageSuite(cec);
        if (suite != null) {
            cec.setCurrentCoverageSuite(suite);
        }
        return suite;
    }

    /**
     * Pushes DevTomcat's include/exclude lists into the IntelliJ-side
     * {@link JavaCoverageEnabledConfiguration}. Called every launch so a user
     * who edited the tab between launches sees their new patterns take
     * effect without restarting the IDE.
     */
    private static void syncPatterns(@NotNull JavaCoverageEnabledConfiguration jcec,
                                     @NotNull CoverageConfig source) {
        ClassFilter[] filters = CoverageConfigBridge.toClassFilters(source);
        jcec.setCoveragePatterns(filters);
    }
}
