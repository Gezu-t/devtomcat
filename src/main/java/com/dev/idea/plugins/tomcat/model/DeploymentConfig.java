/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/1/25
 */


package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DeploymentConfig implements Serializable, Cloneable {
    public List<DeploymentArtifact> artifacts = new ArrayList<>();

    public DeploymentConfig() {}

    public DeploymentConfig(DeploymentConfig other) {
        for (DeploymentArtifact a : other.artifacts) {
            this.artifacts.add(a.clone());
        }
    }

    @Override
    public DeploymentConfig clone() {
        try {
            DeploymentConfig clone = (DeploymentConfig) super.clone();
            clone.artifacts = new ArrayList<>();
            for (DeploymentArtifact a : this.artifacts) {
                clone.artifacts.add(a.clone());
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}