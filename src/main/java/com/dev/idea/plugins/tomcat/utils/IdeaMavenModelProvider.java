package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

/**
 * {@link MavenModelProvider} backed by the real {@code org.jetbrains.idea.maven}
 * typed API. Registered only in {@code devtomcat-maven.xml}, which the platform
 * loads only when the Maven plugin is present — so this class (and the Maven
 * types it references) is never classloaded on an install without Maven.
 *
 * <p>All accessors return {@code null} when the module is not a resolved Maven
 * project, matching the {@link MavenModelProvider} contract.
 */
public final class IdeaMavenModelProvider implements MavenModelProvider {

    private static final String WAR_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
    private static final String WAR_PLUGIN_ARTIFACT_ID = "maven-war-plugin";

    @Override
    @Nullable
    public String getArtifactId(@NotNull Module module) {
        MavenProject project = mavenProject(module);
        if (project == null) return null;
        MavenId id = project.getMavenId();
        return id != null ? id.getArtifactId() : null;
    }

    @Override
    @Nullable
    public String getPackaging(@NotNull Module module) {
        MavenProject project = mavenProject(module);
        return project != null ? project.getPackaging() : null;
    }

    @Override
    @Nullable
    public Element getWarPluginConfiguration(@NotNull Module module) {
        MavenProject project = mavenProject(module);
        return project != null
                ? project.getPluginConfiguration(WAR_PLUGIN_GROUP_ID, WAR_PLUGIN_ARTIFACT_ID)
                : null;
    }

    @Override
    @Nullable
    public String getFinalName(@NotNull Module module) {
        MavenProject project = mavenProject(module);
        return project != null ? project.getFinalName() : null;
    }

    @Override
    @Nullable
    public String getBuildDirectory(@NotNull Module module) {
        MavenProject project = mavenProject(module);
        return project != null ? project.getBuildDirectory() : null;
    }

    @Nullable
    private static MavenProject mavenProject(@NotNull Module module) {
        return MavenProjectsManager.getInstance(module.getProject()).findProject(module);
    }
}
