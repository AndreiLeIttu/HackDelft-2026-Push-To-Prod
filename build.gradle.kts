plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.hackdelft.repomap"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "Repo Map"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.2")
        bundledPlugin("com.intellij.java")
    }
}
