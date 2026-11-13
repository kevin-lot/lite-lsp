plugins { id("lite.module") }

dependencies { implementation(project(":core")) }

// src/upstream/java: lexer + parser imported from intellij-community (see src/upstream/README.md).
sourceSets.main { java.srcDir("src/upstream/java"); kotlin.srcDir("src/upstream/java") }
