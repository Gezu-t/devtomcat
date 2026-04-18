package com.dev.idea.plugins.tomcat.conf;

         import com.dev.idea.plugins.tomcat.model.RunnerSettings;
         import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
         import com.intellij.execution.configurations.LogFileOptions;
         import com.intellij.openapi.diagnostic.Logger;
         import org.jetbrains.annotations.NotNull;

         import java.util.LinkedHashMap;
         import java.util.Map;
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
                     LOG.debug("Cloning configuration: " + original.getName());

                     TomcatRunConfiguration clone = new TomcatRunConfiguration(
                             original.getProject(),
                             original.getFactory(),
                             original.getName()
                     );

                     TomcatConfigurationData src = original.getConfigData();
                     TomcatConfigurationData dst = clone.getConfigData();

                     // === CORE ===
                     var srcTomcatInfo = src.getTomcatInfo();
                    dst.setTomcatInfo(srcTomcatInfo != null ? srcTomcatInfo.clone() : null);
                     dst.setContextPath(src.getContextPath());
                     dst.setServerMode(src.getServerMode());
                     dst.setCatalinaBase(src.getCatalinaBase());
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
                     dst.setCoverageConfig(src.getCoverageConfig().clone());

                     // Deep-clone per-runner settings (startup/shutdown scripts, env vars)
                     Map<String, RunnerSettings> clonedRunnerSettings = new LinkedHashMap<>();
                     for (Map.Entry<String, RunnerSettings> entry :
                              src.getRunnerSettingsMap().entrySet()) {
                         clonedRunnerSettings.put(entry.getKey(), entry.getValue().clone());
                     }
                     dst.setRunnerSettingsMap(clonedRunnerSettings);

                     // Clone platform-managed log and console settings that live on
                     // RunConfigurationBase, not in TomcatConfigurationData.
                     copyPlatformLogSettings(original, clone);

                     // Clone fields on TomcatRunConfiguration itself (outside TomcatConfigurationData)
                     clone.setDocBase(original.getDocBase());

                     LOG.debug("Cloned: " + original.getName() + " -> " + clone.getName());
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

             private static void copyPlatformLogSettings(@NotNull TomcatRunConfiguration original,
                                                         @NotNull TomcatRunConfiguration clone) {
                 clone.removeAllLogFiles();
                 for (LogFileOptions logFile : original.getAllLogFiles()) {
                     clone.addLogFile(
                             logFile.getPathPattern(),
                             logFile.getName(),
                             logFile.isEnabled(),
                             logFile.isSkipContent(),
                             logFile.isShowAll()
                     );
                 }
                 // Carry over the one-shot seed marker so the clone's
                 // syncTomcatLogFiles() does not re-seed defaults when the user
                 // has intentionally emptied the list on the original.
                 clone.setLogsSeeded(original.isLogsSeeded());

                 clone.setShowConsoleOnStdOut(original.isShowConsoleOnStdOut());
                 clone.setShowConsoleOnStdErr(original.isShowConsoleOnStdErr());
                 clone.setSaveOutputToFile(original.isSaveOutputToFile());
                 clone.setFileOutputPath(original.getOutputFilePath());
             }

         }
