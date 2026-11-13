plugins { id("lite.module") }

dependencies {
    implementation(project(":core"))
    // HTML `<script>`/`<style>`: XML PSI (optional at run time, see lite-html.xml)
    // `$ref` in JSON files (OpenAPI, JSON Schema): JSON PSI (optional at run time, see lite-json.xml)
    intellijPlatform { bundledPlugin("com.intellij.xml"); bundledPlugin("com.intellij.modules.json") }
}
