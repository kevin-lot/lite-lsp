package me.kevinlot.lite.core

import com.intellij.lexer.EmptyLexer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PlainTextTokenTypes
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IdempotenceChecker
import com.intellij.util.IncorrectOperationException
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.ID
import java.util.concurrent.atomic.AtomicInteger

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

    private val layout = ProjectLayout(rootManifests = listOf("root.toml"), moduleManifests = listOf("module.toml"), outputDirs = setOf("dist", "out"))

    private fun failsToRename(name: PsiElement?) = assertThrows(IncorrectOperationException::class.java) { renameLeaf(name, "square") }

    private fun file(path: String): VirtualFile = myFixture.addFileToProject(path, "").virtualFile

    private fun oracle(library: Boolean) = object : LibraryOracle {
        override fun isLibrary(e: PsiElement) = library
        override fun isLibraryFile(project: Project, file: VirtualFile) = file.name == "vendored.txt"
        override fun tracker(e: PsiElement) = ModificationTracker.NEVER_CHANGED
    }

    fun `test cache dependencies of library code leave out the PSI counter`() {
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        val ofProject = LiteCache.dependencies(element, oracle(library = false))
        val ofLibrary = LiteCache.dependencies(element, oracle(library = true))
        assertEquals(3, ofProject.size)
        assertTrue(PsiModificationTracker.MODIFICATION_COUNT in ofProject)
        assertEquals(ofProject.filter { it != PsiModificationTracker.MODIFICATION_COUNT }, ofLibrary.toList())
    }

    fun `test cached value of library code survives a change of the project`() {
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        val key = Key.create<CachedValue<Int>>("test.library")
        val runs = AtomicInteger()
        fun value(): Int = LiteCache.cached(element, key, oracle(library = true)) { runs.incrementAndGet() }
        assertEquals(1, value())
        psiManager.dropPsiCaches()
        assertEquals(1, value())
    }

    fun `test cached value of project code is computed again after a change`() {
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        val key = Key.create<CachedValue<Int>>("test.project")
        val runs = AtomicInteger()
        fun value(): Int = LiteCache.cached(element, key, oracle(library = false)) { runs.incrementAndGet() }
        assertEquals(1, value())
        assertEquals(1, value())
        psiManager.dropPsiCaches()
        assertEquals(2, value())
    }

    fun `test find usages provider describes declarations only`() {
        val file = myFixture.configureByText("a.txt", "x")
        val provider = object : LiteFindUsagesProvider({ EmptyLexer() }, TokenSet.EMPTY, TokenSet.EMPTY, TokenSet.EMPTY) {
            override fun isDeclaration(element: PsiElement) = element is PsiFile
            override fun kindOf(element: PsiElement) = "document"
        }
        assertTrue(provider.canFindUsagesFor(file))
        assertFalse(provider.canFindUsagesFor(file.firstChild))
        assertEquals("document", provider.getType(file))
        assertEquals("a.txt", provider.getDescriptiveName(file))
        assertEquals("a.txt", provider.getNodeText(file, true))
        assertEquals("", provider.getDescriptiveName(file.firstChild))
        assertNull(provider.getHelpId(file))
    }

    fun `test keys index maps a file of its class to its keys`() {
        val words = object : LiteKeysIndex<PsiPlainTextFile>(PlainTextFileType.INSTANCE, PsiPlainTextFile::class.java) {
            override fun getName(): ID<String, Void> = error("not registered")
            override fun keys(file: PsiPlainTextFile) = file.text.split(' ')
        }
        val never = object : LiteKeysIndex<PsiBinaryFile>(PlainTextFileType.INSTANCE, PsiBinaryFile::class.java) {
            override fun getName(): ID<String, Void> = error("not registered")
            override fun keys(file: PsiBinaryFile) = listOf("unreachable")
        }
        val content = FileContentImpl.createByFile(myFixture.addFileToProject("a.txt", "circle square circle").virtualFile, project)
        assertEquals(setOf("circle", "square"), words.indexer.map(content).keys)
        assertEmpty(never.indexer.map(content).keys)
        assertTrue(words.inputFilter.acceptInput(content.file))
        assertTrue(words.dependsOnFileContent())
    }

    fun `test leaf is renamed in place, and a missing name is an error`() {
        val file = myFixture.configureByText("a.txt", "circle")
        assertTrue(file.firstChild.isA(PlainTextTokenTypes.PLAIN_TEXT))
        assertFalse(file.isA(PlainTextTokenTypes.PLAIN_TEXT))
        assertFalse(null.isA(PlainTextTokenTypes.PLAIN_TEXT))
        WriteCommandAction.runWriteCommandAction(project) { renameLeaf(file.firstChild, "square") }
        assertEquals("square", file.text)
        failsToRename(null)
        failsToRename(file)
    }

    fun `test local use scope is the container alone`() {
        val file = myFixture.configureByText("a.txt", "x")
        assertNull(localUseScope(null))
        assertEquals(LocalSearchScope(file), localUseScope(file))
    }

    fun `test lookup scope of an element is its project, plus what the oracle calls a library`() {
        file("ws/root.toml")
        val element = myFixture.addFileToProject("ws/a.txt", "x")
        val scope = layout.scopeOf(element, Key.create("test.scope"), oracle(library = false))
        assertEquals(LiteProjectScope.create(project, layout, element.virtualFile), scope)
        assertTrue(scope.contains(element.virtualFile))
        assertTrue(scope.contains(file("other/vendored.txt")))
        assertFalse(scope.contains(file("other/b.txt")))
        assertFalse(layout.scopeOf(element, Key.create("test.scope.plain")).contains(file("third/vendored.txt")))
    }

    fun `test names validator without escaping`() {
        val v = LiteNamesValidator(setOf("fun"))
        assertTrue(v.isIdentifier("_private", null))
        assertTrue(v.isIdentifier("café2", null))
        assertFalse(v.isIdentifier("", null))
        assertFalse(v.isIdentifier("a-b", null))
        assertFalse(v.isIdentifier("a b", null))
        assertFalse(v.isIdentifier("`fun`", null))
        assertFalse(v.isKeyword("Fun", null))
    }

    fun `test project root is the nearest root manifest, before any module manifest`() {
        file("ws/root.toml")
        file("ws/sub/module.toml")
        file("ws/inner/root.toml")
        val inner = file("ws/inner/b.txt")
        assertEquals("/src/ws", layout.rootOf(project, file("ws/sub/deep/a.txt")))
        assertEquals("/src/ws/inner", layout.rootOf(project, inner))
        assertEquals("/src/ws/inner", layout.rootOf(project, inner.parent))
        assertNull(layout.rootOf(project, null))
    }

    fun `test project root without a root manifest is the module, then the highest marked folder, then the content root`() {
        file("mod/module.toml")
        myFixture.tempDirFixture.findOrCreateDir("repo/.git")
        assertEquals("/src/mod", layout.rootOf(project, file("mod/deep/a.txt")))
        assertEquals("/src/marked", layout.rootOf(project, file("marked/src/pkg/src/b.txt")))
        assertEquals("/src/repo", layout.rootOf(project, file("repo/lib/c.txt")))
        assertEquals("/src", layout.rootOf(project, file("plain/d.txt")))
    }

    fun `test project scope keeps the project, minus its outputs, plus the libraries`() {
        file("ws/root.toml")
        val scope = LiteProjectScope.create(project, layout, file("ws/a.txt")) { it.name == "vendored.txt" }
        assertTrue(scope.contains(file("ws/lib/b.txt")))
        assertFalse(scope.contains(file("ws/out/c.txt")))
        assertFalse(scope.contains(file("ws/lib/dist/d.txt")))
        assertFalse(scope.contains(file("other/e.txt")))
        assertFalse(scope.contains(file("wsx/f.txt")))
        assertTrue(scope.contains(file("other/vendored.txt")))
        assertTrue(scope.isSearchInLibraries)
    }

    fun `test project scope of a file inside an output dir also sees that dir`() {
        file("ws/root.toml")
        val scope = LiteProjectScope.create(project, layout, file("ws/out/gen/a.txt"))
        assertTrue(scope.contains(file("ws/out/b.txt")))
        assertTrue(scope.contains(file("ws/c.txt")))
        assertFalse(scope.contains(file("ws/dist/d.txt")))
    }

    fun `test project scopes of one project are equal`() {
        file("ws/root.toml")
        file("other/root.toml")
        val scope: GlobalSearchScope = LiteProjectScope.create(project, layout, file("ws/a.txt"))
        val same = LiteProjectScope.create(project, layout, file("ws/deep/b.txt"))
        assertEquals(scope, same)
        assertEquals(scope.hashCode(), same.hashCode())
        assertFalse(scope == LiteProjectScope.create(project, layout, file("other/c.txt")))
        assertFalse(scope == LiteProjectScope.create(project, layout, file("ws/out/gen/d.txt")))
        assertEquals("Lite project /src/ws", scope.toString())
    }

    fun `test recursion guard is released after a failure and refuses what is too deep`() {
        val guard = RecursionGuard(maxDepth = 1)
        assertThrows(IllegalStateException::class.java) { guard.run(0) { error("failed") } }
        assertEquals(1, guard.run(0) { 1 })
        assertEquals(-1, guard.run(0) { guard.run(-1) { 1 } })
        assertTrue(guard.enter())
        assertFalse(guard.enter())
        guard.exit()
    }

    fun `test reference has the range of its name, distinct targets and the first of them as result`() {
        val file = myFixture.configureByText("a.txt", "circle")
        fun reference(name: PsiElement?, vararg targets: PsiElement) = object : LiteReference<PsiFile>(file, { name }) {
            override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? = null
            override fun resolveTargets() = targets.toList()
        }
        val several = reference(file.firstChild, file, file.firstChild, file)
        assertEquals(file.firstChild.textRange, several.rangeInElement)
        assertEquals(listOf(file, file.firstChild), several.multiResolve(false).map { it.element })
        assertSame(file, several.resolve())
        assertSame(file, several.handleElementRename("square"))
        assertEquals("circle", file.text)
        val nameless = reference(null)
        assertTrue(nameless.rangeInElement.isEmpty)
        assertNull(nameless.resolve())
        assertSame(file, nameless.handleElementRename("square"))
        assertEmpty(nameless.variants)
    }

    fun `test resolve cache is dropped on a PSI change`() {
        val element: PsiElement = myFixture.configureByText("a.txt", "x")
        val reference = object : PsiReferenceBase<PsiElement>(element) { override fun resolve(): PsiElement? = null }
        val runs = AtomicInteger()
        val found = arrayOf<ResolveResult>(PsiElementResolveResult(element))
        fun resolve() = LiteResolveCache.resolve(project, reference) { found.copyOf().also { runs.incrementAndGet() } }
        assertSame(resolve(), resolve())
        assertEquals(1, runs.get())
        psiManager.dropPsiCaches()
        assertSame(element, resolve().single().element)
        assertEquals(2, runs.get())
    }
}
