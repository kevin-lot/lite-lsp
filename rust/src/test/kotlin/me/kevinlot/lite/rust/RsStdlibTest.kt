package me.kevinlot.lite.rust

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.rust.cargo.CargoProjects
import me.kevinlot.lite.rust.cargo.CargoWorkspace
import me.kevinlot.lite.rust.cargo.CrateInfo
import java.io.File

/**
 * Resolution against the real standard library sources.
 * Needs RUST_SRC_LIBRARY=<sysroot>/lib/rustlib/src/rust/library (skipped otherwise).
 */
class RsStdlibTest : BasePlatformTestCase() {
    private val library = System.getenv("RUST_SRC_LIBRARY")?.let(::File)?.takeIf { File(it, "std/src/lib.rs").exists() }

    override fun setUp() {
        super.setUp()
        val lib = library ?: return
        myFixture.testDataPath = lib.path
        if (myFixture.findFileInTempDir("stdlib/std/src/lib.rs") == null) {
            for (crate in listOf("std", "core", "alloc")) myFixture.copyDirectoryToProject("$crate/src", "stdlib/$crate/src")
        }
        val root = myFixture.tempDirFixture.getFile("")!!.url
        myFixture.addFileToProject("demo/src/main.rs", "")
        CargoProjects.getInstance(project).setWorkspaceForTests(
            CargoWorkspace(listOf(CrateInfo("demo", "$root/demo/src/main.rs", emptyMap(), "2021", true)), emptyList(), "$root/stdlib"),
        )
    }

    private fun check(code: String, fileSuffix: String, name: String) {
        if (library == null) return
        val offset = code.indexOf("<ref>")
        val text = code.replace("<ref>", "")
        val vf = myFixture.findFileInTempDir("demo/src/main.rs")!!
        com.intellij.openapi.application.WriteAction.run<Throwable> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, text) }
        val file = myFixture.configureFromTempProjectFile("demo/src/main.rs")
        val ref = file.findReferenceAt(offset) ?: error("no reference")
        val start = System.nanoTime()
        val targets = (ref as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
        val ms = (System.nanoTime() - start) / 1_000_000
        val ok = targets.any { it.containingFile.virtualFile.path.endsWith(fileSuffix) && (it as? PsiNameIdentifierOwner)?.name == name }
        assertTrue("resolved in $ms ms to ${targets.map { "${it.containingFile.virtualFile.path.substringAfter("stdlib/")}: ${it.text.take(50)}" }}", ok)
        println("resolve '$name' took $ms ms")
    }

    fun `test Vec new`() = check("fn main() { let v: Vec<i32> = Vec::<ref>new(); }", "alloc/src/vec/mod.rs", "new")
    fun `test Vec push`() = check("fn main() { let mut v: Vec<i32> = Vec::new(); v.<ref>push(1); }", "alloc/src/vec/mod.rs", "push")
    fun `test vec macro then method`() = check("fn main() { let v = vec![1, 2]; v.<ref>len(); }", "alloc/src/vec/mod.rs", "len")
    fun `test HashMap import`() = check("use std::collections::<ref>HashMap;", "std/src/collections/hash/map.rs", "HashMap")
    fun `test HashMap get`() = check(
        "use std::collections::HashMap; fn main() { let m: HashMap<String, i32> = HashMap::new(); m.<ref>get(\"a\"); }",
        "std/src/collections/hash/map.rs", "get",
    )
    fun `test Option method`() = check("fn main() { let o: Option<i32> = None; o.<ref>unwrap_or(0); }", "core/src/option.rs", "unwrap_or")
    fun `test Some from prelude`() = check("fn main() { let o = <ref>Some(1); }", "core/src/option.rs", "Some")
    fun `test String len`() = check("fn main() { let s = String::new(); s.<ref>len(); }", "alloc/src/string.rs", "len")
    fun `test String deref to str`() = check("fn main() { let s = String::new(); s.<ref>trim(); }", "core/src/str/mod.rs", "trim")
    fun `test Vec deref to slice`() = check("fn main() { let v: Vec<i32> = Vec::new(); v.<ref>iter(); }", "core/src/slice/mod.rs", "iter")
    fun `test iterator adapter`() = check(
        "fn main() { let v: Vec<i32> = Vec::new(); v.iter().<ref>map(|x| x); }",
        "core/src/iter/traits/iterator.rs", "map",
    )
    fun `test println macro`() = check("fn main() { <ref>println!(\"hi\"); }", "std/src/macros.rs", "println")
    fun `test format result`() = check("fn main() { format!(\"x\").<ref>len(); }", "alloc/src/string.rs", "len")

    /** Typing in a file must not re-resolve the standard library: warm resolution stays fast. */
    fun `test warm resolution after edits`() {
        if (library == null) return
        val code = "use std::collections::HashMap;\nfn main() {\n    let v: Vec<String> = Vec::new();\n    let m: HashMap<String, i32> = HashMap::new();\n" +
            "    v.iter().map(|s| s.len());\n    m.get(\"a\").unwrap_or(&0);\n    let s = String::from(\"x\");\n    s.trim().to_uppercase();\n}\n"
        val vf = myFixture.findFileInTempDir("demo/src/main.rs")!!
        com.intellij.openapi.application.WriteAction.run<Throwable> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, code) }
        myFixture.configureFromTempProjectFile("demo/src/main.rs")
        fun resolveAll(): Long {
            val t = System.nanoTime()
            PsiTreeUtil.processElements(myFixture.file) { e -> e.references.forEach { it.resolve() }; true }
            return (System.nanoTime() - t) / 1_000_000
        }
        val cold = resolveAll()
        val warm = (1..5).map {
            myFixture.editor.caretModel.moveToOffset(myFixture.file.textLength - 2)
            myFixture.type("\n    let x$it = 1;")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            resolveAll()
        }
        println("whole-file resolve: cold=$cold ms, after edits=$warm ms")
        assertTrue("warm resolution too slow: $warm", warm.max() < 1000)
    }

    /** Today's std must parse: lists the files that still contain a syntax error. */
    fun `test stdlib parses`() {
        if (library == null) return
        val root = myFixture.findFileInTempDir("stdlib")!!
        val files = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
        com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(root, null) { if (it.extension == "rs") files += it; true }
        val broken = files.mapNotNull { vf ->
            val psi = psiManager.findFile(vf)!!
            val err = PsiTreeUtil.findChildOfType(psi, com.intellij.psi.PsiErrorElement::class.java) ?: return@mapNotNull null
            val line = psi.text.substring(0, err.textOffset).count { it == '\n' } + 1
            "${vf.path.substringAfter("stdlib/")}:$line ${err.errorDescription}"
        }
        println("std parse: ${files.size - broken.size}/${files.size} files without errors\n" + broken.joinToString("\n"))
        assertTrue("${broken.size} files with errors:\n" + broken.take(30).joinToString("\n"), broken.size * 100 < files.size * 2)
    }

}
