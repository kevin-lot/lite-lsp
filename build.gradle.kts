// Root project: assembles the plugin from the modules (one jar, one descriptor).
// Plugin versions live in buildSrc/build.gradle.kts (shared with the module convention).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

allprojects {
    group = "me.kevinlot"
    version = "0.45.0"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // The IntelliJ IDEA the plugin compiles and is tested against: `platformVersion`, downloaded once and cached by Gradle.
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        pluginComposedModule(implementation(project(":core")))
        pluginComposedModule(implementation(project(":rust")))
        pluginComposedModule(implementation(project(":kotlin")))
        pluginComposedModule(implementation(project(":python")))
        pluginComposedModule(implementation(project(":typescript")))
        pluginComposedModule(implementation(project(":qml")))
        pluginComposedModule(implementation(project(":shell")))
        pluginComposedModule(implementation(project(":lua")))
        pluginComposedModule(implementation(project(":groovy")))
        pluginComposedModule(implementation(project(":scala")))
        pluginComposedModule(implementation(project(":dart")))
        pluginComposedModule(implementation(project(":markdown")))
        pluginComposedModule(implementation(project(":formats")))
        pluginComposedModule(implementation(project(":protobuf")))
        pluginComposedModule(implementation(project(":terminal")))
    }
}

kotlin { jvmToolchain(25) }

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = providers.gradleProperty("instrumentCode").map(String::toBoolean).orElse(true)
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}
