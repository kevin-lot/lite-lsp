package me.kevinlot.lite.typescript

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.typescript.angular.AngularAttributeReference
import me.kevinlot.lite.typescript.angular.AngularComponents
import me.kevinlot.lite.typescript.angular.AngularTagReference
import me.kevinlot.lite.typescript.psi.TsClass
import me.kevinlot.lite.typescript.psi.TsFunction
import me.kevinlot.lite.typescript.psi.TsNamedDecl

class TsAngularComponentsTest : BasePlatformTestCase() {
    private val card = """
        class Base { @Input() tone = 'plain'; }
        @Component({ selector: 'app-card, section[appCard]', template: '' })
        export class CardComponent extends Base {
            @Input() title = '';
            @Input('kind') variant = 'info';
            @Input({ alias: 'wide', required: true }) isWide = false;
            @Input() set level(value: number) {}
            @Output() closed = new EventEmitter<void>();
            size = input<number>(1);
            label = input.required<string>({ alias: 'caption' });
            value = model('');
            picked = output<string>();
            hidden = false;
        }
    """.trimIndent()

    private val tip = """
        @Directive({ selector: '[appTip], button[appHint]:not([disabled])' })
        export class TipDirective {
            @Input() appTip = '';
            @Input() tipDelay = 0;
            @Output() tipShown = new EventEmitter<void>();
        }
    """.trimIndent()

    private val host = "@Component({ selector: 'app-page', templateUrl: './page.component.html' })\nexport class PageComponent { name = ''; done(): void {} }\n"

    private val panel = """
        import { TipDirective } from './tip.directive';
        @Component({
            selector: 'app-panel', template: '', inputs: ['depth', 'shade: tint'], outputs: ['moved'],
            hostDirectives: [{ directive: TipDirective, inputs: ['tipDelay: delay'], outputs: ['tipShown'] }],
        })
        export class PanelComponent { depth = 0; shade = ''; moved = new EventEmitter<void>(); }
    """.trimIndent()

    private fun sources() {
        myFixture.addFileToProject("panel.component.ts", panel)
        myFixture.addFileToProject("card.component.ts", card)
        myFixture.addFileToProject("tip.directive.ts", tip)
        myFixture.addFileToProject("page.component.ts", host)
    }

    private fun references(file: PsiFile, at: Int): List<PsiPolyVariantReference> {
        val manager = InjectedLanguageManager.getInstance(project)
        val leaves = file.viewProvider.allFiles.mapNotNull { it.findElementAt(at) } + listOfNotNull(manager.findInjectedElementAt(file, at))
        return leaves.flatMap { leaf ->
            val offset = at - (leaf.containingFile.takeIf { manager.isInjectedFragment(it) }?.let { manager.injectedToHost(leaf, leaf.textOffset) - leaf.textOffset } ?: 0)
            generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }
                .flatMap { e -> e.references.filter { it.rangeInElement.shiftRight(e.textRange.startOffset).containsOffset(offset) }.asSequence() }
        }.filter { it is AngularTagReference || it is AngularAttributeReference }.filterIsInstance<PsiPolyVariantReference>().distinct()
    }

    /** The names of what `<ref>` of [html] points to, in the template of the page. */
    private fun targets(html: String): List<String> {
        sources()
        val file = myFixture.configureByText("page.component.html", html.replace("<ref>", ""))
        return references(file, html.indexOf("<ref>")).flatMap { it.multiResolve(false).mapNotNull { r -> (r.element as? PsiNamedElement)?.name } }
    }

    fun `test element`() = assertEquals(listOf("CardComponent"), targets("<<ref>app-card></app-card>"))
    fun `test input of an element with the attribute its selector asks for`() = assertEquals(listOf("title"), targets("<section appCard [<ref>title]=\"name\"></section>"))
    fun `test native element is no component`() = assertEmpty(targets("<<ref>section [title]=\"name\"></section>"))
    fun `test unknown element`() = assertEmpty(targets("<<ref>app-other></app-other>"))

    fun `test decorated input`() = assertEquals(listOf("title"), targets("<app-card [<ref>title]=\"name\"></app-card>"))
    fun `test static input`() = assertEquals(listOf("title"), targets("<app-card <ref>title=\"Hello\"></app-card>"))
    fun `test bind prefix`() = assertEquals(listOf("title"), targets("<app-card bind-<ref>title=\"name\"></app-card>"))
    fun `test input alias as a string`() = assertEquals(listOf("variant"), targets("<app-card [<ref>kind]=\"name\"></app-card>"))
    fun `test input alias in an object`() = assertEquals(listOf("isWide"), targets("<app-card [<ref>wide]=\"true\"></app-card>"))
    fun `test member name is not bound when it has an alias`() = assertEmpty(targets("<app-card [<ref>variant]=\"name\"></app-card>"))
    fun `test setter input`() = assertEquals(listOf("level"), targets("<app-card [<ref>level]=\"1\"></app-card>"))
    fun `test inherited input`() = assertEquals(listOf("tone"), targets("<app-card [<ref>tone]=\"name\"></app-card>"))
    fun `test signal input`() = assertEquals(listOf("size"), targets("<app-card [<ref>size]=\"2\"></app-card>"))
    fun `test required signal input with an alias`() = assertEquals(listOf("label"), targets("<app-card [<ref>caption]=\"name\"></app-card>"))
    fun `test decorated output`() = assertEquals(listOf("closed"), targets("<app-card (<ref>closed)=\"done()\"></app-card>"))
    fun `test signal output`() = assertEquals(listOf("picked"), targets("<app-card (<ref>picked)=\"done()\"></app-card>"))
    fun `test on prefix`() = assertEquals(listOf("closed"), targets("<app-card on-<ref>closed=\"done()\"></app-card>"))
    fun `test model as two way binding`() = assertEquals(listOf("value"), targets("<app-card [(<ref>value)]=\"name\"></app-card>"))
    fun `test model as an event`() = assertEquals(listOf("value"), targets("<app-card (<ref>valueChange)=\"done()\"></app-card>"))
    fun `test an input is no event`() = assertEmpty(targets("<app-card (<ref>title)=\"done()\"></app-card>"))
    fun `test an output is no property`() = assertEmpty(targets("<app-card [<ref>closed]=\"name\"></app-card>"))
    fun `test a plain member is no input`() = assertEmpty(targets("<app-card [<ref>hidden]=\"true\"></app-card>"))

    fun `test directive attribute that is its input`() = assertEquals(listOf("appTip"), targets("<p [<ref>appTip]=\"name\"></p>"))
    fun `test other input of a directive`() = assertEquals(listOf("tipDelay"), targets("<p appTip [<ref>tipDelay]=\"3\"></p>"))
    fun `test output of a directive`() = assertEquals(listOf("tipShown"), targets("<p appTip (<ref>tipShown)=\"done()\"></p>"))
    fun `test directive attribute that is no input`() = assertEquals(listOf("TipDirective"), targets("<button <ref>appHint></button>"))
    fun `test directive bound to another element`() = assertEmpty(targets("<p <ref>appHint></p>"))
    fun `test directive on a component`() = assertEquals(listOf("tipDelay"), targets("<app-card appTip [<ref>tipDelay]=\"3\"></app-card>"))
    fun `test native attribute of a native element has no reference`() = assertEmpty(targets("<p [<ref>hidden]=\"true\" (click)=\"done()\"></p>"))

    fun `test input listed in the decorator`() = assertEquals(listOf("depth"), targets("<app-panel [<ref>depth]=\"1\"></app-panel>"))
    fun `test listed input with an alias`() = assertEquals(listOf("shade"), targets("<app-panel [<ref>tint]=\"name\"></app-panel>"))
    fun `test listed input is not bound under its member name`() = assertEmpty(targets("<app-panel [<ref>shade]=\"name\"></app-panel>"))
    fun `test output listed in the decorator`() = assertEquals(listOf("moved"), targets("<app-panel (<ref>moved)=\"done()\"></app-panel>"))
    fun `test input of a host directive under its alias`() = assertEquals(listOf("tipDelay"), targets("<app-panel [<ref>delay]=\"3\"></app-panel>"))
    fun `test output of a host directive`() = assertEquals(listOf("tipShown"), targets("<app-panel (<ref>tipShown)=\"done()\"></app-panel>"))
    fun `test host directive input that is not exposed`() = assertEmpty(targets("<app-panel [<ref>tipDelay]=\"3\"></app-panel>"))

    fun `test bare names`() {
        assertEquals(listOf("size", "saved", "value", "size", "saved", "value", "appIf", "size"),
            listOf("[size]", "(saved)", "[(value)]", "bind-size", "on-saved", "bindon-value", "*appIf", "size").map(AngularComponents::bare))
        assertEquals(listOf(null, null, null, null), listOf("#ref", "let-row", "[attr.role]", "@fade").map(AngularComponents::bare))
    }

    fun `test selectors`() {
        sources()
        val found = AngularComponents.selectors(project).map { "${it.element}${it.attributes}" }.sorted()
        assertEquals(listOf("app-card[]", "app-page[]", "app-panel[]", "button[appHint]", "null[appTip]", "section[appCard]"), found)
    }

    fun `test inline template`() {
        sources()
        val source = "@Component({ selector: 'app-box', template: `<app-card [title]=\"name\" (closed)=\"done()\"></app-card>` })\nexport class BoxComponent { name = ''; done(): void {} }\n"
        val file = myFixture.configureByText("box.component.ts", source)
        val names = { text: String -> references(file, source.indexOf(text) + 1).flatMap { it.multiResolve(false).mapNotNull { r -> (r.element as? PsiNamedElement)?.name } } }
        assertEquals(listOf("CardComponent"), names("<app-card").distinct())
        assertEquals(listOf("title"), names("[title]").distinct())
        assertEquals(listOf("closed"), names("(closed)").distinct())
    }

    private fun member(file: String, name: String): TsNamedDecl =
        PsiTreeUtil.findChildrenOfType(psiManager.findFile(myFixture.findFileInTempDir(file))!!, TsNamedDecl::class.java)
            .first { it.name == name && PsiTreeUtil.getParentOfType(it, TsFunction::class.java) == null }

    fun `test usages and rename of an input`() {
        sources()
        val html = myFixture.configureByText("page.component.html", "<app-card [title]=\"name\" title=\"x\"></app-card>\n<p title=\"y\">{{ name }}</p>\n")
        val title = member("card.component.ts", "title")
        assertEquals(2, ReferencesSearch.search(title).findAll().count { it is AngularAttributeReference })
        myFixture.renameElement(title, "heading")
        assertEquals("<app-card [heading]=\"name\" heading=\"x\"></app-card>\n<p title=\"y\">{{ name }}</p>\n", html.text)
    }

    fun `test rename of an output and of a model`() {
        sources()
        val html = myFixture.configureByText("page.component.html", "<app-card (closed)=\"done()\" [(value)]=\"name\"></app-card>\n")
        myFixture.renameElement(member("card.component.ts", "closed"), "dismissed")
        myFixture.renameElement(member("card.component.ts", "value"), "text")
        assertEquals("<app-card (dismissed)=\"done()\" [(text)]=\"name\"></app-card>\n", html.text)
    }

    fun `test rename of an aliased input keeps the alias`() {
        sources()
        val html = myFixture.configureByText("page.component.html", "<app-card [kind]=\"name\"></app-card>\n")
        myFixture.renameElement(member("card.component.ts", "variant"), "flavor")
        assertEquals("<app-card [kind]=\"name\"></app-card>\n", html.text)
    }

    fun `test usages of a component and rename of its class`() {
        sources()
        val html = myFixture.configureByText("page.component.html", "<app-card></app-card>\n<section appCard></section>\n<button appHint></button>\n")
        val cls = member("card.component.ts", "CardComponent") as TsClass
        assertEquals(1, ReferencesSearch.search(cls).findAll().count { it is AngularTagReference })
        assertEquals(1, ReferencesSearch.search(cls).findAll().count { it is AngularAttributeReference })
        assertEquals(1, ReferencesSearch.search(member("tip.directive.ts", "TipDirective")).findAll().count { it is AngularAttributeReference })
        myFixture.renameElement(cls, "PanelComponent")
        assertEquals("<app-card></app-card>\n<section appCard></section>\n<button appHint></button>\n", html.text)
    }
}
