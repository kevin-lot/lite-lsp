package me.kevinlot.lite.typescript

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsReferenceExpr
import me.kevinlot.lite.typescript.resolve.TsResolver

class TsAngularTest : BasePlatformTestCase() {
    private val component = """
        interface Item { id: number; name: string; }
        @Component({ selector: 'app-list', templateUrl: './list.component.html' })
        export class ListComponent {
            title = 'Items';
            items: Item[] = [];
            busy = false;
            when = new Date();
            save(item?: Item): void {}
        }
    """.trimIndent()

    private val manager get() = InjectedLanguageManager.getInstance(project)

    private fun template(html: String, source: String = component): PsiFile {
        myFixture.addFileToProject("list.component.ts", source)
        return myFixture.configureByText("list.component.html", html)
    }

    private fun fragments(file: PsiFile): List<PsiFile> {
        val found = LinkedHashSet<PsiFile>()
        file.viewProvider.allFiles.forEach { root ->
            PsiTreeUtil.processElements(root) { e -> if (e is PsiLanguageInjectionHost) manager.enumerate(e) { injected, _ -> found += injected }; true }
        }
        return found.sortedBy { manager.injectedToHost(it, it.textLength / 2) }
    }

    private fun fragmentTexts(html: String) = fragments(template(html)).map { it.text.trim() }

    /** Where `<ref>` of [html] points: (file name, offset in that file or in its host, text of the target). */
    private fun target(html: String, source: String = component): Triple<String, Int, String>? {
        val at = html.indexOf("<ref>")
        val file = template(html.replace("<ref>", ""), source)
        val leaf = file.viewProvider.allFiles.firstNotNullOfOrNull { manager.findInjectedElementAt(it, at) } ?: error("nothing injected at <ref>")
        val ref = generateSequence(leaf) { it.parent }.firstNotNullOfOrNull { it.reference as? PsiPolyVariantReference } ?: error("no reference at <ref>")
        val found = ref.multiResolve(false).firstNotNullOfOrNull { it.element } ?: return null
        val name: PsiElement = (found as? PsiNameIdentifierOwner)?.nameIdentifier ?: found
        return Triple(manager.getTopLevelFile(found).name, manager.injectedToHost(name, name.textOffset), found.text)
    }

    private fun member(html: String, name: String) {
        val found = target(html) ?: error("unresolved")
        assertEquals("list.component.ts", found.first)
        assertEquals(component.indexOf(name), found.second)
    }

    private fun variable(html: String, declaration: String) {
        val found = target(html) ?: error("unresolved")
        assertEquals("list.component.html", found.first)
        assertEquals(html.replace("<ref>", "").indexOf(declaration), found.second)
    }

    fun `test an interpolation is an expression`() = assertEquals(listOf("( title );"), fragmentTexts("<h1>{{ title }}</h1>"))
    fun `test a pipe is a call`() = assertEquals(listOf("( when | date( 'short' , zone )| uppercase );"), fragmentTexts("<p>{{ when | date: 'short' : zone | uppercase }}</p>"))
    fun `test a ternary keeps its colon`() = assertEquals(listOf("( busy ? 'a:b' : title || 'x' );"), fragmentTexts("<p>{{ busy ? 'a:b' : title || 'x' }}</p>"))
    fun `test a ternary in a pipe argument`() = assertEquals(listOf("( when | date( busy ? 'a' : 'b' ));"), fragmentTexts("<p>{{ when | date: busy ? 'a' : 'b' }}</p>"))
    fun `test a pipe in parentheses`() = assertEquals(listOf("( busy ? (when | date( 'short')) : (title | as( Item)).name );"), fragmentTexts("<p>{{ busy ? (when | date: 'short') : (title | as: Item).name }}</p>"))
    fun `test a handler named like a keyword`() = assertEquals(listOf("this.delete.emit()"), fragmentTexts("<a (click)=\"delete.emit()\"></a>"))
    fun `test pipes in an array and an object`() = assertEquals(
        listOf("([title | upper, when | date( 'short'), busy]);", "({ a: title | cut( 3), b: busy });"),
        fragmentTexts("<a [x]=\"[title | upper, when | date: 'short', busy]\" [y]=\"{ a: title | cut: 3, b: busy }\"></a>"),
    )
    fun `test a member named like a keyword`() = assertEquals(listOf("(this. class );", "(this.case().title);"), fragmentTexts("<p [a]=\"case().title\">{{ class }}</p>").sorted())
    fun `test defer triggers`() = assertEquals(listOf("(busy && title);"), fragmentTexts("@defer (on viewport; when busy && title) { <a></a> } @placeholder (minimum 1s) { x } @loading (after 100ms; minimum 1s) { y }"))
    fun `test bindings`() = assertEquals(
        listOf("(busy || !items.length);", "save(); busy = true", "(title);", "({ on: busy, 'is-off': !busy });"),
        fragmentTexts("<button [disabled]=\"busy || !items.length\" (click)=\"save(); busy = true\" [(value)]=\"title\" [ngClass]=\"{ on: busy, 'is-off': !busy }\" class=\"x\">ok</button>"),
    )
    fun `test an interpolation in an attribute`() = assertEquals(listOf("( title );"), fragmentTexts("<img alt=\"the {{ title }}\">"))
    fun `test block headers`() = assertEquals(
        listOf("(items.length > 0);", "for (const item of (items | slice(0,3))) {}", "(item.id);", "let i = \$index, last = \$last;", "( i );", "(busy);", "const total =( items.length + 1);", "(title);"),
        fragmentTexts("@if (items.length > 0) {\n@for (item of items | slice:0:3; track item.id; let i = \$index, last = \$last) {\n<b>{{ i }}</b>\n} @empty { none }\n} @else if (busy) { wait } @else { no }\n@let total = items.length + 1;\n@switch (title) { @default { x } }"),
    )
    fun `test an alias`() = assertEquals(listOf("(items.length);", "const count = 0;", "( count );"), fragmentTexts("@if (items.length; as count) { {{ count }} }"))

    fun `test a realistic template has no syntax error`() {
        val file = template("""
            @if (items.length > 0 && !busy) {
              <ul [class.busy]="busy" [style.width.px]="items.length * 10">
                @for (item of items; track item.id; let i = ${'$'}index) {
                  <li #row (click)="save(item); ${'$'}event.stopPropagation()" [title]="item.name | uppercase">{{ i + 1 }}. {{ item?.name ?? 'none' }} {{ when | date: 'dd/MM' }}</li>
                } @empty {
                  <li>nothing</li>
                }
              </ul>
            } @else {
              <app-spinner [options]="{ size: 12, labels: ['a', 'b'] }" (done)="busy = false" />
            }
            @let label = busy ? 'Saving…' : title;
            <footer>{{ label }} {{ items[0]!.name }} {{ `${'$'}{title}!` }}</footer>
        """.trimIndent())
        val fragments = fragments(file)
        assertEquals(17, fragments.size)
        fragments.forEach { f -> assertNull("${f.text}: ${PsiTreeUtil.findChildOfType(f, PsiErrorElement::class.java)?.errorDescription}", PsiTreeUtil.findChildOfType(f, PsiErrorElement::class.java)) }
    }

    fun `test an html file without a component is left alone`() {
        myFixture.addFileToProject("other.ts", "export class Other { title = 1 }")
        assertEmpty(fragments(myFixture.configureByText("page.html", "<h1>{{ title }}</h1>")))
    }

    fun `test interpolation to a property`() = member("<h1>{{ <ref>title }}</h1>", "title =")
    fun `test binding to a property`() = member("<button [disabled]=\"<ref>busy\"></button>", "busy =")
    fun `test event to a method`() = member("<button (click)=\"<ref>save()\"></button>", "save(")
    fun `test block header to a property`() = member("@if (<ref>items.length) { x }", "items:")
    fun `test loop source to a property`() = member("@for (item of <ref>items; track item) { x }", "items:")
    fun `test piped value to a property`() = member("<p>{{ <ref>when | date: 'short' }}</p>", "when =")

    fun `test loop item`() = variable("@for (item of items; track item.id) { <b>{{ <ref>item.name }}</b> }", "item of")
    fun `test loop item in track`() = variable("@for (item of items; track <ref>item.id) { x }", "item of")
    fun `test loop index`() = variable("@for (item of items; track item; let i = \$index) { {{ <ref>i }} }", "i =")
    fun `test let`() = variable("@let total = items.length;\n<p>{{ <ref>total }}</p>", "total =")
    fun `test alias`() = variable("@if (items.length; as count) { {{ <ref>count }} }", "count)")
    fun `test template reference`() = variable("<input #box><button (click)=\"<ref>box.focus()\"></button>", "#box")
    fun `test let attribute`() = variable("<ng-template let-row><b>{{ <ref>row.id }}</b></ng-template>", "let-row")
    fun `test structural directive variable`() = variable("<td *matCellDef=\"let row; let i = index\">{{ <ref>i }}</td>", "*matCellDef")
    fun `test the nearest loop item`() = variable("@for (item of items; track item) { a }\n@for (item of items; track item) { {{ <ref>item }} }", "item of items; track item) { {{")

    fun `test member of a loop item`() {
        myFixture.addFileToProject("lib.d.ts", "interface Array<T> { length: number; }")
        val found = target("@for (item of items; track item.id) { {{ item.<ref>name }} }") ?: error("unresolved")
        assertEquals("list.component.ts" to component.indexOf("name: string"), found.first to found.second)
    }

    fun `test pipe`() {
        myFixture.addFileToProject("money.pipe.ts", "@Pipe({ name: 'money', standalone: true })\nexport class MoneyPipe { transform(value: number): string { return '' } }")
        val found = target("<p>{{ items.length | <ref>money: 'EUR' }}</p>") ?: error("unresolved")
        assertEquals("money.pipe.ts" to "MoneyPipe", found.first to found.third.substringAfter("class ").substringBefore(" "))
    }

    fun `test an unknown name is not resolved`() = assertNull(target("<p>{{ <ref>missing }}</p>"))

    fun `test usages and rename of a property`() {
        val html = template("<h1>{{ title }}</h1>\n@if (title) { <p [title]=\"title\"></p> }\n")
        val source = myFixture.findFileInTempDir("list.component.ts").let { psiManager.findFile(it)!! }
        val title = PsiTreeUtil.findChildrenOfType(source, TsNamedDecl::class.java).first { it.name == "title" }
        assertEquals(3, ReferencesSearch.search(title).findAll().size)
        myFixture.renameElement(title, "heading")
        assertEquals("<h1>{{ heading }}</h1>\n@if (heading) { <p [title]=\"heading\"></p> }\n", html.text)
    }

    private fun inline(template: String, quote: String = "`") = """
        interface Item { id: number; name: string; }
        @Component({
            selector: 'app-list',
            template: $quote$template$quote,
        })
        export class ListComponent {
            title = 'Items';
            items: Item[] = [];
            save(item?: Item): void {}
        }
    """.trimIndent()

    /** Where `<ref>` of the inline [template] points: its offset in the file, or -1. */
    private fun inlineTarget(template: String, quote: String = "`"): Int {
        val source = inline(template, quote)
        val file = myFixture.configureByText("list.component.ts", source.replace("<ref>", ""))
        val leaf = manager.findInjectedElementAt(file, source.indexOf("<ref>")) ?: error("nothing injected at <ref>")
        val ref = generateSequence(leaf) { it.parent }.firstNotNullOfOrNull { it.reference as? PsiPolyVariantReference } ?: error("no reference at <ref>")
        val found = ref.multiResolve(false).firstNotNullOfOrNull { it.element } ?: return -1
        val name: PsiElement = (found as? PsiNameIdentifierOwner)?.nameIdentifier ?: found
        return manager.injectedToHost(name, name.textOffset)
    }

    private fun inlineAt(template: String, text: String) = inline(template).replace("<ref>", "").indexOf(text)

    fun `test inline template fragments`() {
        val file = myFixture.configureByText("list.component.ts", inline("<h1 [title]=\"title\">{{ title | upper: 1 }}</h1>\n@if (items.length) { <a (click)=\"save()\">x</a> }"))
        val injected = fragments(file)
        assertEquals(listOf("(title);", "( title | upper( 1 ));", "(items.length);", "save()"), injected.filter { it.language == TsLanguage }.map { it.text.trim() })
        assertEquals("<h1 [title]=\"\">{{}}</h1>\n@if () { <a (click)=\"\">x</a> }", injected.single { it.language.id == "HTML" }.text)
        injected.forEach { assertNull(it.text, PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java)) }
    }

    fun `test inline interpolation to a property`() = "<h1>{{ <ref>title }}</h1>".let { assertEquals(inlineAt(it, "title ="), inlineTarget(it)) }
    fun `test inline event to a method`() = "<a (click)=\"<ref>save()\"></a>".let { assertEquals(inlineAt(it, "save(item"), inlineTarget(it)) }
    fun `test inline loop item`() = "@for (item of items; track item.id) { {{ <ref>item.name }} }".let { assertEquals(inlineAt(it, "item of"), inlineTarget(it)) }
    fun `test inline template reference`() = "<input #box><a (click)=\"<ref>box.focus()\"></a>".let { assertEquals(inlineAt(it, "#box"), inlineTarget(it)) }
    fun `test inline template in a plain string`() = assertEquals(inline("<b>{{ title }}</b>", "'").indexOf("title ="), inlineTarget("<b>{{ <ref>title }}</b>", "'"))

    fun `test a template property outside a component is a string`() {
        val file = myFixture.configureByText("x.ts", "const options = { template: `<b>{{ title }}</b>` };\n@Directive({ template: `{{ a }}` }) class D {}")
        assertEmpty(fragments(file))
    }

    fun `test usages and rename reach an inline template`() {
        val file = myFixture.configureByText("list.component.ts", inline("<h1>{{ title }}</h1>\n@if (title) { x }"))
        val title = PsiTreeUtil.findChildrenOfType(file, TsNamedDecl::class.java).first { it.name == "title" }
        assertEquals(2, ReferencesSearch.search(title).findAll().size)
        myFixture.renameElement(title, "heading")
        assertTrue(file.text, file.text.contains("template: `<h1>{{ heading }}</h1>\n@if (heading) { x }`") && file.text.contains("heading = 'Items'"))
    }

    /** LITE_NG_CORPUS=<dir>: fragments of every `*.component.html` without syntax error, and how many of their names resolve. */
    fun `test corpus`() {
        val dir = System.getenv("LITE_NG_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val inline = Regex("""\btemplate\s*:""")
        val files = dir.walkTopDown().onEnter { it.name != "node_modules" && it.name != "dist" }
            .filter { it.isFile }.filter { it.name.endsWith(".component.html") || (it.name.endsWith(".ts") && !it.name.endsWith(".spec.ts") && it.readText().let { text -> "@Component" in text && inline.containsMatchIn(text) }) }.toList()
        val started = System.nanoTime()
        var total = 0; var names = 0; var resolved = 0
        val broken = ArrayList<String>(); val misses = HashMap<String, Int>()
        for (f in files) {
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: continue
            for (fragment in fragments(psi).filter { it.language == TsLanguage }) {
                total++
                PsiTreeUtil.findChildOfType(fragment, PsiErrorElement::class.java)?.let { broken += "${f.path.removePrefix(dir.path)}: ${it.errorDescription} in '${fragment.text.trim().take(110).replace('\n', ' ')}'" }
                PsiTreeUtil.findChildrenOfType(fragment, TsReferenceExpr::class.java).filter { it.qualifier == null && it.reference != null }.forEach { ref ->
                    names++
                    if (TsResolver.resolve(ref).isNotEmpty()) { resolved++; return@forEach }
                    misses.merge(ref.referencedName ?: "", 1, Int::plus)
                }
            }
        }
        val top = misses.entries.sortedByDescending { it.value }.take(40).joinToString(" ") { "${it.key}:${it.value}" }
        println("ng corpus: ${files.count { it.extension == "html" }} template files and ${files.count { it.extension == "ts" }} components with an inline template, ${total - broken.size}/$total fragments without errors, names $resolved/$names in ${(System.nanoTime() - started) / 1_000_000} ms\nunresolved: $top\n" + broken.take(40).joinToString("\n"))
    }
}
