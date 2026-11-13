plugins { id("lite.module") }

// QML embeds JavaScript: expressions, statements and functions are parsed and resolved by the typescript module.
dependencies {
    implementation(project(":core"))
    implementation(project(":typescript"))
}
