package com.dev.idea.plugins.tomcat.conf;

         import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
         import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
         import com.intellij.openapi.diagnostic.Logger;
         import org.jetbrains.annotations.NotNull;

         import java.util.ArrayList;
         import java.util.List;
         import java.util.Objects;

         /**
          * Dev Tomcat Configuration Cloner
          *
          * <p>Deep clones using only the modern model.
          */
         public class TomcatConfigurationCloner {

             private static final Logger LOG = Logger.getInstance(TomcatConfigurationCloner.class);

             @NotNull
             public static TomcatRunConfiguration clone(@NotNull TomcatRunConfiguration original) {
                 Objects.requireNonNull(original, "Configuration cannot be null");

                 try {
                     LOG.debug("Cloning configuration: {}", original.getName());

                     TomcatRunConfiguration clone = new TomcatRunConfiguration(
                             original.getProject(),
                             original.getFactory(),
                             original.getName() + " (copy)"
                     );

                     TomcatConfigurationData src = original.getConfigData();
                     TomcatConfigurationData dst = clone.getConfigData();

                     // === CORE ===
                     dst.setTomcatInfo(src.getTomcatInfo() != null ? src.getTomcatInfo().clone() : null);
                     dst.setContextPath(src.getContextPath());
                     dst.setServerMode(src.getServerMode());
                     dst.setJreSelection(src.getJreSelection());
                     dst.setStoreAsProjectFile(src.isStoreAsProjectFile());

                     // === EXTERNAL CONFIGS (use clone()) ===
                     dst.setPortConfig(src.getPortConfig().clone());
                     dst.setDeploymentConfig(src.getDeploymentConfig().clone());
                     dst.setVmConfig(src.getVmConfig().clone());
                     dst.setBrowserConfig(src.getBrowserConfig().clone());
                     dst.setUpdateConfig(src.getUpdateConfig().clone());
                     dst.setUiConfig(src.getUiConfig().clone());
                     dst.setDebugConfig(src.getDebugConfig().clone());
                     dst.setRemoteConfig(src.getRemoteConfig().clone());
                     dst.setLogFileConfig(src.getLogFileConfig().clone());

                     // === DEPLOYMENT ARTIFACTS ===
                     List<DeploymentArtifact> srcArtifacts = src.getDeploymentConfig().getArtifacts();
                     if (srcArtifacts != null && !srcArtifacts.isEmpty()) {
                         List<DeploymentArtifact> clonedArtifacts = new ArrayList<>(srcArtifacts.size());
                         for (DeploymentArtifact artifact : srcArtifacts) {
                             if (artifact != null) {
                                 clonedArtifacts.add(artifact.clone());
                             }
                         }
                         dst.getDeploymentConfig().setArtifacts(clonedArtifacts);
                     }

                     LOG.debug("Cloned: {} -> {}", original.getName(), clone.getName());
                     validateClone(clone);
                     return clone;

                 } catch (Exception e) {
                     LOG.error("Clone failed for configuration: " + original.getName(), e);
                     throw new RuntimeException("Clone failed for: " + original.getName(), e);
                 }
             }

             private static void validateClone(@NotNull TomcatRunConfiguration clone) {
                 TomcatConfigurationData data = clone.getConfigData();

                 if (data.getTomcatInfo() == null) {
                     LOG.warn(String.format("Clone '%s' missing Tomcat server configuration", clone.getName()));
                 }

                 if (data.getPortConfig().getHttp() == 0) {
                     LOG.warn(String.format("Clone '%s' has invalid HTTP port (0)", clone.getName()));
                 }
             }

             @NotNull
             public static String getCloneSummary(@NotNull TomcatRunConfiguration clone) {
                 Objects.requireNonNull(clone, "Clone cannot be null");
                 TomcatConfigurationData d = clone.getConfigData();

                 String serverName = d.getTomcatInfo() != null ? d.getTomcatInfo().getName() : "None";
                 int httpPort = d.getPortConfig().getHttp();
                 int artifactCount = d.getDeploymentConfig().getArtifacts() != null
                         ? d.getDeploymentConfig().getArtifacts().size()
                         : 0;

                 return String.format(
                         "Cloned '%s': Server=%s, HTTP=%d, Mode=%s, Artifacts=%d",
                         clone.getName(),
                         serverName,
                         httpPort,
                         d.getServerMode(),
                         artifactCount
                 );
             }
         }