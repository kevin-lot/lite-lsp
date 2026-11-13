package me.kevinlot.lite.rust.debug

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.rust.cargo.CargoProjects
import me.kevinlot.lite.rust.psi.RsPath
import me.kevinlot.lite.rust.psi.RsUseItem
import java.io.File

/** Dev tool: LITE_REAL_MAIN=<crate>/src/main.rs LITE_REAL_METADATA=<cargo metadata json>. Read-only. */
class RealProjectTest : BasePlatformTestCase() {
    fun `test resolve imports of a real crate`() {
        val main = System.getenv("LITE_REAL_MAIN") ?: return
        val json = File(System.getenv("LITE_REAL_METADATA") ?: return).readText()
        CargoProjects.getInstance(project).setWorkspaceForTests(CargoProjects.parseMetadata(json, null))
        val home = System.getProperty("user.home")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, File(main).parentFile.parentFile.parent, "$home/.cargo")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(main)
            ?: error("cannot open $main: exists=${File(main).exists()} readable=${File(main).canRead()} home=$home")
        val file = psiManager.findFile(vf)!!
        val out = StringBuilder("file=${file.javaClass.simpleName} len=${file.textLength} type=${file.fileType.name}\n")
        for (use in PsiTreeUtil.findChildrenOfType(file, RsUseItem::class.java)) {
            for (p in PsiTreeUtil.findChildrenOfType(use, RsPath::class.java)) {
                val ref = p.reference as? PsiPolyVariantReference ?: continue
                val t = ref.multiResolve(false).mapNotNull { it.element }
                out.append("${p.text.padEnd(40)} -> ${t.map { it.containingFile.virtualFile.path.substringAfter("index.crates.io-").substringAfter("/") + ":" + it.text.lineSequence().first().take(50) }}\n")
            }
        }
        println("REAL<<\n$out>>")
    }
}
