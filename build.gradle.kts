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
    implementation("com.google.code.gson:gson:2.10.1")
}

// Forward the OpenAI key into the sandbox IDE so AI grouping works no matter how runIde is
// launched (terminal or the Gradle tool window). Provide it as either the OPENAI_API_KEY
// environment variable or an `openaiApiKey` Gradle property (e.g. in ~/.gradle/gradle.properties).
tasks.named<JavaExec>("runIde") {
    val openAiKey = providers.environmentVariable("OPENAI_API_KEY")
        .orElse(providers.gradleProperty("openaiApiKey"))
        .getOrElse("")
    if (openAiKey.isNotEmpty()) {
        environment("OPENAI_API_KEY", openAiKey)
    }
}
