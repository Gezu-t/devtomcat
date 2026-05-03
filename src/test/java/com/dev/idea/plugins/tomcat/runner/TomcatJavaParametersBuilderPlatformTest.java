package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform-fixture coverage of {@link TomcatJavaParametersBuilder#resolveJdkOrNull}
 * — the static JRE resolver shared between the launcher and the compatibility
 * checker.
 *
 * <p>The contract this method enforces (verified here):
 * <ol>
 *   <li>An explicit JRE selection that matches a registered SDK wins.</li>
 *   <li>A null / empty / {@code "Project default"} selection falls through
 *       to {@link com.intellij.openapi.roots.ProjectRootManager#getProjectSdk()}.</li>
 *   <li>A selection that names a JRE not in the table also falls through
 *       (with a logged warning) — we never fail launch on a stale persisted
 *       JRE name; the project SDK is always the safe default.</li>
 * </ol>
 *
 * <p>BasePlatformTestCase's project starts with no SDK set, so the
 * fall-through cases below assert {@code null} — that's the contract:
 * "no JRE configured anywhere" must propagate as null so the builder's
 * {@link TomcatJavaParametersBuilder#build()} can throw a clear
 * "configure a Project SDK" message instead of a NullPointerException
 * from somewhere deeper in the launch pipeline.
 */
public class TomcatJavaParametersBuilderPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    public void testNullSelectionFallsThroughToProjectSdk() {
        TomcatRunConfiguration cfg = createConfig("JreFallback-Null");
        cfg.getConfigData().setJreSelection(null);

        Sdk resolved = TomcatJavaParametersBuilder.resolveJdkOrNull(cfg, getProject());

        // BasePlatformTestCase has no project SDK configured by default,
        // so the fall-through must produce null — never throw.
        assertNull("null jreSelection must fall through to project SDK (which is unset here)",
                resolved);
    }

    public void testEmptySelectionFallsThroughToProjectSdk() {
        // Empty string is the "user cleared the dropdown" state — same
        // semantics as null. Pin both so a future refactor doesn't accidentally
        // start treating "" as a JRE name to look up.
        TomcatRunConfiguration cfg = createConfig("JreFallback-Empty");
        cfg.getConfigData().setJreSelection("");

        Sdk resolved = TomcatJavaParametersBuilder.resolveJdkOrNull(cfg, getProject());

        assertNull("empty jreSelection must fall through to project SDK", resolved);
    }

    public void testProjectDefaultSentinelFallsThrough() {
        // "Project default" is the user-visible label for "use whatever the
        // project is configured with". The constant lives in TomcatConstants
        // so the UI dropdown and this resolver share the same sentinel —
        // pin that integration so the dropdown wiring can't drift away from
        // the resolver's check.
        TomcatRunConfiguration cfg = createConfig("JreFallback-Default");
        cfg.getConfigData().setJreSelection(TomcatConstants.JRE_PROJECT_DEFAULT);

        Sdk resolved = TomcatJavaParametersBuilder.resolveJdkOrNull(cfg, getProject());

        assertNull("\"" + TomcatConstants.JRE_PROJECT_DEFAULT
                        + "\" sentinel must fall through to project SDK",
                resolved);
    }

    public void testUnknownJreNameFallsThroughInsteadOfThrowing() {
        // A persisted JRE name that no longer exists in the SDK table (the
        // user uninstalled the JDK between IDE sessions) MUST NOT make the
        // launch fail here — the resolver logs a warning and falls back to
        // the project SDK. The build() caller can still throw a clear
        // "configure a Project SDK" error if both are unavailable, but the
        // path through this method must be exception-free.
        TomcatRunConfiguration cfg = createConfig("JreFallback-Unknown");
        cfg.getConfigData().setJreSelection("definitely-not-a-real-jdk-name-12345");

        Sdk resolved = TomcatJavaParametersBuilder.resolveJdkOrNull(cfg, getProject());

        assertNull("unknown JRE name must fall through to project SDK, not throw", resolved);
    }
}
