fun prop(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = prop("pluginGroup")
version = prop("pluginVersion")

repositories {
    mavenCentral()
}

intellij {
    pluginName.set(prop("pluginName"))
    version.set(prop("platformVersion"))
    type.set(prop("platformType"))
    plugins.set(
        prop("platformPlugins")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(prop("jdkVersion")))
    }
}

tasks {
    compileJava {
        options.release.set(prop("compatibleJdkVersion").toInt())
    }

    buildSearchableOptions {
        enabled = false
    }

    wrapper {
        gradleVersion = prop("gradleVersion")
    }

    patchPluginXml {
        pluginId.set(prop("pluginGroup"))
        version.set(prop("pluginVersion"))
        sinceBuild.set(prop("pluginSinceBuild"))
        untilBuild.set(prop("pluginUntilBuild"))
    }

    publishPlugin {
        token.set(System.getenv("intellijPublishToken"))
    }
}