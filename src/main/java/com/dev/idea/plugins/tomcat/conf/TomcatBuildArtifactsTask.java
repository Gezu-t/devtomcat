package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.BeforeRunTask;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Before-launch task that records which deployment artifacts are configured.
 *
 * <p>Serves two purposes:
 * <ol>
 *   <li><b>Visibility</b> — the Before Launch panel shows "Build 'name'" or
 *       "Build N artifacts" so the user knows exactly what will be deployed,
 *       matching IntelliJ Ultimate's experience on Community Edition.</li>
 *   <li><b>Fail-fast validation</b> — {@link TomcatBuildArtifactsTaskProvider#executeTask}
 *       checks that all artifact paths exist before Tomcat starts, surfacing a clear error
 *       instead of a confusing mid-launch failure.</li>
 * </ol>
 *
 * <p>{@code artifactNames} is persisted inline with the run configuration via
 * {@link PersistentStateComponent} so the Before Launch label survives IDE restarts
 * without relying on the deprecated {@code BeforeRunTask.readExternal/writeExternal}
 * hooks. The authoritative artifact data always lives in
 * {@link com.dev.idea.plugins.tomcat.model.DeploymentConfig}.
 */
public class TomcatBuildArtifactsTask extends BeforeRunTask<TomcatBuildArtifactsTask>
        implements PersistentStateComponent<TomcatBuildArtifactsTask.State> {

    public static final class State {
        public boolean enabled = true;
        public List<String> artifactNames = new ArrayList<>();
    }

    private final List<String> artifactNames = new ArrayList<>();

    public TomcatBuildArtifactsTask(@NotNull Key<TomcatBuildArtifactsTask> providerId) {
        super(providerId);
    }

    @NotNull
    public List<String> getArtifactNames() {
        return new ArrayList<>(artifactNames);
    }

    public void setArtifactNames(@NotNull List<String> names) {
        artifactNames.clear();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                artifactNames.add(name);
            }
        }
    }

    @Override
    public @NotNull State getState() {
        State state = new State();
        state.enabled = isEnabled();
        state.artifactNames.addAll(artifactNames);
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        setEnabled(state.enabled);
        setArtifactNames(state.artifactNames);
    }
}
