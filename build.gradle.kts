/**
 * Dev Tomcat Plugin - Build Configuration
 *
 * This build script configures the Dev Tomcat plugin for IntelliJ IDEA Community Edition.
 * It provides Tomcat server integration features similar to those found in paid IDE versions.
 */

import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

/**
 * Helper function to retrieve properties from gradle.properties file
 * @param key The property key to look up
 * @return The property value as a String
 */
fun prop(key: String) = project.findProperty(key).toString()

// Plugin Dependencies
plugins {
    // Java support
    id("java")

    // IntelliJ Platform Plugin for building IntelliJ IDEA plugins
    id("org.jetbrains.intellij") version "1.13.1"

    // Changelog management for tracking plugin versions
    id("org.jetbrains.changelog") version "2.2.0"
}

// Project Configuration
group = prop("pluginGroup")
version = prop("pluginVersion")

/**
 * Repository Configuration
 *
 * Defines where to fetch dependencies from.
 * Listed in order of preference/reliability.
 */
repositories {
    // JetBrains repositories for IntelliJ-specific dependencies
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://www.jetbrains.com/intellij-repository/snapshots")

    // Aliyun mirror for improved download speeds in certain regions
    // Comment out if not needed
    maven("https://maven.aliyun.com/repository/public/")

    // Standard Maven Central repository
    mavenCentral()
}

/**
 * IntelliJ Platform Plugin Configuration
 *
 * Configures the IntelliJ platform SDK and plugin dependencies.
 * See: https://github.com/JetBrains/gradle-intellij-plugin
 */
intellij {
    // Plugin display name
    pluginName.set(prop("pluginName"))

    // IntelliJ IDEA version to build against
    version.set(prop("platformVersion"))

    // IDE type (IC = IntelliJ Community, IU = IntelliJ Ultimate)
    type.set(prop("platformType"))

    // Plugin dependencies (e.g., other plugins this plugin depends on)
    // Loaded from gradle.properties as comma-separated values
    plugins.set(
        prop("platformPlugins")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    )
}

/**
 * Changelog Plugin Configuration
 *
 * Manages version history and change notes.
 * See: https://github.com/JetBrains/gradle-changelog-plugin
 */
changelog {
    // Current plugin version
    version.set(prop("pluginVersion"))

    // Don't keep unreleased changes section
    keepUnreleasedSection.set(false)

    // No custom grouping for changes
    groups.set(emptyList())
}

/**
 * Java Toolchain Configuration
 *
 * Ensures consistent JDK version across different development environments
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(prop("jdkVersion")))
    }
}

/**
 * Task Configuration
 *
 * Customizes various build tasks for the plugin
 */
tasks {
    /**
     * Java Compilation Task
     * Sets the target JVM version for compiled classes
     */
    compileJava {
        options.release.set(prop("compatibleJdkVersion").toInt())
    }

    /**
     * Searchable Options Task
     * Disabled to speed up the build process during development
     * Enable this for production builds if searchable options are needed
     */
    buildSearchableOptions {
        enabled = false
    }

    /**
     * Gradle Wrapper Task
     * Ensures consistent Gradle version across all development environments
     */
    wrapper {
        gradleVersion = prop("gradleVersion")
    }

    /**
     * Plugin XML Patching Task
     * Updates the plugin.xml file with version info and descriptions
     */
    patchPluginXml {
        // Plugin configuration
        pluginId.set(prop("pluginGroup"))
        version.set(prop("pluginVersion"))

        // Compatible IDE version range
        sinceBuild.set(prop("pluginSinceBuild"))
        untilBuild.set(prop("pluginUntilBuild"))

        /**
         * Plugin Description
         * Extracts the description from README.md between specific markers
         * and converts it from Markdown to HTML for the plugin manifest
         */
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                // Validate that description markers exist
                if (!containsAll(listOf(start, end))) {
                    throw GradleException(
                        "Plugin description section not found in README.md:\n$start ... $end"
                    )
                }

                // Extract content between markers
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run {
                // Convert Markdown to HTML
                markdownToHTML(this)
            }
        )

        /**
         * Change Notes
         * Retrieves the latest version changes from CHANGELOG.md
         * and formats them as HTML for the plugin repository
         */
        changeNotes.set(provider {
            changelog.renderItem(
                changelog
                    .getLatest()
                    .withHeader(true)
                    .withEmptySections(false),
                Changelog.OutputType.HTML
            )
        })
    }

    /**
     * Plugin Publishing Task
     * Publishes the plugin to JetBrains Marketplace
     *
     * Requires intellijPublishToken environment variable to be set
     */
    publishPlugin {
        // Ensure changelog is updated before publishing
        dependsOn("patchChangelog")

        // Authentication token from environment variable
        token.set(System.getenv("intellijPublishToken"))

        /**
         * Release Channel Configuration
         * Determines the release channel based on version suffix:
         * - "x.y.z-beta" -> beta channel
         * - "x.y.z" -> default channel
         */
        channels.set(listOf(
            prop("pluginVersion")
                .split('-')
                .getOrNull(1)
                ?.split('.')
                ?.firstOrNull()
                ?: "default"
        ))
    }
}