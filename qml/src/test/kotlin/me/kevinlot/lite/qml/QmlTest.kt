package me.kevinlot.lite.qml

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import me.kevinlot.lite.qml.ide.QmlFindUsagesProvider
import me.kevinlot.lite.qml.psi.QmlNamed
import me.kevinlot.lite.qml.resolve.QmlDirs
import me.kevinlot.lite.qml.resolve.QmlEnvironment

class QmlTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        QmlEnvironment.importPaths.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
    }

    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { fail("parse error: ${it.errorDescription} at '${file.text.substring(it.textOffset).take(30)}'") }
        val ref = file.findReferenceAt(refOffset) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(40)}" }}", targets.any { t ->
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile!!) && (off == defOffset || defOffset == 0)
        })
    }

    private fun one(code: String) = check("Main.qml" to code)

    /** QML soft keywords are tokens of the JavaScript language: its annotator colors them first. */
    private fun annotated(code: String): String {
        myFixture.configureByText("Main.qml", code)
        val colored = myFixture.doHighlighting().filter { it.forcedTextAttributesKey?.externalName?.let { key -> key.startsWith("QML_") || key == "TS_KEYWORD" } == true }
        return colored.sortedBy { it.startOffset }.joinToString(" ") { "${it.text}:${it.forcedTextAttributesKey.externalName.substringAfter('_')}" }
    }

    private fun declarations(code: String): List<QmlNamed> =
        PsiTreeUtil.findChildrenOfType(myFixture.configureByText("Main.qml", code), QmlNamed::class.java).toList()

    /** <ref> in the last file resolves to nothing. */
    private fun none(vararg files: Pair<String, String>) {
        files.dropLast(1).forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        val (path, code) = files.last()
        val file = myFixture.addFileToProject(path, code.replace("<ref>", ""))
        val ref = file.findReferenceAt(code.indexOf("<ref>")) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        assertEmpty(ref.multiResolve(false).mapNotNull { it.element }.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(40)}" })
    }

    private fun usages(declaration: QmlNamed): List<String> = ReferencesSearch.search(declaration).findAll().map { it.element.text }.sorted()

    /** <ref> in [code] resolves into the Qt file [qtFile] (skipped without a Qt installation). */
    private fun qt(code: String, qtFile: String) {
        if (!hasQt) return
        var text = code.trimIndent()
        val r = text.indexOf("<ref>"); text = text.removeRange(r, r + 5)
        val file = myFixture.configureByText("Main.qml", text)
        val ref = file.findReferenceAt(r) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}: ${it.text.take(60)}" }}", targets.any { it.containingFile?.name == qtFile })
    }
    private val hasQt get() = QmlEnvironment.importPaths.any { it.findChild("builtins.qmltypes") != null }

    private fun parses(name: String, code: String) {
        val file = myFixture.configureByText(name, code.trimIndent())
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        assertNull(error?.let { "${it.errorDescription} at '${file.text.substring(it.textOffset).take(30)}'" }, error)
    }

    // ---------------------------------------------------------------- parser

    fun `test an empty file has no element`() {
        listOf("Empty.qml", "empty.qmltypes").forEach { name ->
            assertEmpty(name, myFixture.configureByText(name, "").children.toList())
        }
    }

    fun `test parses qml`() = parses("A.qml", """
        pragma Singleton
        pragma ComponentBehavior: Bound
        import QtQuick 2.15
        import QtQuick.Layouts as L
        import "widgets"
        import "util.js" as Util

        L.ColumnLayout {
            id: root
            default property alias content: inner.data
            required property var api
            readonly property real ratio: width / Math.max(1, height)
            property list<Item> items: [ Item {}, Item {} ]
            property Item current: Item { width: 1 }
            signal moved(int x, real y)
            signal picked(name: string)
            enum Mode { Off, On = 2 }
            component Card: Rectangle { radius: 4 }

            spacing: Style.marginM; visible: true
            anchors { left: parent.left; margins: 4 }
            Layout.fillWidth: true
            onMoved: (x, y) => console.log(x, y)
            Component.onCompleted: {
                const n = items.length
                for (let i = 0; i < n; i++) Util.log(i)
            }
            NumberAnimation on opacity { to: 1 }
            Behavior on width { NumberAnimation {} }

            function reset(value: int): bool {
                return api?.instance?.value ?? (value || 10)
            }
            Item { id: inner }
        }
    """)

    fun `test parses qmltypes`() = parses("plugins.qmltypes", """
        import QtQuick.tooling 1.2
        Module {
            Component {
                name: "QQuickItem"
                prototype: "QObject"
                exports: ["QtQuick/Item 2.0", "QtQuick/Item 6.0"]
                Enum { name: "TransformOrigin"; values: ["TopLeft", "Top"] }
                Property { name: "width"; type: "double"; read: "width" }
                Signal { name: "childrenRectChanged"; Parameter { name: "rect"; type: "QRectF" } }
                Method { name: "grabToImage"; type: "bool"; isJavaScriptFunction: true }
            }
        }
    """)

    // ---------------------------------------------------------------- documents

    fun `test id from javascript`() = one("Item {\n  id: <def>root\n  property int x: 1\n  Item { width: <ref>root.x }\n}")
    fun `test property from javascript`() = one("Item {\n  property int <def>count: 0\n  Item { width: <ref>count }\n}")
    fun `test member through id`() = one("Item {\n  id: root\n  property int <def>count: 0\n  Item { width: root.<ref>count }\n}")
    fun `test binding to own property`() = one("Item {\n  property int <def>count\n  <ref>count: 2\n}")
    fun `test property change handler`() = one("Item {\n  property int <def>count\n  <ref>onCountChanged: {}\n}")
    fun `test signal handler`() = one("Item {\n  signal <def>moved(int x)\n  <ref>onMoved: x => x\n}")
    fun `test function member`() = one("Item {\n  function <def>reset() {}\n  Item { width: <ref>reset() }\n}")
    fun `test enum value`() = one("Item {\n  enum Mode { Off, <def>On }\n  property int m: Main.<ref>On\n}")
    fun `test inline component`() = one("Item {\n  component <def>Card: Item {}\n  <ref>Card {}\n}")
    fun `test sibling document type`() = check("MyButton.qml" to "<def>Item {}", "Main.qml" to "Item { <ref>MyButton {} }")
    fun `test property of sibling type`() = check("MyButton.qml" to "Item { property string <def>label }", "Main.qml" to "Item { MyButton { <ref>label: \"x\" } }")
    fun `test javascript member of sibling type`() = check(
        "MyButton.qml" to "Item { property string <def>label }",
        "Main.qml" to "Item {\n  MyButton { id: b }\n  Item { visible: b.<ref>label }\n}",
    )
    fun `test inherited member of sibling type`() = check(
        "Base.qml" to "Item { property int <def>size }", "MyButton.qml" to "Base {}",
        "Main.qml" to "Item { MyButton { <ref>size: 2 } }",
    )
    fun `test group property`() = check(
        "MyFont.qml" to "Item { property bool <def>bold }",
        "Main.qml" to "Item {\n  property MyFont font: MyFont {}\n  font { <ref>bold: true }\n}",
    )
    fun `test directory import`() = check("widgets/Badge.qml" to "<def>Item {}", "Main.qml" to "import \"widgets\"\nItem { <ref>Badge {} }")
    fun `test quickshell qs module`() = check(
        "shell/shell.qml" to "Item {}", "shell/Widgets/NButton.qml" to "<def>Item {}",
        "shell/Modules/Bar.qml" to "import qs.Widgets\nItem { <ref>NButton {} }",
    )
    fun `test javascript resource`() = check("util.js" to "function <def>fmt(v) { return v }", "Main.qml" to "import \"util.js\" as Util\nItem { width: Util.<ref>fmt(1) }")
    fun `test singleton from javascript`() = check(
        "Style.qml" to "pragma Singleton\nimport QtQuick\nQtObject { property int <def>marginM: 4 }",
        "Main.qml" to "Item { width: Style.<ref>marginM }",
    )

    fun `test aliased directory import`() = check("widgets/Badge.qml" to "<def>Item {}", "Main.qml" to "import \"widgets\" as W\nItem { W.<ref>Badge {} }")
    fun `test binding to a behavior target`() = one("Item {\n  property int <def>count\n  Behavior on <ref>count {}\n}")
    fun `test dotted binding into a property object`() = check(
        "MyFont.qml" to "Item { property bool <def>bold }",
        "Main.qml" to "Item {\n  property MyFont font: MyFont {}\n  font.<ref>bold: true\n}",
    )
    fun `test dotted project module`() = check("acme/Shapes/Tri.qml" to "<def>Item {}", "Main.qml" to "import acme.Shapes\nItem { <ref>Tri {} }")
    fun `test enum value of another document`() = check(
        "Panel.qml" to "Item { enum Mode { Off, <def>On } }", "Main.qml" to "Item { property int m: Panel.<ref>On }",
    )
    fun `test explicit changed signal handler`() = one("Item {\n  signal <def>dataChanged()\n  <ref>onDataChanged: {}\n}")
    fun `test function of another document through its id`() = check(
        "MyButton.qml" to "Item { function <def>reset() {} }",
        "Main.qml" to "Item {\n  MyButton { id: b }\n  Item { visible: b.<ref>reset() }\n}",
    )
    fun `test inline component base type`() = check("MyButton.qml" to "<def>Item {}", "Main.qml" to "Item { component Card: <ref>MyButton {} }")
    fun `test inline component member`() = one("Item {\n  component Card: Item { property int <def>pad }\n  Card { <ref>pad: 1 }\n}")
    fun `test inline component of another document`() = check(
        "Cards.qml" to "Item { component <def>Card: Item {} }", "Main.qml" to "Item { Cards.<ref>Card {} }",
    )
    fun `test inline component root from javascript`() = one(
        "Item {\n  component Card: Item {\n    property int <def>pad\n    Item { width: <ref>pad }\n  }\n}",
    )
    fun `test javascript member through a typed property`() = check(
        "MyButton.qml" to "Item { property string <def>label }",
        "Main.qml" to "Item {\n  property MyButton button\n  Item { visible: button.<ref>label }\n}",
    )
    fun `test javascript member through an alias property`() = one(
        "Item {\n  id: root\n  property alias inner: box\n  Item { id: box; property int <def>size }\n  Item { width: root.inner.<ref>size }\n}",
    )
    fun `test list property element type`() = check("MyButton.qml" to "<def>Item {}", "Main.qml" to "Item { property list<<ref>MyButton> buttons }")
    fun `test module qmldir import is followed`() = check(
        "Kit/qmldir" to "module Kit\nimport KitBase", "KitBase/Knob.qml" to "<def>Item {}", "Main.qml" to "import Kit\nItem { <ref>Knob {} }",
    )
    fun `test module qmldir names a document`() = check(
        "Forms/qmldir" to "module Forms\nSquare 1.0 impl/SquareImpl.qml", "Forms/impl/SquareImpl.qml" to "<def>Item {}",
        "Main.qml" to "import Forms\nItem { <ref>Square {} }",
    )
    fun `test property type`() = check("MyButton.qml" to "<def>Item {}", "Main.qml" to "Item { property <ref>MyButton button }")
    fun `test qmldir of the document folder`() = check(
        "qmldir" to "Fancy 1.0 impl/FancyImpl.qml", "impl/FancyImpl.qml" to "<def>Item {}", "Main.qml" to "Item { <ref>Fancy {} }",
    )
    fun `test quickshell module needs the shell config`() = none(
        "Widgets/NButton.qml" to "Item {}", "Modules/Bar.qml" to "import qs.Widgets\nItem { <ref>NButton {} }",
    )
    fun `test quickshell root module`() = check(
        "conf/shell.qml" to "Item {}", "conf/Bar.qml" to "<def>Item {}", "conf/Modules/Panel.qml" to "import qs\nItem { <ref>Bar {} }",
    )
    fun `test required inherited property`() = check(
        "MyButton.qml" to "Item { property string <def>label }", "Main.qml" to "Item { MyButton { required <ref>label } }",
    )
    fun `test signal handler of sibling type`() = check(
        "MyButton.qml" to "Item { signal <def>pressed() }", "Main.qml" to "Item { MyButton { <ref>onPressed: {} } }",
    )
    fun `test signal parameter type`() = check("MyButton.qml" to "<def>Item {}", "Main.qml" to "Item { signal picked(button: <ref>MyButton) }")
    fun `test var property is untyped`() = none("Main.qml" to "Item {\n  property var data\n  Item { width: data.<ref>size }\n}")

    // ---------------------------------------------------------------- highlighting and names

    fun `test comment line`() {
        myFixture.configureByText("Main.qml", "Item {\n  wid<caret>th: 1\n}\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("Item {\n//  width: 1\n}\n")
    }

    private val document = """
        import QtQuick
        Item {
          id: root
          property int count: 0
          signal moved(int x)
          enum Mode { Off, On }
          component Card: Item {}
          onMoved: {}
          Layout.fillWidth: true
          width: 1
        }
    """.trimIndent()

    fun `test document structure is colored by kind`() = assertEquals(
        "QtQuick:MODULE Item:TYPE id:KEYWORD root:ID property:KEYWORD int:TYPE count:PROPERTY signal:KEYWORD moved:SIGNAL int:TYPE x:PROPERTY " +
            "Mode:TYPE Off:PROPERTY On:PROPERTY component:KEYWORD Card:TYPE Item:TYPE onMoved:HANDLER Layout:TYPE fillWidth:PROPERTY width:PROPERTY",
        annotated(document),
    )

    fun `test qmldir lists types, singletons, imports and type descriptions`() {
        val text = """
            module Listed
            typeinfo plugins.qmltypes
            import Base
            default import Listed.Basic
            optional import Listed.Fancy
            singleton Theme 1.0 Theme.qml
            internal Helper Helper.qml
            Square 2.0 Square2.qml
            Square 1.0 Square.qml
            Util 1.0 util.js
            # a comment
        """.trimIndent()
        val qmldir = QmlDirs.read(myFixture.tempDirFixture.createFile("Listed/qmldir", text).parent) ?: error("no qmldir")
        assertEquals(mapOf("Theme" to "Theme.qml", "Helper" to "Helper.qml", "Square" to "Square2.qml", "Util" to "util.js"), qmldir.types)
        assertEquals(setOf("Theme"), qmldir.singletons)
        assertEquals(listOf("Base", "Listed.Basic"), qmldir.imports)
        assertEquals(listOf("plugins.qmltypes"), qmldir.typeInfo)
        assertNull(QmlDirs.read(myFixture.tempDirFixture.findOrCreateDir("Unlisted")))
    }

    fun `test quickshell root is the folder of shell qml`() {
        val panel = myFixture.addFileToProject("conf/Modules/Panel.qml", "Item {}").virtualFile
        assertNull(QmlEnvironment.quickshellRoot(panel, project))
        assertEmpty(QmlEnvironment.moduleDirs("qs.Modules", panel, project))
        myFixture.addFileToProject("conf/shell.qml", "Item {}")
        assertEquals("/src/conf", QmlEnvironment.quickshellRoot(panel, project)?.path)
        assertEquals(listOf("/src/conf"), QmlEnvironment.moduleDirs("qs", panel, project).map { it.path })
        assertEquals(listOf("/src/conf/Modules"), QmlEnvironment.moduleDirs("qs.Modules", panel, project).map { it.path })
    }

    fun `test type description file is told apart from a document`() {
        val types = myFixture.addFileToProject("Told/plugins.qmltypes", "Module { Component { name: \"ToldItem\" } }") as QmlFile
        val document = myFixture.addFileToProject("Told/Square.qml", "Item {}") as QmlFile
        assertTrue(types.isTypeInfo)
        assertSame(QmlTypesFileType, types.fileType)
        assertFalse(document.isTypeInfo)
        assertSame(QmlFileType, document.fileType)
        assertEquals("Square", document.componentName)
        assertEmpty(types.rootObject?.typeRef?.reference.let { it as PsiPolyVariantReference }.multiResolve(false).toList())
    }

    fun `test usage view names each kind`() {
        val provider = QmlFindUsagesProvider()
        assertEquals(
            listOf("root: id", "count: property", "moved: signal", "x: parameter", "Mode: enum", "Off: enum value", "On: enum value", "Card: component"),
            declarations(document).map { "${it.name}: ${provider.getType(it)}" },
        )
    }

    // ---------------------------------------------------------------- Qt (skipped without a Qt installation)

    fun `test qt property`() = qt("import QtQuick\nItem { <ref>width: 1 }", "plugins.qmltypes")
    fun `test qt inherited property`() = qt("import QtQuick\nRectangle { <ref>width: 1 }", "plugins.qmltypes")
    fun `test qt attached property`() = qt("import QtQuick\nimport QtQuick.Layouts\nColumnLayout { Item { Layout.<ref>fillWidth: true } }", "plugins.qmltypes")
    fun `test component on completed`() = qt("import QtQuick\nItem { Component.<ref>onCompleted: {} }", "plugins.qmltypes")
    fun `test grouped qt property`() = qt("import QtQuick\nItem { anchors.<ref>fill: parent }", "plugins.qmltypes")
    fun `test javascript global`() = qt("import QtQuick\nItem { width: Math.<ref>max(1, 2) }", "jsroot.qmltypes")
    fun `test parent member`() = qt("import QtQuick\nItem { Item { width: parent.<ref>width } }", "plugins.qmltypes")
    fun `test qt enum value`() = qt("import QtQuick\nText { horizontalAlignment: Text.<ref>AlignHCenter }", "plugins.qmltypes")

    // ---------------------------------------------------------------- editing

    fun `test rename property follows bindings and handlers`() {
        myFixture.configureByText("Main.qml", "Item {\n  id: root\n  property int cou<caret>nt: 0\n  count: 1\n  onCountChanged: console.log(root.count)\n}\n")
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("Item {\n  id: root\n  property int total: 0\n  total: 1\n  onTotalChanged: console.log(root.total)\n}\n")
    }

    fun `test rename id`() {
        myFixture.configureByText("Main.qml", "Item {\n  id: ro<caret>ot\n  Item { width: root.width }\n}\n")
        myFixture.renameElementAtCaret("main")
        myFixture.checkResult("Item {\n  id: main\n  Item { width: main.width }\n}\n")
    }

    fun `test find usages of a property include its change handler`() {
        val all = declarations("Item {\n  id: root\n  property int count: 0\n  count: 1\n  onCountChanged: console.log(root.count)\n}\n")
        assertEquals(listOf("count", "onCountChanged", "root.count"), usages(all.single { it.name == "count" }))
    }

    fun `test find usages of a signal include its handler`() {
        val all = declarations("Item {\n  id: root\n  signal moved(int x)\n  onMoved: {}\n  Item { width: root.moved(1) }\n}\n")
        assertEquals(listOf("onMoved", "root.moved"), usages(all.single { it.name == "moved" }))
    }

    fun `test id is only used in its document`() {
        myFixture.addFileToProject("Other.qml", "Item {\n  id: root\n  Item { width: root.width }\n}\n")
        val root = declarations("Item {\n  id: root\n  Item { width: root.width; height: root.height }\n}\n").single()
        assertEquals(LocalSearchScope(myFixture.file), root.useScope)
        assertEquals(listOf("root", "root"), usages(root))
    }

    fun `test rename document renames its uses`() {
        val use = myFixture.addFileToProject("Main.qml", "Item {\n  MyButton {}\n  property MyButton other\n}\n")
        myFixture.renameElement(myFixture.addFileToProject("MyButton.qml", "Item {}\n"), "Knob.qml")
        assertEquals("Item {\n  Knob {}\n  property Knob other\n}\n", use.text)
    }

    fun `test rename enum value follows javascript`() {
        myFixture.configureByText("Main.qml", "Item {\n  enum Mode { Off, O<caret>n }\n  property int m: Main.On\n}\n")
        myFixture.renameElementAtCaret("Lit")
        myFixture.checkResult("Item {\n  enum Mode { Off, Lit }\n  property int m: Main.Lit\n}\n")
    }

    fun `test rename inline component follows its uses`() {
        myFixture.configureByText("Main.qml", "Item {\n  component Ca<caret>rd: Item {}\n  Card {}\n  property Card first\n}\n")
        myFixture.renameElementAtCaret("Tile")
        myFixture.checkResult("Item {\n  component Tile: Item {}\n  Tile {}\n  property Tile first\n}\n")
    }

    fun `test rename signal follows its handler`() {
        myFixture.configureByText("Main.qml", "Item {\n  signal mov<caret>ed(int x)\n  onMoved: {}\n}\n")
        myFixture.renameElementAtCaret("dragged")
        myFixture.checkResult("Item {\n  signal dragged(int x)\n  onDragged: {}\n}\n")
    }

    // ---------------------------------------------------------------- real code (read-only, optional)

    /** LITE_QML_PROJECT=<dir>: resolution rate of every QML name (types, bindings, JavaScript); LITE_SHOW=1 lists misses. */
    fun `test whole project`() {
        val dir = System.getenv("LITE_QML_PROJECT")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().filter { it.isFile && it.extension in setOf("qml", "js") || it.name == "qmldir" }.toList()
        val added = files.map { myFixture.addFileToProject("proj/" + it.relativeTo(dir).path, it.readText()) }
        var total = 0; val unresolved = mutableMapOf<String, Int>(); val where = mutableListOf<String>()
        val started = System.nanoTime()
        added.filterIsInstance<QmlFile>().forEach { f ->
            PsiTreeUtil.collectElements(f) { true }.forEach { e ->
                if (e.firstChild == null) return@forEach
                val ref = e.reference as? PsiPolyVariantReference ?: return@forEach
                if (e is me.kevinlot.lite.typescript.psi.TsReferenceExpr && e.qualifier != null && e.qualifier !is me.kevinlot.lite.typescript.psi.TsReferenceExpr) return@forEach
                total++
                if (ref.multiResolve(false).isEmpty()) {
                    unresolved.merge(ref.canonicalText, 1, Int::plus)
                    where += "${f.name}:${f.text.substring(0, e.textOffset).count { it == '\n' } + 1} ${e.text.take(50)}"
                }
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("QMLPROJECT ${total - unresolved.values.sum()}/$total resolved in $ms ms; unresolved: " + unresolved.entries.sortedByDescending { it.value }.take(40).joinToString { "${it.key}×${it.value}" })
        if (System.getenv("LITE_SHOW") != null) println(where.take(60).joinToString("\n"))
    }

    private val typeInfo = arrayOf(
        "Shapes/qmldir" to "module Shapes\ntypeinfo plugins.qmltypes",
        "Shapes/plugins.qmltypes" to """
            <def>Module {
                Component {
                    name: "ShapeItem"
                    exports: ["Shapes/Shape 1.0"]
                    Enum { name: "Mode"; values: ["Off", "On"] }
                    Property { name: "size"; type: "int" }
                }
            }
        """,
    )

    fun `test type description property`() = check(*typeInfo, "Main.qml" to "import Shapes\nShape { <ref>size: 1 }")
    fun `test type description enum value`() = check(*typeInfo, "Main.qml" to "import Shapes\nShape { property int mode: Shape.<ref>On }")

    private val gauges = arrayOf(
        "Gauges/qmldir" to "module Gauges\ntypeinfo plugins.qmltypes",
        "Gauges/plugins.qmltypes" to """
            <def>Module {
                Component {
                    name: "GaugeItem"
                    exports: ["Gauges/Gauge 1.0"]
                    Property { name: "level"; type: "int" }
                    Signal { name: "overflowed" }
                    Method { name: "reset" }
                }
            }
        """,
    )

    fun `test type description member from javascript`() = check(
        *gauges, "Main.qml" to "import Gauges\nItem {\n  Gauge { id: gauge }\n  Item { width: gauge.<ref>level }\n}",
    )
    fun `test type description method from javascript`() = check(
        *gauges, "Main.qml" to "import Gauges\nItem {\n  Gauge { id: gauge }\n  Item { visible: gauge.<ref>reset() }\n}",
    )
    fun `test type description property change handler`() = check(*gauges, "Main.qml" to "import Gauges\nGauge { <ref>onLevelChanged: {} }")
    fun `test type description signal handler`() = check(*gauges, "Main.qml" to "import Gauges\nGauge { <ref>onOverflowed: {} }")
    fun `test type description type`() = check(*gauges, "Main.qml" to "import Gauges\nItem { <ref>Gauge {} }")

    fun `test deeply nested objects`() {
        val depth = 20_000
        val file = myFixture.configureByText("Deep.qml", "Item { ".repeat(depth) + "}".repeat(depth))
        assertEquals("Item", (file as QmlFile).rootObject?.typeName)
    }

    /** LITE_QML_CORPUS=<dir>: every .qml / .qmltypes must parse. */
    fun `test corpus parses`() {
        val dirs = System.getenv("LITE_QML_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val files = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile && (it.extension == "qml" || it.extension == "qmltypes") }.toList() }
        val broken = files.mapNotNull { f ->
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: return@mapNotNull null
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let {
                "${f.path}:${psi.text.substring(0, it.textOffset).count { c -> c == '\n' } + 1}: ${it.errorDescription} '${psi.text.substring(it.textOffset).take(40).replace('\n', ' ')}'"
            }
        }
        println("qml corpus: ${files.size - broken.size}/${files.size} files without errors\n" + broken.take(25).joinToString("\n"))
        assertTrue(broken.size * 100 <= files.size)
    }
}
