import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
    id("lite.module")
    id("org.jetbrains.intellij.platform.grammarkit")
}

dependencies { implementation(project(":core")) }

val lexerDir = layout.buildDirectory.dir("generated/lexer")
val parserDir = layout.buildDirectory.dir("generated/parser")

val generateRustLexer by tasks.registering(GenerateLexerTask::class) {
    sourceFile = file("src/main/grammars/RustLexer.flex")
    targetRootOutputDir = lexerDir
    packageName = "me.kevinlot.lite.rust.lexer"
    purgeOldFiles = true
}

val generateRustParser by tasks.registering(GenerateParserTask::class) {
    sourceFile = file("src/main/grammars/RustParser.bnf")
    targetRootOutputDir = parserDir
    pathToParser = "me/kevinlot/lite/rust/parser/RustParser.java"
    pathToPsiRoot = "me/kevinlot/lite/rust/psi"
    purgeOldFiles = true
}

sourceSets.main { java.srcDirs(lexerDir, parserDir) }
tasks.compileKotlin { dependsOn(generateRustLexer, generateRustParser) }
tasks.compileJava { dependsOn(generateRustLexer, generateRustParser) }
