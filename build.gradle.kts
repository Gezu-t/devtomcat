import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun prop(key: String): String = project.findProperty(key)?.toString()
    ?: error("Missing required property: $key")

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.11.0"
    // Source the Marketplace <change-notes> from CHANGELOG.md at build time so
    // the published plugin always reflects the actual release notes. Without
    // this, the <change-notes> in plugin.xml drifts from CHANGELOG.md and a
    // forgotten manual copy ships stale content (which is what happened in
    // 1.0.9 before the plugin was wired up).
    id("org.jetbrains.changelog") version "2.2.1"
}

group = prop("pluginGroup")
version = prop("pluginVersion")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(prop("platformType"), prop("platformVersion"))
        bundledPlugins(
            prop("platformPlugins")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        )
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(prop("jdkVersion")))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = prop("pluginName")
        id = prop("pluginGroup")
        version = prop("pluginVersion")
        ideaVersion {
            sinceBuild = prop("pluginSinceBuild")
            untilBuild = prop("pluginUntilBuild")
        }
        // Inject <change-notes> from CHANGELOG.md's section matching the
        // current pluginVersion. Falls back to [Unreleased] when no exact
        // match exists (useful between releases) and finally to a generic
        // "see CHANGELOG.md" fallback if neither is present, so a missing
        // CHANGELOG entry is reported clearly rather than failing the build.
        changeNotes = provider {
            // try/catch wraps the whole chain because both getOrNull-then-
            // getUnreleased and renderItem can throw (missing version, missing
            // [Unreleased], malformed markdown). A failed render must never
            // fail the build; degrade to a static fallback instead.
            try {
                val version = prop("pluginVersion")
                val item = changelog.getOrNull(version) ?: changelog.getUnreleased()
                changelog.renderItem(item, org.jetbrains.changelog.Changelog.OutputType.HTML)
            } catch (e: Exception) {
                "<p>See <code>CHANGELOG.md</code> in the plugin source for release details.</p>"
            }
        }
    }

    pluginVerification {
        ides {
            // recommended() pulls seven IDE builds (~35GB of DMG downloads) which
            // exceeds available disk on developer laptops. Verify against a
            // tighter, meaningful set: our exact build target (pinned in
            // gradle.properties as platformVersion) and the upper bound of our
            // pluginUntilBuild claim so we catch API drift before the
            // Marketplace does. If you're running in CI with plenty of disk,
            // swap this for `recommended()`.
            create(IntelliJPlatformType.IntellijIdeaCommunity, prop("platformVersion"))
            // IntelliJ IDEA Community was merged into the unified IDEA product at
            // 2025.3 (build 253), so the upper-bound target uses the unified type.
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
}

// CHANGELOG.md → Marketplace <change-notes> wiring. Configures the
// org.jetbrains.changelog plugin to recognise the project's keepachangelog
// shape (## [VERSION], ### Subsection, - bullets). The 'groups' list is
// intentionally left empty so any custom subsection names in CHANGELOG.md
// (e.g. "Fixed (data loss)", "Diagnostics", "Tests") are rendered verbatim
// rather than being normalised into a fixed taxonomy by patchChangelog.
changelog {
    version.set(prop("pluginVersion"))
    path.set(file("CHANGELOG.md").path)
    header.set(provider { "[${version.get()}]" })
    headerParserRegex.set("""(\d+\.\d+(?:\.\d+)*)""".toRegex())
    itemPrefix.set("-")
    keepUnreleasedSection.set(true)
    unreleasedTerm.set("[Unreleased]")
    groups.set(emptyList())
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(prop("compatibleJdkVersion").toInt())
    }

    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }

    jarSearchableOptions {
        enabled = false
    }

    wrapper {
        gradleVersion = prop("gradleVersion")
    }

    publishPlugin {
        token.set(System.getenv("intellijPublishToken"))
    }
}

tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
    doFirst {
        delete(
            fileTree(layout.buildDirectory.dir("idea-sandbox")) {
                include("**/config/app-internal-state.db")
            }
        )
    }
    systemProperty("idea.diagnostic.opentelemetry.file", "false")
    // Internal-mode: stacktraces on Disposable parentage warnings during sandbox runs.
    systemProperty("idea.is.internal", "true")
}
