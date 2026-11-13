package me.kevinlot.lite.core

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LiteCoreTest : BasePlatformTestCase() {

    fun `test names validator`() {
        val v = object : LiteNamesValidator(setOf("fun", "val")) {
            override fun unescape(name: String) = name.removeSurrounding("`")
            override fun isEscaped(name: String) = name.startsWith("`")
        }
        assertTrue(v.isIdentifier("hello_1", null))
        assertFalse(v.isIdentifier("1abc", null))
        assertFalse(v.isIdentifier("fun", null))
        assertTrue(v.isIdentifier("`fun`", null))
        assertFalse(v.isIdentifier("_", null))
        assertTrue(v.isKeyword("val", null))
    }

    fun `test recursion guard limits depth`() {
        val guard = RecursionGuard(maxDepth = 3)
        fun dive(n: Int): Int = guard.run(n) { dive(n + 1) }
        assertEquals(3, dive(0))
    }

    /** A computation that needs its own cached result gets the fallback instead of re-entering the cache. */
    fun `test cyclic cached computation returns fallback`() {
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        val oracle = object : LibraryOracle {
            override fun isLibrary(e: PsiElement) = false
            override fun tracker(e: PsiElement) = ModificationTracker.NEVER_CHANGED
        }
        val key = Key.create<CachedValue<Int>>("test.cycle")
        fun compute(): Int = LiteCache.cachedNonRecursive(element, key, oracle, onCycle = -1) { compute() + 1 }
        assertEquals(0, compute())
    }
}
