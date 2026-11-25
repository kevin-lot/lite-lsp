package me.kevinlot.lite.formats

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RainbowTest : BasePlatformTestCase() {
    fun `test nested toml brackets`() {
        myFixture.configureByText("a.toml", "a = [{ b = [1] }]")
        val colored = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("LITE_RAINBOW_") == true }
            .sortedBy { it.startOffset }
            .joinToString(" ") { it.text + it.forcedTextAttributesKey.externalName.removePrefix("LITE_RAINBOW_") }
        assertEquals("[1 {2 [3 ]3 }2 ]1", colored)
    }
}
