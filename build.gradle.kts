import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun prop(key: String): String = project.findProperty(key)?.toString()
    ?: error("Missing required property: $key")

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.11.0"
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
    }

    pluginVerification {
        ides {
            // recommended() pulls seven IDE builds (~35GB of DMG downloads) which
            // exceeds available disk on developer laptops. Verify against a
            // tighter, meaningful set: our exact build target (pinned in
            // gradle.properties as platformVersion) plus one newer stable so
            // until-build compatibility is still covered. If you're running in
            // CI with plenty of disk, swap this for `recommended()`.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, prop("platformVersion"))
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7")
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
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
}
