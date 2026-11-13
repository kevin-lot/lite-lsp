import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
    id("lite.module")
    id("org.jetbrains.intellij.platform.grammarkit")
}

dependencies { implementation(project(":core")) }

// src/main/grammars + src/upstream/java: imported from JetBrains' protoeditor (see src/upstream/README.md).
val lexerDir = layout.buildDirectory.dir("generated/lexer")
val parserDir = layout.buildDirectory.dir("generated/parser")

val generateProtoLexer by tasks.registering(GenerateLexerTask::class) {
    sourceFile = file("src/main/grammars/Proto.flex")
    targetRootOutputDir = lexerDir
    packageName = "me.kevinlot.lite.proto.lexer"
    purgeOldFiles = true
}

val generateProtoParser by tasks.registering(GenerateParserTask::class) {
    sourceFile = file("src/main/grammars/Proto.bnf")
    targetRootOutputDir = parserDir
    pathToParser = "me/kevinlot/lite/proto/parser/PbParser.java"
    pathToPsiRoot = "me/kevinlot/lite/proto/psi"
    purgeOldFiles = true
}

sourceSets.main { java.srcDirs(lexerDir, parserDir, "src/upstream/java") }
tasks.compileKotlin { dependsOn(generateProtoLexer, generateProtoParser) }
tasks.compileJava { dependsOn(generateProtoLexer, generateProtoParser) }
