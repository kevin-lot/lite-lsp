package me.kevinlot.lite.proto

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class PbTest : BasePlatformTestCase() {

    /** <ref> resolves to exactly <def> (or to the first file, for imports). */
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
        val ref = generateSequence(file.findElementAt(refOffset)) { it.parent }.takeWhile { it !is PsiFile }.mapNotNull { it.reference }.firstOrNull()
            as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        val describe = targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }
        if (defFile == null) return assertTrue("resolved to $describe", targets.singleOrNull() is PsiFile && (targets.single() as PsiFile).name == files.first().first.substringAfterLast('/'))
        assertEquals("resolved to $describe", 1, targets.size)
        val t = targets.single()
        val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
        assertTrue("resolved to $describe", t.containingFile.virtualFile.path.endsWith(defFile!!) && off == defOffset)
    }

    private fun one(code: String) = check("a.proto" to code)

    fun `test parses proto2 proto3 and editions`() {
        val file = myFixture.configureByText("a.proto", """
            syntax = "proto2";
            package demo.v1;
            import public "other.proto";
            option java_package = "com.demo";
            message Outer {
              option (custom.opt) = { a: 1 b: "x" nested { c: true } };
              required int64 id = 1 [default = 5, json_name = "ID"];
              optional group Result = 2 { optional string url = 3; }
              map<string, Inner> inners = 4;
              oneof choice { string name = 5; int32 code = 6; }
              message Inner { repeated .demo.v1.Outer parents = 1; }
              enum Kind { option allow_alias = true; KIND_A = 0; KIND_B = 1 [deprecated = true]; }
              reserved 10 to 20, 30;
              reserved "old";
              extensions 100 to max;
            }
            extend Outer { optional string tag = 100; }
            service Api {
              option deprecated = true;
              rpc Get(Outer) returns (stream Outer.Inner) { option idempotency_level = NO_SIDE_EFFECTS; }
            }
            message message { optional string enum = 1; }
        """.trimIndent())
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { "${it.errorDescription} at ${it.textOffset}" })
    }

    // ---------------------------------------------------------------- resolution

    fun `test field type`() = one("message <def>Id {}\nmessage User { Id <ref>Id = 1; }".replace("Id <ref>Id", "<ref>Id id"))
    fun `test nested type from inside`() = one("message Outer {\n  message <def>Inner {}\n  <ref>Inner inner = 1;\n}")
    fun `test nested type qualified`() = one("message Outer { message <def>Inner {} }\nmessage User { Outer.<ref>Inner inner = 1; }")
    fun `test qualifier segment is the outer message`() = one("message <def>Outer { message Inner {} }\nmessage User { <ref>Outer.Inner inner = 1; }")
    fun `test innermost scope wins`() = one("message Id {}\nmessage Outer {\n  message <def>Id {}\n  <ref>Id id = 1;\n}")
    fun `test absolute name`() = one("package p;\nmessage <def>Id {}\nmessage Outer {\n  message Id {}\n  .p.<ref>Id id = 1;\n}")
    fun `test imported package qualified`() = check(
        "common/types.proto" to "package acme.common;\nmessage <def>Id {}",
        "api/svc.proto" to "package acme.api;\nimport \"common/types.proto\";\nmessage User { acme.common.<ref>Id id = 1; }",
    )
    fun `test same name in two packages`() = check(
        "a.proto" to "package a;\nmessage Id {}",
        "b.proto" to "package b;\nmessage <def>Id {}",
        "main.proto" to "package main;\nimport \"a.proto\";\nimport \"b.proto\";\nmessage M { b.<ref>Id id = 1; }",
    )
    fun `test sibling package by partial name`() = check(
        "common.proto" to "package acme.common;\nmessage <def>Id {}",
        "api.proto" to "package acme.api;\nimport \"common.proto\";\nmessage M { common.<ref>Id id = 1; }",
    )
    fun `test package segment`() = check(
        "common.proto" to "package acme.<def>common;\nmessage Id {}",
        "api.proto" to "package acme.api;\nimport \"common.proto\";\nmessage M { acme.<ref>common.Id id = 1; }",
    )
    fun `test map value type`() = one("message <def>Point {}\nmessage M { map<string, <ref>Point> points = 1; }")
    fun `test rpc types`() = one("message <def>Req {}\nmessage Res {}\nservice S { rpc Get(<ref>Req) returns (stream Res); }")
    fun `test extend target`() = one("message <def>Base { extensions 100 to max; }\nextend <ref>Base { optional int32 x = 100; }")
    fun `test custom option`() = check(
        "opts.proto" to "package acme;\nimport \"google/protobuf/descriptor.proto\";\nextend google.protobuf.FieldOptions { optional bool <def>secret = 50000; }",
        "m.proto" to "package acme;\nimport \"opts.proto\";\nmessage M { optional string pwd = 1 [(acme.<ref>secret) = true]; }",
    )
    fun `test enum type`() = one("enum <def>Status { A = 0; }\nmessage M { <ref>Status s = 1; }")
    fun `test scalar is not resolved`() {
        myFixture.configureByText("a.proto", "message M { int32 n = 1; }")
        val path = PsiTreeUtil.findChildOfType(myFixture.file, PbSymbolPath::class.java)!!
        assertEmpty(PbResolver.resolve(path))
    }
    fun `test import`() = check("common/types.proto" to "message Id {}", "api/svc.proto" to "import \"<ref>common/types.proto\";")

    fun `test well-known type is bundled`() {
        myFixture.configureByText("a.proto", "import \"google/protobuf/timestamp.proto\";\nmessage M { google.protobuf.Timestamp at = 1; }")
        val path = PsiTreeUtil.findChildrenOfType(myFixture.file, PbSymbolPath::class.java).single { it.parent !is PbSymbolPath }
        assertEquals("Timestamp", (PbResolver.resolve(path).single() as PbDefinition).name)
    }

    // ---------------------------------------------------------------- editing

    fun `test rename message across files`() {
        val api = myFixture.addFileToProject("api.proto", "package p;\nimport \"types.proto\";\nmessage M { p.Id id = 1; Id other = 2; }\n")
        myFixture.configureByText("types.proto", "package p;\nmessage I<caret>d {}\n")
        myFixture.renameElementAtCaret("Identifier")
        myFixture.checkResult("package p;\nmessage Identifier {}\n")
        assertEquals("package p;\nimport \"types.proto\";\nmessage M { p.Identifier id = 1; Identifier other = 2; }\n", api.text)
    }

    fun `test find usages honours scope`() {
        val types = myFixture.addFileToProject("types.proto", "message Id {}\n")
        myFixture.addFileToProject("a.proto", "import \"types.proto\";\nmessage A { Id id = 1; }\n")
        myFixture.addFileToProject("b.proto", "import \"types.proto\";\nmessage B { Id id = 1; repeated Id ids = 2; }\n")
        val id = PsiTreeUtil.findChildOfType(types, PbDefinition::class.java)!!
        assertEquals(3, ReferencesSearch.search(id, GlobalSearchScope.projectScope(project)).findAll().size)
        assertEquals(1, ReferencesSearch.search(id, GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("a.proto")))).findAll().size)
    }

    // ---------------------------------------------------------------- real files (read-only, optional)

    /** LITE_PROTO_CORPUS=<dir>: every .proto parses; type names resolve (scalars excepted). */
    fun `test corpus`() {
        val dir = System.getenv("LITE_PROTO_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "proto" }.toList()
        var broken = 0; var total = 0; var resolved = 0
        val errors = mutableListOf<String>()
        for (f in files) {
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: continue
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let { broken++; errors += "${f.path}: ${it.errorDescription}" }
            PsiTreeUtil.findChildrenOfType(psi, PbSymbolPath::class.java).filter { it.parent !is PbSymbolPath && it.text !in PbResolver.SCALARS }.forEach {
                total++; if (PbResolver.resolve(it).isNotEmpty()) resolved++ else if (System.getenv("LITE_SHOW") != null) println("  unresolved ${f.name}: ${it.text}")
            }
        }
        println("proto corpus: ${files.size - broken}/${files.size} files without errors, $resolved/$total names resolved\n" + errors.take(15).joinToString("\n"))
    }
}
