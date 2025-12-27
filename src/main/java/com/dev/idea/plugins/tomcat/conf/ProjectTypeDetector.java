package com.dev.idea.plugins.tomcat.conf;

            import com.intellij.ide.highlighter.JavaFileType;
            import com.intellij.openapi.diagnostic.Logger;
            import com.intellij.openapi.project.Project;
            import com.intellij.openapi.vfs.VfsUtil;
            import com.intellij.openapi.vfs.VirtualFile;
            import com.intellij.psi.search.FileTypeIndex;
            import com.intellij.psi.search.ProjectScope;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;

            import java.io.File;
            import java.io.IOException;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.util.Collection;
            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.regex.Pattern;

            /**
             * Detects project type for dynamic configuration.
             *
             * <p>Supported detections:
             * <ul>
             *   <li>Spring Boot (via @SpringBootApplication or SpringApplication)</li>
             *   <li>Web Application (via web.xml)</li>
             * </ul>
             *
             * <p>Results are cached per project session.
             */
            public class ProjectTypeDetector {

                private static final Logger LOG = Logger.getInstance(ProjectTypeDetector.class);

                // Patterns
                private static final Pattern SPRING_BOOT_ANNOTATION_PATTERN = Pattern.compile("@SpringBootApplication\\b");
                private static final Pattern SPRING_APPLICATION_PATTERN = Pattern.compile("SpringApplication\\.run\\s*\\(");
                private static final Pattern SPRING_BOOT_STARTER_PATTERN = Pattern.compile("spring-boot-starter");

                // Cache
                private static final Map<String, Boolean> SPRING_BOOT_CACHE = new ConcurrentHashMap<>();
                private static final Map<String, Boolean> WEB_APP_CACHE = new ConcurrentHashMap<>();

                private static final int MAX_FILES_TO_SCAN = 100;
                private static final int MAX_FILE_SIZE = 1024 * 1024; // 1 MB

                /**
                 * Checks if the project is a Spring Boot application.
                 *
                 * <p>Uses caching to avoid repeated scans. Searches for:
                 * <ul>
                 *   <li>@SpringBootApplication annotation</li>
                 *   <li>SpringApplication.run() call</li>
                 *   <li>spring-boot-starter in build files</li>
                 * </ul>
                 *
                 * @param project The IntelliJ project
                 * @return true if Spring Boot is detected
                 */
                public static boolean isSpringBootProject(@NotNull Project project) {
                    String projectPath = project.getBasePath();
                    if (projectPath == null) return false;

                    return SPRING_BOOT_CACHE.computeIfAbsent(projectPath, p ->
                            hasSpringBootAnnotation(project) ||
                                    hasSpringApplicationRun(project) ||
                                    hasSpringBootStarterInBuildFile(project)
                    );
                }

                /**
                 * Checks for @SpringBootApplication in Java files (with early exit).
                 */
        private static boolean hasSpringBootAnnotation(@NotNull Project project) {
            Collection<VirtualFile> javaFiles = FileTypeIndex.getFiles(
                    JavaFileType.INSTANCE,
                    ProjectScope.getAllScope(project)
            );

                    int scanned = 0;
                    for (VirtualFile file : javaFiles) {
                        if (scanned >= MAX_FILES_TO_SCAN) {
                            LOG.debug("Reached max file scan limit for Spring Boot annotation check");
                            return false;
                        }

                        if (file.getLength() > MAX_FILE_SIZE) {
                            continue;
                        }

                        try {
                            String content = VfsUtil.loadText(file);
                            if (content != null && SPRING_BOOT_ANNOTATION_PATTERN.matcher(content).find()) {
                                LOG.debug("Found @SpringBootApplication in {}", file.getPath());
                                return true;
                            }
                        } catch (IOException e) {
                            LOG.debug("Failed to read file: {}", file.getPath(), e);
                        }
                        scanned++;
                    }
                    return false;
                }

                /**
                 * Checks for SpringApplication.run() in Java files (with early exit).
                 */
        private static boolean hasSpringApplicationRun(@NotNull Project project) {
            Collection<VirtualFile> javaFiles = FileTypeIndex.getFiles(
                    JavaFileType.INSTANCE,
                    ProjectScope.getAllScope(project)
            );

                    int scanned = 0;
                    for (VirtualFile file : javaFiles) {
                        if (scanned >= MAX_FILES_TO_SCAN) {
                            LOG.debug("Reached max file scan limit for SpringApplication check");
                            return false;
                        }

                        if (file.getLength() > MAX_FILE_SIZE) {
                            continue;
                        }

                        try {
                            String content = VfsUtil.loadText(file);
                            if (content != null && SPRING_APPLICATION_PATTERN.matcher(content).find()) {
                                LOG.debug("Found SpringApplication.run() in {}", file.getPath());
                                return true;
                            }
                        } catch (IOException e) {
                            LOG.debug("Failed to read file: {}", file.getPath(), e);
                        }
                        scanned++;
                    }
                    return false;
                }

                /**
                 * Checks for spring-boot-starter in build files.
                 *
                 * <p>Supports:
                 * <ul>
                 *   <li>pom.xml</li>
                 *   <li>build.gradle</li>
                 *   <li>build.gradle.kts</li>
                 * </ul>
                 */
                private static boolean hasSpringBootStarterInBuildFile(@NotNull Project project) {
                    String basePath = project.getBasePath();
                    if (basePath == null) return false;

                    File pom = new File(basePath, "pom.xml");
                    File gradle = new File(basePath, "build.gradle");
                    File gradleKts = new File(basePath, "build.gradle.kts");

                    try {
                        if (pom.exists() && checkBuildFileForSpringBootStarter(pom)) {
                            return true;
                        }
                        if (gradle.exists() && checkBuildFileForSpringBootStarter(gradle)) {
                            return true;
                        }
                        if (gradleKts.exists() && checkBuildFileForSpringBootStarter(gradleKts)) {
                            return true;
                        }
                    } catch (Exception e) {
                        LOG.debug("Error checking build files for Spring Boot", e);
                    }

                    return false;
                }

                /**
                 * Helper to check a single build file for spring-boot-starter.
                 */
                private static boolean checkBuildFileForSpringBootStarter(@NotNull File file) {
                    try {
                        if (file.length() > MAX_FILE_SIZE) {
                            LOG.debug("Build file too large: {}", file.getAbsolutePath());
                            return false;
                        }

                        String content = readFile(file);
                        if (content != null && SPRING_BOOT_STARTER_PATTERN.matcher(content).find()) {
                            LOG.debug("Found spring-boot-starter in {}", file.getAbsolutePath());
                            return true;
                        }
                    } catch (IOException e) {
                        LOG.debug("Failed to read build file: {}", file.getAbsolutePath(), e);
                    }

                    return false;
                }

                /**
                 * Checks if the project has a web.xml (traditional WAR).
                 *
                 * @param project The project
                 * @return true if web.xml exists in src/main/webapp/WEB-INF
                 */
                public static boolean isTraditionalWebApp(@NotNull Project project) {
                    String projectPath = project.getBasePath();
                    if (projectPath == null) return false;

                    return WEB_APP_CACHE.computeIfAbsent(projectPath, p -> {
                        try {
                            File webXml = new File(projectPath + "/src/main/webapp/WEB-INF/web.xml");
                            boolean exists = webXml.exists();
                            if (exists) {
                                LOG.debug("Found web.xml at {}", webXml.getAbsolutePath());
                            }
                            return exists;
                        } catch (Exception e) {
                            LOG.debug("Error checking for web.xml", e);
                            return false;
                        }
                    });
                }

                /**
                 * Clears the detection cache (use for testing or project reload).
                 */
                public static void clearCache() {
                    SPRING_BOOT_CACHE.clear();
                    WEB_APP_CACHE.clear();
                    LOG.debug("Project type detection cache cleared");
                }

                /**
                 * Utility: safely read file content.
                 */
                @Nullable
                private static String readFile(@NotNull File file) throws IOException {
                    try {
                        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        LOG.debug("Failed to read file: {}", file.getAbsolutePath(), e);
                        throw e;
                    }
                }
            }
