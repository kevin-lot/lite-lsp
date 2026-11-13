package me.kevinlot.lite.rust

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.rust.cargo.CargoProjects
import me.kevinlot.lite.rust.cargo.CargoWorkspace

/** External crates: only what `cargo metadata` declares as dependencies of the current crate. */
class RsCargoTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            CargoProjects.getInstance(project).setWorkspaceForTests(CargoWorkspace.EMPTY)
        } finally {
            super.tearDown()
        }
    }

    private fun metadata(root: String) = """
        {"workspace_members": ["app 0.1.0"],
         "packages": [
           {"id": "app 0.1.0", "name": "app", "edition": "2021", "manifest_path": "$root/app/Cargo.toml",
            "targets": [{"kind": ["bin"], "name": "app", "src_path": "$root/app/src/main.rs"}]},
           {"id": "my-dep 1.0.0", "name": "my-dep", "edition": "2021", "manifest_path": "$root/registry/my-dep/Cargo.toml",
            "targets": [{"kind": ["lib"], "name": "my-dep", "src_path": "$root/registry/my-dep/src/lib.rs"}]},
           {"id": "other 1.0.0", "name": "other", "edition": "2021", "manifest_path": "$root/registry/other/Cargo.toml",
            "targets": [{"kind": ["lib"], "name": "other", "src_path": "$root/registry/other/src/lib.rs"}]}
         ],
         "resolve": {"nodes": [
           {"id": "app 0.1.0", "deps": [{"name": "my_dep", "pkg": "my-dep 1.0.0"}]},
           {"id": "my-dep 1.0.0", "deps": [{"name": "other", "pkg": "other 1.0.0"}]},
           {"id": "other 1.0.0", "deps": []}
         ]}}
    """.trimIndent()

    private fun setup(main: String): Pair<Int, List<com.intellij.psi.PsiElement>> {
        myFixture.addFileToProject("registry/my-dep/src/lib.rs", "pub mod model; pub use other::Shared;")
        myFixture.addFileToProject("registry/my-dep/src/model.rs", "pub struct Thing; impl Thing { pub fn run(&self) {} }")
        myFixture.addFileToProject("registry/other/src/lib.rs", "pub struct Shared;")
        val offset = main.indexOf("<ref>")
        myFixture.addFileToProject("app/src/main.rs", main.replace("<ref>", ""))
        // Real paths are file:// urls; the light test file system is temp://.
        val root = myFixture.tempDirFixture.getFile("")!!.url
        val ws = CargoProjects.parseMetadata(metadata("/ROOT"), null)
        fun fix(u: String) = u.replace("file:///ROOT", root)
        CargoProjects.getInstance(project).setWorkspaceForTests(
            CargoWorkspace(ws.crates.map { c -> c.copy(rootUrl = fix(c.rootUrl), dependencies = c.dependencies.mapValues { fix(it.value) }) },
                ws.packageDirs.map(::fix), null),
        )
        val file = myFixture.configureFromTempProjectFile("app/src/main.rs")
        if (offset < 0) return offset to emptyList()
        val ref = file.findReferenceAt(offset) as PsiPolyVariantReference
        return offset to ref.multiResolve(false).mapNotNull { it.element }
    }

    private fun assertTarget(targets: List<com.intellij.psi.PsiElement>, file: String, name: String) =
        assertTrue(targets.map { it.containingFile.virtualFile.path }.toString(),
            targets.any { it.containingFile.virtualFile.path.endsWith(file) && (it as? PsiNameIdentifierOwner)?.name == name })

    fun `test dependency is renamed with underscores`() {
        val (_, t) = setup("use my_dep::model::<ref>Thing; fn main() {}")
        assertTarget(t, "registry/my-dep/src/model.rs", "Thing")
    }

    fun `test method of a dependency type`() {
        val (_, t) = setup("use my_dep::model::Thing; fn main() { let t = Thing; t.<ref>run(); }")
        assertTarget(t, "registry/my-dep/src/model.rs", "run")
    }

    fun `test reexport from a transitive dependency`() {
        val (_, t) = setup("fn main() { let _: my_dep::<ref>Shared; }")
        assertTarget(t, "registry/other/src/lib.rs", "Shared")
    }

    /** `mod handler;` commented out in main.rs: the orphan file still sees the crate's dependencies. */
    fun `test orphan file uses dependencies of its directory crate`() {
        myFixture.addFileToProject("app/src/handler.rs", "use my_dep::model::Thing;")
        setup("fn main() {}")
        val file = myFixture.configureFromTempProjectFile("app/src/handler.rs")
        val ref = file.findReferenceAt(file.text.indexOf("Thing")) as PsiPolyVariantReference
        assertTarget(ref.multiResolve(false).mapNotNull { it.element }, "registry/my-dep/src/model.rs", "Thing")
    }

    fun `test transitive dependency is not nameable directly`() {
        val (_, t) = setup("fn main() { let _: other::<ref>Shared; }")
        assertEmpty(t)
    }
}
