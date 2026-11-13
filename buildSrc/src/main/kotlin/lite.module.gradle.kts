// Convention for every module (core and languages): Kotlin on the local IDE, platform test framework.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("localIdePath"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin { jvmToolchain(25) }

tasks.test {
    // LITE_JFR=<file.jfr>: profile the test JVM with Java Flight Recorder.
    providers.environmentVariable("LITE_JFR").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    // Optional inputs of the "real world" tests (skipped when absent).
    for (name in listOf("RUST_SRC_LIBRARY", "LITE_REAL_MAIN", "LITE_REAL_METADATA", "LITE_KOTLIN_STDLIB", "LITE_KOTLIN_CORPUS", "LITE_PYTHON_CORPUS", "LITE_PYTHON_PROJECT", "LITE_PYTHON_STDLIB", "LITE_TS_LIB", "LITE_TS_CORPUS", "LITE_TS_PROJECT", "LITE_SHOW", "LITE_QML_CORPUS", "LITE_QML_PROJECT", "LITE_SH_CORPUS", "LITE_MD_CORPUS", "LITE_FMT_CORPUS", "LITE_PROTO_CORPUS", "LITE_LUA_CORPUS")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
