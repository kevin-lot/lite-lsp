import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
    id("lite.module")
    id("org.jetbrains.intellij.platform.grammarkit")
}

dependencies {
    implementation(project(":core"))
    // tests only: `.arb` files are read by the formats module, found by file type at run time
    testRuntimeOnly(project(":formats"))
}

// src/main/grammars + src/upstream/java: imported from the official Dart plugin (see src/upstream/README.md).
val lexerDir = layout.buildDirectory.dir("generated/lexer")
val parserDir = layout.buildDirectory.dir("generated/parser")

val generateDartLexer by tasks.registering(GenerateLexerTask::class) {
    sourceFile = file("src/main/grammars/Dart.flex")
    targetRootOutputDir = lexerDir
    packageName = "me.kevinlot.lite.dart.upstream"
    purgeOldFiles = true
}

val generateDartParser by tasks.registering(GenerateParserTask::class) {
    sourceFile = file("src/main/grammars/Dart.bnf")
    targetRootOutputDir = parserDir
    pathToParser = "me/kevinlot/lite/dart/upstream/DartParser.java"
    pathToPsiRoot = "me/kevinlot/lite/dart/upstream/psi"
    purgeOldFiles = true
    // DartTokenTypes.java lands beside the parser, outside the declared outputs: a cached run would restore DartParser.java alone
    outputs.cacheIf { false }
}

sourceSets.main { java.srcDirs(lexerDir, parserDir, "src/upstream/java") }
tasks.compileKotlin { dependsOn(generateDartLexer, generateDartParser) }
tasks.compileJava { dependsOn(generateDartLexer, generateDartParser) }
