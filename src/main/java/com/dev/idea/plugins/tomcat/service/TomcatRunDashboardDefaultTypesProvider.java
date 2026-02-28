package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

/**
 * Auto-registers the DevTomcat run configuration type in the Services tool window.
 * Without this, users would need to manually add it via "Add Service" → "Run Configuration Type".
 */
public class TomcatRunDashboardDefaultTypesProvider implements RunDashboardDefaultTypesProvider {

    @Override
    @NotNull
    public Collection<String> getDefaultTypeIds(@NotNull Project project) {
        return Set.of(TomcatRunConfigurationType.ID);
    }
}
