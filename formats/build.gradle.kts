plugins { id("lite.module") }

dependencies {
    implementation(project(":core"))
    // HTML `<script>`/`<style>`: XML PSI (optional at run time, see lite-html.xml)
    intellijPlatform { bundledPlugin("com.intellij.xml") }
}
