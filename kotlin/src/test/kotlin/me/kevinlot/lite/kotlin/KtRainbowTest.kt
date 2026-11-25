package me.kevinlot.lite.kotlin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KtRainbowTest : BasePlatformTestCase() {
    private fun rainbow(name: String, text: String): String {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("LITE_RAINBOW_") == true }
            .sortedBy { it.startOffset }
            .joinToString(" ") { it.text + it.forcedTextAttributesKey.externalName.removePrefix("LITE_RAINBOW_") }
    }

    fun `test brackets are colored by depth`() =
        assertEquals("(1 )1 {1 (2 [3 ]3 )2 }1", rainbow("a.kt", "fun f() { g(a[0]) }"))

    fun `test brackets in strings and comments keep their color`() =
        assertEquals("(1 )1", rainbow("a.kt", "fun f() = \"([{\" // ([{"))

    fun `test colors cycle and a stray closer does not shift the rest`() =
        assertEquals("(1 (2 (3 (4 (5 (1 )1 )5 )4 )3 )2 )1 )1 (1 )1", rainbow("a.kt", "val x = ((((((1)))))) ) + (2)"))

    fun `test other languages are left alone`() = assertEquals("", rainbow("A.java", "class A { void f() {} }"))
}
