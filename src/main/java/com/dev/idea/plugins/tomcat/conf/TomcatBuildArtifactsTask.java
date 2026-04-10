package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.BeforeRunTask;
import com.intellij.openapi.util.Key;
import org.jdom.Element;
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
 * <p>{@code artifactNames} is persisted via {@link #writeExternal}/{@link #readExternal}
 * so the Before Launch label survives IDE restarts without requiring the configuration
 * editor to be opened. The authoritative artifact data always lives in
 * {@link com.dev.idea.plugins.tomcat.model.DeploymentConfig}.
 */
public class TomcatBuildArtifactsTask extends BeforeRunTask<TomcatBuildArtifactsTask> {

    private static final String ELEMENT_ARTIFACTS = "artifacts";
    private static final String ELEMENT_ARTIFACT  = "artifact";
    private static final String ATTR_NAME         = "name";

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
        artifactNames.addAll(names);
    }

    // BeforeRunTask.writeExternal/readExternal are deprecated in favor of PersistentStateComponent,
    // but that API stores state in a separate file — unsuitable for tasks whose state must be
    // embedded inline in the run configuration XML. RunManagerImpl still calls these methods
    // for inline serialization; no public plugin API alternative exists for this pattern yet.
    @SuppressWarnings("deprecation")
    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        if (!artifactNames.isEmpty()) {
            Element artifacts = new Element(ELEMENT_ARTIFACTS);
            for (String name : artifactNames) {
                artifacts.addContent(new Element(ELEMENT_ARTIFACT).setAttribute(ATTR_NAME, name));
            }
            element.addContent(artifacts);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        artifactNames.clear();
        Element artifacts = element.getChild(ELEMENT_ARTIFACTS);
        if (artifacts != null) {
            for (Element artifact : artifacts.getChildren(ELEMENT_ARTIFACT)) {
                String name = artifact.getAttributeValue(ATTR_NAME);
                if (name != null && !name.isBlank()) {
                    artifactNames.add(name);
                }
            }
        }
    }
}
