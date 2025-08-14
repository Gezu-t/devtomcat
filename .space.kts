/**
 * Dev Tomcat Plugin - JetBrains Space Automation Configuration
 *
 * This Kotlin script defines the CI/CD pipeline for the Dev Tomcat plugin
 * using JetBrains Space Automation.
 *
 * For more information about Space Automation, see:
 * https://www.jetbrains.com/help/space/automation.html
 */

/**
 * Main Build Job
 *
 * Compiles the plugin and runs all tests to ensure code quality
 * before merging or releasing.
 */
job("Build and run tests") {
   // Use OpenJDK 11 for compatibility with IntelliJ Platform
   gradlew("openjdk:11", "assemble", "test")
}

/**
 * Plugin Verification Job
 *
 * Runs additional verification tasks to ensure the plugin
 * meets JetBrains Marketplace requirements.
 */
job("Verify plugin") {
   // Trigger only on main branch or release tags
   startOn {
      gitPush {
         anyBranchMatching {
            +"main"
            +"release/*"
         }
      }
   }

   gradlew("openjdk:11", "runPluginVerifier")
}

/**
 * Release Build Job
 *
 * Builds the final plugin distribution for release.
 * Only runs on version tags (e.g., v1.0.0).
 */
job("Build release") {
   // Trigger on version tags only
   startOn {
      gitPush {
         anyTagMatching {
            +"v*"
         }
      }
   }

   container("openjdk:11") {
      kotlinScript { api ->
         // Build the plugin
         api.gradlew("buildPlugin")

         // Archive the distribution
         api.fileArtifacts(
            localPath = "build/distributions/*.zip",
            remotePath = "releases",
            archive = true
         )
      }
   }
}