package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Registers Tomcat-specific console filters for all DevTomcat run configurations.
 * Provides clickable HTTP URLs and Tomcat log file references in console output.
 */
public final class TomcatConsoleFilterProvider implements ConsoleFilterProvider {

    @Override
    public Filter @NotNull [] getDefaultFilters(@NotNull Project project) {
        return new Filter[]{new TomcatConsoleFilter()};
    }
}
