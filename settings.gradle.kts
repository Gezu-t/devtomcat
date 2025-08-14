/**
 * Dev Tomcat Plugin - Settings Configuration
 *
 * This file configures the project name and plugin management repositories
 * for the Dev Tomcat IntelliJ IDEA plugin.
 */

// Define the root project name
rootProject.name = "DevTomcat"

/**
 * Plugin Management Configuration
 *
 * Configures repositories for Gradle plugins used in the build process.
 * The order matters - repositories are checked in the order they're listed.
 */
pluginManagement {
    repositories {
        // Primary: Maven Central for most plugins
        mavenCentral()

        // Gradle's official plugin portal
        gradlePluginPortal()

        // Aliyun mirror for Chinese users (improves download speed)
        // Remove this if not needed for your location
        maven("https://maven.aliyun.com/nexus/content/repositories/gradle-plugin")
    }
}