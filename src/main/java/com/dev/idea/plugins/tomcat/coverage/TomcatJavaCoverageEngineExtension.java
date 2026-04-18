package com.dev.idea.plugins.tomcat.coverage;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.coverage.JavaCoverageEngineExtension;
import com.intellij.execution.configurations.RunConfigurationBase;
import org.jetbrains.annotations.NotNull;

/**
 * Opts {@link TomcatRunConfiguration} into IntelliJ's Java coverage engine.
 *
 * <p>{@link com.intellij.coverage.JavaCoverageEngine#isApplicableTo} only
 * recognises configurations that implement {@code CommonJavaRunConfigurationParameters}
 * or that are claimed by a registered {@link JavaCoverageEngineExtension}.
 * {@code TomcatRunConfiguration} extends {@code LocatableConfigurationBase}
 * and does neither, so without this extension the whole coverage pipeline
 * (agent injection, suite creation, report loading) never engages for
 * Tomcat launches — the "Run with Coverage" executor would start Tomcat
 * normally and report nothing.
 *
 * <p>Registered under {@code com.intellij.javaCoverageEngineExtension} in
 * plugin.xml. The rest of the coverage path is driven by
 * {@code CoverageAgentAttacher} (agent argument) and {@code TomcatCoverageRunner}
 * ({@code CoverageHelper.attachToProcess} for post-run reporting).
 */
public final class TomcatJavaCoverageEngineExtension extends JavaCoverageEngineExtension {

    @Override
    public boolean isApplicableTo(@NotNull RunConfigurationBase<?> conf) {
        return conf instanceof TomcatRunConfiguration;
    }
}
