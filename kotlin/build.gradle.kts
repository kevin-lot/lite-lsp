import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask

plugins {
    id("lite.module")
    id("org.jetbrains.intellij.platform.grammarkit")
}

dependencies {
    implementation(project(":core"))
    // Kotlin code navigates into Java classes (JDK, Java sources) through the Java PSI.
    intellijPlatform {
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Plugin.Java) // mock JDK for tests
    }
}

val lexerDir = layout.buildDirectory.dir("generated/lexer")

// Lexer from the Kotlin compiler's Kotlin.flex (see tools/import_kotlin_parser.py); the parser is hand-written Java.
val generateKotlinLexer by tasks.registering(GenerateLexerTask::class) {
    sourceFile = file("src/main/grammars/Kotlin.flex")
    targetRootOutputDir = lexerDir
    packageName = "me.kevinlot.lite.kotlin.upstream.lexer"
    purgeOldFiles = true
}

// src/upstream/java: parser imported from the Kotlin compiler (see src/upstream/README.md).
sourceSets.main { java.srcDirs("src/upstream/java", lexerDir) }
tasks.compileKotlin { dependsOn(generateKotlinLexer) }
tasks.compileJava { dependsOn(generateKotlinLexer) }
