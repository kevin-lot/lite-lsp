package me.kevinlot.lite.proto

import com.intellij.openapi.actionSystem.IdeActions
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

    private fun annotated(code: String): String {
        myFixture.configureByText("a.proto", code)
        return myFixture.doHighlighting().filter { it.forcedTextAttributesKey?.externalName?.startsWith("PB_") == true }.sortedBy { it.startOffset }
            .joinToString(" ") { "${it.text}:${it.forcedTextAttributesKey.externalName.removePrefix("PB_")}" }
    }

    private fun definitions(code: String): List<PbDefinition> =
        PsiTreeUtil.findChildrenOfType(myFixture.configureByText("a.proto", code), PbDefinition::class.java).toList()

    private fun lexed(code: String): String {
        val highlighter = PbSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer.also { it.start(code) }
        val tokens = generateSequence { lexer.tokenType?.let { type -> (lexer.tokenText to highlighter.getTokenHighlights(type)).also { lexer.advance() } } }
        return tokens.mapNotNull { (text, keys) -> keys.firstOrNull()?.let { "$text:${it.externalName.removePrefix("PB_")}" } }.joinToString(" ")
    }

    private fun referenceAt(code: String): PsiPolyVariantReference {
        val file = myFixture.configureByText("a.proto", code.replace("<ref>", ""))
        return generateSequence(file.findElementAt(code.indexOf("<ref>"))) { it.parent }.takeWhile { it !is PsiFile }
            .firstNotNullOf { it.reference } as PsiPolyVariantReference
    }

    private val sample = """
        syntax = "proto2";
        package shapes;
        message Shape {
          optional group Corner = 1 { optional int32 x = 2; }
          oneof form { string name = 3; }
          map<string, int32> tags = 4;
          enum Kind { KIND_A = 0; }
        }
        service Shapes { rpc Get(Shape) returns (Shape); }
    """.trimIndent()

    fun `test parses an edition file`() {
        val file = myFixture.configureByText("a.proto", """
            edition = "2023";
            package shapes;
            option features.field_presence = IMPLICIT;
            message Shape {
              string name = 1 [features.field_presence = EXPLICIT];
              repeated int32 sides = 2 [features.repeated_field_encoding = EXPANDED];
            }
        """.trimIndent())
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { "${it.errorDescription} at ${it.textOffset}" })
    }

    fun `test parses proto3`() {
        val file = myFixture.configureByText("a.proto", """
            syntax = "proto3";
            package shapes.v1;
            import "google/protobuf/timestamp.proto";
            message Shape {
              optional string name = 1;
              repeated double sides = 2 [packed = true];
              google.protobuf.Timestamp drawn_at = 3;
              reserved 4, 9 to 11;
              reserved "old_name";
            }
            service Shapes {
              rpc Watch(stream Shape) returns (stream Shape);
            }
        """.trimIndent())
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { "${it.errorDescription} at ${it.textOffset}" })
    }

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

    fun `test absolute name without a package`() = one("message <def>Id {}\nmessage Outer {\n  message Id {}\n  .<ref>Id id = 1;\n}")
    fun `test cyclic imports end`() = check(
        "a.proto" to "import \"b.proto\";\nmessage <def>A {}",
        "b.proto" to "import \"a.proto\";\nmessage B { <ref>A a = 1; }",
    )
    fun `test enclosing scope two levels up`() = one("message A {\n  message <def>B {\n    message C { <ref>B b = 1; }\n  }\n}")
    fun `test extension declared in a message`() = check(
        "opts.proto" to "package acme;\nimport \"google/protobuf/descriptor.proto\";\n" +
            "message Opts { extend google.protobuf.FieldOptions { optional bool <def>secret = 50000; } }",
        "m.proto" to "package acme;\nimport \"opts.proto\";\nmessage M { optional string pwd = 1 [(acme.Opts.<ref>secret) = true]; }",
    )
    fun `test field named like a package does not hide it`() = check(
        "common.proto" to "package acme.common;\nmessage <def>Id {}",
        "api.proto" to "package acme.api;\nimport \"common.proto\";\nmessage M {\n  string common = 1;\n  common.<ref>Id id = 2;\n}",
    )
    fun `test field named like a type does not hide it`() = one("message <def>Status {}\nmessage M {\n  string Status = 1;\n  <ref>Status other = 2;\n}")
    fun `test first scope knowing the first component decides`() {
        val reference = referenceAt("message Outer { message Inner {} }\nmessage M {\n  message Outer {}\n  Outer.<ref>Inner inner = 1;\n}")
        assertEmpty(reference.multiResolve(false).toList())
    }
    fun `test group is a type`() = one(
        "syntax = \"proto2\";\nmessage M {\n  optional group <def>Result = 1 { optional string url = 2; }\n  repeated <ref>Result more = 3;\n}",
    )
    fun `test import public is followed`() = check(
        "types.proto" to "message <def>Id {}",
        "all.proto" to "import public \"types.proto\";",
        "api.proto" to "import \"all.proto\";\nmessage M { <ref>Id id = 1; }",
    )
    fun `test import reference covers the path and is not renamed`() {
        val reference = referenceAt("import \"<ref>missing/types.proto\";")
        assertEquals("missing/types.proto", reference.rangeInElement.substring(reference.element.text))
        assertEmpty(reference.multiResolve(false).toList())
        assertSame(reference.element, reference.handleElementRename("other.proto"))
        assertEquals("import \"missing/types.proto\";", myFixture.file.text)
    }
    fun `test keyword named message`() = one("message <def>message {}\nservice S { rpc Get(<ref>message) returns (message); }")
    fun `test message option`() = one(
        "import \"google/protobuf/descriptor.proto\";\nextend google.protobuf.MessageOptions { optional bool <def>audited = 50001; }\n" +
            "message M { option (<ref>audited) = true; }",
    )
    fun `test oneof field type`() = one("message <def>Id {}\nmessage M { oneof key { <ref>Id id = 1; string name = 2; } }")
    fun `test rpc streamed response type`() = one("message Req {}\nmessage <def>Res {}\nservice S { rpc Get(stream Req) returns (stream <ref>Res); }")
    fun `test type of a nested extension`() = one(
        "message Base { extensions 100 to max; }\nmessage <def>Id {}\nmessage M { extend Base { optional <ref>Id id = 100; } }",
    )
    fun `test well-known import`() {
        val target = referenceAt("import \"<ref>google/protobuf/duration.proto\";").multiResolve(false).single().element as PbFile
        assertEquals("duration.proto", target.name)
        assertEquals(listOf("Duration"), PbResolver.ownSymbols(target).filter { it.kind == Kind.TYPE }.map { it.fullName.substringAfterLast('.') })
    }

    // ---------------------------------------------------------------- highlighting and names

    fun `test comment line`() {
        myFixture.configureByText("a.proto", "message <caret>M {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("//message M {}\n")
    }

    fun `test declarations and type names are colored by kind`() = assertEquals(
        "Corner:TYPE_DECL int32:KEYWORD x:FIELD form:FIELD string:KEYWORD name:FIELD string:KEYWORD int32:KEYWORD tags:FIELD Kind:TYPE_DECL " +
            "KIND_A:ENUM_VALUE Shapes:TYPE_DECL Get:METHOD Shape:TYPE_REF Shape:TYPE_REF",
        annotated(sample).removePrefix("Shape:TYPE_DECL "),
    )

    fun `test full names follow protoc`() = assertEquals(
        listOf(
            "shapes.Shape", "shapes.Shape.Corner", "shapes.Shape.Corner.x", "shapes.Shape.form", "shapes.Shape.name", "shapes.Shape.tags",
            "shapes.Shape.Kind", "shapes.Shape.KIND_A", "shapes.Shapes", "shapes.Shapes.Get",
        ),
        definitions(sample).map { it.fullName },
    )

    fun `test names may be keywords but not paths`() {
        val validator = PbNamesValidator()
        listOf("message", "Shape_2", "_private").forEach { assertTrue(it, validator.isIdentifier(it, project)) }
        listOf("", "2d", "shapes.Shape", "a-b").forEach { assertFalse(it, validator.isIdentifier(it, project)) }
        assertFalse(validator.isKeyword("message", project))
    }

    fun `test option names are colored`() = assertEquals(
        "java_package:OPTION M:TYPE_DECL (acme.audited):OPTION string:KEYWORD name:FIELD deprecated:OPTION",
        annotated("option java_package = \"acme\";\nmessage M {\n  option (acme.audited) = true;\n  string name = 1 [deprecated = true];\n}"),
    )

    fun `test tokens are colored`() = assertEquals(
        "syntax:KEYWORD \"proto3\":STRING // shapes:LINE_COMMENT /* all of them */:BLOCK_COMMENT message:KEYWORD 1:NUMBER 2.5:NUMBER",
        lexed("syntax = \"proto3\"; // shapes\n/* all of them */\nmessage M { Id id = 1 [weight = 2.5]; }"),
    )

    fun `test usage view names each kind`() {
        val provider = PbFindUsagesProvider()
        assertEquals(
            listOf("message", "group", "field", "oneof", "field", "field", "enum", "enum value", "service", "rpc"),
            definitions(sample).map { provider.getType(it) },
        )
        assertEquals("Shape", provider.getDescriptiveName(definitions(sample).first()))
        assertFalse(provider.canFindUsagesFor(myFixture.file))
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

    fun `test find usages of a nested message`() {
        val file = myFixture.configureByText(
            "a.proto",
            "message Outer {\n  message Inner {}\n  Inner own = 1;\n}\nmessage M {\n  Outer.Inner inner = 1;\n  map<string, Outer.Inner> named = 2;\n}\n",
        )
        val inner = PsiTreeUtil.findChildrenOfType(file, PbDefinition::class.java).single { it.name == "Inner" }
        assertEquals(3, ReferencesSearch.search(inner, GlobalSearchScope.projectScope(project)).findAll().size)
    }

    fun `test find usages of an extension include its option names`() {
        val opts = myFixture.addFileToProject(
            "opts.proto",
            "package acme;\nimport \"google/protobuf/descriptor.proto\";\nextend google.protobuf.FieldOptions { optional bool secret = 50000; }\n",
        )
        myFixture.addFileToProject(
            "m.proto", "package acme;\nimport \"opts.proto\";\nmessage M {\n  string pwd = 1 [(acme.secret) = true];\n  string pin = 2 [(secret) = true];\n}\n",
        )
        val secret = PsiTreeUtil.findChildrenOfType(opts, PbDefinition::class.java).single()
        assertEquals(2, ReferencesSearch.search(secret, GlobalSearchScope.projectScope(project)).findAll().size)
    }

    fun `test rename extension follows option names`() {
        val use = myFixture.addFileToProject("m.proto", "package acme;\nimport \"opts.proto\";\nmessage M { string pwd = 1 [(acme.secret) = true]; }\n")
        myFixture.configureByText(
            "opts.proto",
            "package acme;\nimport \"google/protobuf/descriptor.proto\";\nextend google.protobuf.FieldOptions { optional bool sec<caret>ret = 50000; }\n",
        )
        myFixture.renameElementAtCaret("hidden")
        assertEquals("package acme;\nimport \"opts.proto\";\nmessage M { string pwd = 1 [(acme.hidden) = true]; }\n", use.text)
    }

    fun `test rename nested message from a qualified use`() {
        myFixture.configureByText("a.proto", "message Outer {\n  message Inner {}\n  Inner own = 1;\n}\nmessage M { Outer.In<caret>ner inner = 1; }\n")
        myFixture.renameElementAtCaret("Part")
        myFixture.checkResult("message Outer {\n  message Part {}\n  Part own = 1;\n}\nmessage M { Outer.Part inner = 1; }\n")
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
                total++
                if (PbResolver.resolve(it).isNotEmpty()) { resolved++; return@forEach }
                if (System.getenv("LITE_SHOW") != null) println("  unresolved ${f.name}: ${it.text}")
            }
        }
        println("proto corpus: ${files.size - broken}/${files.size} files without errors, $resolved/$total names resolved\n" + errors.take(15).joinToString("\n"))
    }
}
