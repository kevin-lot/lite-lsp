package me.kevinlot.lite.qml

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.qml.resolve.QmlEnvironment
import java.io.File

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
