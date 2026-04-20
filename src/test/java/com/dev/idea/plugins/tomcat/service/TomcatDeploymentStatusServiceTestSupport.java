package com.dev.idea.plugins.tomcat.service;

import org.jetbrains.annotations.NotNull;

/**
 * Exposes the package-private test constructor of
 * {@link TomcatDeploymentStatusService} to tests living in other packages
 * (notably the pipeline integration harness), without widening the
 * production API surface.
 */
public final class TomcatDeploymentStatusServiceTestSupport {

    private TomcatDeploymentStatusServiceTestSupport() {}

    public static @NotNull TomcatDeploymentStatusService createForTest(@NotNull Runnable refreshAction) {
        return new TomcatDeploymentStatusService(refreshAction);
    }
}
