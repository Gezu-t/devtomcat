package com.dev.idea.plugins.tomcat.serviceview;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.services.ServiceViewContributor;
import com.intellij.execution.services.ServiceViewDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level Services tool window contributor for DevTomcat.
 *
 * <p>Surfaces every Tomcat run configuration in the current project as a
 * service row, with deployment artifacts as nested children via
 * {@link TomcatRunConfigContributor}. This is the replacement for the legacy
 * {@code RunDashboardCustomizer} integration — the platform call stops
 * invoking {@code getChildren} on branch 253, so the artifact tree only
 * renders correctly when it goes through the {@link ServiceViewContributor}
 * pipeline.
 *
 * <p>Registered via the {@code com.intellij.serviceViewContributor} extension
 * point. The existing {@code runDashboardCustomizer} registration stays in
 * place for 251 / 252 so users on those platforms see the same data through
 * the legacy customizer path; on 253+ the customizer is dead and this
 * contributor is the sole renderer.
 */
public final class TomcatServiceViewContributor
        implements ServiceViewContributor<TomcatRunConfigContributor> {

    @Override
    @NotNull
    public List<TomcatRunConfigContributor> getServices(@NotNull Project project) {
        try {
            List<RunnerAndConfigurationSettings> allSettings =
                    RunManager.getInstance(project).getAllSettings();
            List<TomcatRunConfigContributor> services = new ArrayList<>();
            for (RunnerAndConfigurationSettings settings : allSettings) {
                if (settings.getConfiguration() instanceof TomcatRunConfiguration) {
                    services.add(new TomcatRunConfigContributor(settings));
                }
            }
            return services;
        } catch (Throwable t) {
            TomcatProgress.rethrowIfControlFlow(t);
            // RunManager can throw if invoked during project init / disposal.
            return Collections.emptyList();
        }
    }

    @Override
    @NotNull
    public ServiceViewDescriptor getServiceDescriptor(@NotNull Project project,
                                                       @NotNull TomcatRunConfigContributor service) {
        return service.getViewDescriptor(project);
    }

    @Override
    @NotNull
    public ServiceViewDescriptor getViewDescriptor(@NotNull Project project) {
        return new GroupDescriptor();
    }

    /** Renders the top-level "Dev Tomcat" group header in the Services tool window. */
    private static final class GroupDescriptor implements ServiceViewDescriptor {
        private static final Icon GROUP_ICON = AllIcons.Nodes.HomeFolder;

        @Override
        public ItemPresentation getPresentation() {
            PresentationData data = new PresentationData();
            data.setIcon(GROUP_ICON);
            data.addText("Dev Tomcat", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            return data;
        }
    }
}
