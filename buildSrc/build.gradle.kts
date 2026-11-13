plugins { `kotlin-dsl` }
repositories { gradlePluginPortal(); mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:2.20.0")
}
