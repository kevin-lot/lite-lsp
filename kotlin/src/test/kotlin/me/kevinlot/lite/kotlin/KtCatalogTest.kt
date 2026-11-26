package me.kevinlot.lite.kotlin

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import me.kevinlot.lite.kotlin.resolve.KtCatalog

class KtCatalogTest : BasePlatformTestCase() {
    private val catalog = """
        [versions]
        kotlin = "2.1.0"
        junit-jupiter = "5.11.0"

        [libraries]
        kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
        junit_api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit-jupiter" }
        junit-engine.module = "org.junit.jupiter:junit-jupiter-engine"
        "okio" = "com.squareup.okio:okio:3.9.0"

        [bundles]
        testing = ["junit_api", "junit-engine"]

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """.trimIndent()

    private fun at(key: String) = listOf(catalog.indexOf(key))

    private fun entry(vararg path: String): List<Int>? = KtCatalog.entries(myFixture.addFileToProject("gradle/libs.versions.toml", catalog))[path.toList()]

    fun `test entry of a library`() = assertEquals(at("kotlin-stdlib"), entry("kotlin", "stdlib"))
    fun `test entry written with an underscore`() = assertEquals(at("junit_api"), entry("junit", "api"))
    fun `test entry with a dotted key`() = assertEquals(at("junit-engine"), entry("junit", "engine"))
    fun `test entry with a quoted key`() = assertEquals(at("okio\""), entry("okio"))
    fun `test entry of a version`() = assertEquals(at("junit-jupiter"), entry("versions", "junit", "jupiter"))
    fun `test entry of a plugin`() = assertEquals(at("kotlin-jvm"), entry("plugins", "kotlin", "jvm"))
    fun `test entry of a bundle`() = assertEquals(at("testing"), entry("bundles", "testing"))
    fun `test a value is no entry`() = assertNull(entry("module"))

    /** The targets of the reference at `<caret>`; the catalog sits in `gradle/`, beside the script or above it. */
    private fun resolved(script: String, path: String = "build.gradle.kts"): Int {
        myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, script.replace("<caret>", "")).virtualFile)
        val targets = (myFixture.file.findReferenceAt(script.indexOf("<caret>")) as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
        return targets.count(KtCatalog::isEntry)
    }

    fun `test library`() = assertEquals(1, resolved("dependencies { implementation(libs.kotlin.<caret>stdlib) }"))
    fun `test version`() = assertEquals(1, resolved("val v = libs.versions.junit.<caret>jupiter.get()"))
    fun `test plugin`() = assertEquals(1, resolved("plugins { alias(libs.plugins.kotlin.<caret>jvm) }"))
    fun `test bundle`() = assertEquals(1, resolved("dependencies { testImplementation(libs.bundles.<caret>testing) }"))
    fun `test script of a subproject`() = assertEquals(1, resolved("dependencies { implementation(libs.kotlin.<caret>stdlib) }", "app/build.gradle.kts"))
    fun `test inner segment names the entries under it`() = assertEquals(2, resolved("val x = libs.<caret>junit.api"))
    fun `test section segment names nothing`() = assertEquals(0, resolved("val x = libs.<caret>versions.kotlin"))
    fun `test unknown entry`() = assertEquals(0, resolved("val x = libs.kotlin.<caret>reflect"))
    fun `test call after an accessor is no entry`() = assertEquals(0, resolved("val x = libs.versions.kotlin.<caret>get()"))
    fun `test a source file has no accessor`() = assertEquals(0, resolved("val x = libs.kotlin.<caret>stdlib", "src/Main.kt"))

    fun `test renamed segment`() {
        myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
        val script = "val a = libs.kotlin.stdlib"
        val ref = myFixture.configureByText("build.gradle.kts", script).findReferenceAt(script.indexOf("stdlib"))?.element as KtNameReferenceExpression
        assertEquals("core", KtCatalog.renamed(ref, "kotlin-core"))
        assertNull(KtCatalog.renamed(ref, "kt-core"))
        assertNull(KtCatalog.renamed(ref, "kotlin-std-lib"))
    }
}
