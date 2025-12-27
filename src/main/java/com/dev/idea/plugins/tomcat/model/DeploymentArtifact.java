package com.dev.idea.plugins.tomcat.model;

        import com.intellij.openapi.util.text.StringUtil;
        import org.jetbrains.annotations.NotNull;
        import org.jetbrains.annotations.Nullable;

        import java.io.File;
        import java.io.Serial;
        import java.io.Serializable;
        import java.util.Objects;

        /**
         * Deployment Artifact for Tomcat.
         *
         * Represents a deployable artifact (WAR, exploded directory, etc.)
         * with path, name, type, and context path information.
         *
         * Author: Dev Tomcat Team
         * Project: DevTomcat Plugin
         */
        public class DeploymentArtifact implements Serializable, Cloneable {

            @Serial
            private static final long serialVersionUID = 1L;

            public static final String TYPE_WAR = "war";
            public static final String TYPE_EXPLODED = "exploded";
            public static final String TYPE_EXTERNAL = "external";

            @NotNull private String name = "";
            @NotNull private String path = "";
            @NotNull private String type = TYPE_WAR;
            @NotNull private String contextPath = "/";
            private boolean deployed = true; // Whether this artifact should be deployed

            public DeploymentArtifact() {}

            public DeploymentArtifact(@NotNull String name, @NotNull String path, @NotNull String type) {
                this.name = Objects.requireNonNull(name);
                this.path = Objects.requireNonNull(path);
                this.type = Objects.requireNonNull(type);
            }

            @NotNull
            public String getName() { return name; }

            public void setName(@Nullable String name) {
                this.name = StringUtil.notNullize(name);
            }

            @NotNull
            public String getPath() { return path; }

            public void setPath(@Nullable String path) {
                this.path = StringUtil.notNullize(path);
            }

            @NotNull
            public String getType() { return type; }

            public void setType(@Nullable String type) {
                this.type = StringUtil.notNullize(type, TYPE_WAR);
            }

            @NotNull
            public String getContextPath() { return contextPath; }

            public void setContextPath(@Nullable String contextPath) {
                this.contextPath = StringUtil.notNullize(contextPath, "/");
            }

            /**
             * Alias for getContextPath() - used by UI components.
             */
            @NotNull
            public String getApplicationContext() {
                return getContextPath();
            }

            /**
             * Alias for setContextPath() - used by UI components.
             */
            public void setApplicationContext(@Nullable String context) {
                setContextPath(context);
            }

            /**
             * Gets the display name for this artifact.
             * Uses the name field, or derives it from the path if name is empty.
             */
            @NotNull
            public String getDisplayName() {
                if (!name.isEmpty()) {
                    return name;
                }
                // Derive name from path
                File file = new File(path);
                return file.getName();
            }

            /**
             * Gets the server path for deployment.
             * This is an alias for contextPath.
             */
            @NotNull
            public String getServerPath() {
                return contextPath;
            }

            /**
             * Sets the server path for deployment.
             * This is an alias for setContextPath.
             */
            public void setServerPath(@Nullable String serverPath) {
                setContextPath(serverPath);
            }

            /**
             * Checks if this deployment is using the default context path.
             * Returns true if contextPath is "/" or empty.
             */
            public boolean isUsingDefaultContext() {
                return contextPath.isEmpty() || contextPath.equals("/");
            }

            /**
             * Checks if this artifact is marked for deployment.
             */
            public boolean isDeployed() {
                return deployed;
            }

            /**
             * Sets whether this artifact should be deployed.
             */
            public void setDeployed(boolean deployed) {
                this.deployed = deployed;
            }

            public boolean isValid() {
                if (name.isEmpty() || path.isEmpty()) return false;
                File file = new File(path);
                return file.exists();
            }

            @NotNull
            @Override
            public DeploymentArtifact clone() {
                try {
                    return (DeploymentArtifact) super.clone();
                } catch (CloneNotSupportedException e) {
                    DeploymentArtifact copy = new DeploymentArtifact();
                    copy.name = this.name;
                    copy.path = this.path;
                    copy.type = this.type;
                    copy.contextPath = this.contextPath;
                    copy.deployed = this.deployed;
                    return copy;
                }
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof DeploymentArtifact that)) return false;
                return name.equals(that.name) && path.equals(that.path) &&
                       type.equals(that.type) && contextPath.equals(that.contextPath);
            }

            @Override
            public int hashCode() {
                return Objects.hash(name, path, type, contextPath);
            }

            @NotNull
            @Override
            public String toString() {
                return "DeploymentArtifact{name='" + name + "', type='" + type + "', path='" + path + "'}";
            }
        }