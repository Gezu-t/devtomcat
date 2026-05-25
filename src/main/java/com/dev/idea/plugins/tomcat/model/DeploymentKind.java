package com.dev.idea.plugins.tomcat.model;

/** Discriminator for the three {@link Deployment} flavours. */
public enum DeploymentKind {
    ARTIFACT,
    MODULE,
    EXTERNAL
}
