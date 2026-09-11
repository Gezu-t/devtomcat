package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** A cancellation raised while the factory picks a Tomcat server must reach the caller, not be logged. */
public class TomcatConfigurationFactoryCancellationPlatformTest extends BasePlatformTestCase {

    public void testTemplateCreationPropagatesCancellation() {
        ConfigurationFactory factory = new TomcatRunConfigurationType().getConfigurationFactories()[0];
        ProcessCanceledException pce = new ProcessCanceledException();
        try (MockedStatic<TomcatServerManagerState> state = Mockito.mockStatic(TomcatServerManagerState.class)) {
            state.when(TomcatServerManagerState::getInstance).thenThrow(pce);
            assertSame(pce, cancellationFrom(() -> factory.createTemplateConfiguration(getProject())));
        }
    }

    public void testConfigurationCreationPropagatesCancellation() {
        ConfigurationFactory factory = new TomcatRunConfigurationType().getConfigurationFactories()[0];
        RunConfiguration template = factory.createTemplateConfiguration(getProject());
        ProcessCanceledException pce = new ProcessCanceledException();
        try (MockedStatic<TomcatServerManagerState> state = Mockito.mockStatic(TomcatServerManagerState.class)) {
            state.when(TomcatServerManagerState::getInstance).thenThrow(pce);
            assertSame(pce, cancellationFrom(() -> factory.createConfiguration("Tomcat", template)));
        }
    }

    private static ProcessCanceledException cancellationFrom(Runnable action) {
        try {
            action.run();
        } catch (ProcessCanceledException e) {
            return e;
        }
        fail("cancellation was swallowed");
        return null;
    }
}
