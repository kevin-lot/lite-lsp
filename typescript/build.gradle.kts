plugins { id("lite.module") }

dependencies {
    implementation(project(":core"))
    // Angular templates: XML PSI (optional at run time, see lite-angular.xml)
    intellijPlatform { bundledPlugin("com.intellij.xml") }
}
