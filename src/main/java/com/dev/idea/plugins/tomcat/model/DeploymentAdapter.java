package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * @deprecated Bridge between the legacy {@link DeploymentArtifact} and the
 * typed {@link Deployment} hierarchy. Used only by the XML serializer
 * back-compat path and a small number of in-flight migration sites.
 * Scheduled for removal once all callers consume {@code Deployment}
 * directly — see LOCAL_NOTES.md "Phase 4b–5 handoff".
 */
@Deprecated(forRemoval = true)
public final class DeploymentAdapter {

    private DeploymentAdapter() {}

    /** Map a legacy artifact to the appropriate typed subclass. */
    public static @NotNull Deployment toTyped(@NotNull Project project,
                                              @NotNull DeploymentArtifact legacy) {
        boolean exploded = DeploymentArtifact.TYPE_EXPLODED.equals(legacy.getType());
        String context = legacy.getContextPath();

        return switch (legacy.getSource()) {
            case INTELLIJ_ARTIFACT ->
                    ArtifactBackedDeployment.ofName(project, legacy.getName(), context);

            case AUTO_DETECTED ->
                    ModuleBackedDeployment.ofName(
                            project, legacy.getName(),
                            Path.of(legacy.getPath()), context, exploded);

            case EXTERNAL ->
                    new ExternalFileDeployment(
                            Path.of(legacy.getPath()), context, exploded);
        };
    }

    /** Map a typed deployment back to legacy form for serialization. */
    public static @NotNull DeploymentArtifact toLegacy(@NotNull Project project,
                                                       @NotNull Deployment typed) {
        DeploymentArtifact out = new DeploymentArtifact();
        out.setContextPath(typed.getContextPath());
        out.setType(typed.isExploded()
                ? DeploymentArtifact.TYPE_EXPLODED
                : DeploymentArtifact.TYPE_WAR);

        if (typed instanceof ArtifactBackedDeployment a) {
            out.setName(a.getArtifactName());
            Path resolved = a.getResolvedPath(project);
            out.setPath(resolved == null ? "" : resolved.toString());
            out.setSource(DeploymentArtifact.Source.INTELLIJ_ARTIFACT);
        } else if (typed instanceof ModuleBackedDeployment m) {
            out.setName(m.getModuleName());
            out.setPath(m.getOutputPath().toString());
            out.setSource(DeploymentArtifact.Source.AUTO_DETECTED);
        } else if (typed instanceof ExternalFileDeployment e) {
            out.setName(e.getDisplayName());
            out.setPath(e.getExternalPath().toString());
            out.setSource(DeploymentArtifact.Source.EXTERNAL);
        } else {
            throw new IllegalStateException(
                    "Unhandled Deployment subtype: " + typed.getClass());
        }
        return out;
    }
}
