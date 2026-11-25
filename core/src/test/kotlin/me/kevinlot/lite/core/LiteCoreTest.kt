package me.kevinlot.lite.core

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
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

    /** A reference met again while it resolves gets nothing there; what was computed above that cut is computed again later. */
    fun `test cyclic resolution is cut and only kept below the cut`() {
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        fun reference() = object : PsiReferenceBase<PsiElement>(element) { override fun resolve(): PsiElement? = null }
        val a = reference()
        val b = reference()
        val found = arrayOf<ResolveResult>(PsiElementResolveResult(element))
        val runs = mutableListOf<String>()
        fun resolveB(): Array<ResolveResult> = LiteResolveCache.resolve(project, b) {
            runs += "b"
            LiteResolveCache.resolve(project, a) { error("a is already resolving or known") }.ifEmpty { found }
        }
        fun resolveA(): Array<ResolveResult> = LiteResolveCache.resolve(project, a) { runs += "a"; resolveB() }
        assertSame(found, resolveA())
        assertSame(found, resolveA())
        assertEquals(listOf("a", "b"), runs)
        assertSame(found, resolveB())
        assertSame(found, resolveB())
        assertEquals(listOf("a", "b", "b"), runs)
    }

    fun `test first lookup that finds something wins`() {
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        assertEquals(listOf(element), firstFound({ emptyList() }, { listOf(element) }, { error("not reached") }))
        assertEmpty(firstFound({ emptyList() }))
    }

    fun `test library roots change is published where the model may change`() {
        val seen = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            com.intellij.openapi.roots.AdditionalLibraryRootsListener.TOPIC,
            com.intellij.openapi.roots.AdditionalLibraryRootsListener { name, _, _, _ -> seen += name.orEmpty() },
        )
        LiteLibraryRoots.changed(project, "Lite", emptyList(), listOf(myFixture.tempDirFixture.findOrCreateDir("lib")), "lite-test")
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf("Lite"), seen)
        val guard = com.intellij.openapi.application.TransactionGuard.getInstance()
        assertTrue(guard.isWriteSafeModality(LiteLibraryRoots.modality))
        assertFalse(guard.isWriteSafeModality(com.intellij.openapi.application.ModalityState.any()))
    }
}
