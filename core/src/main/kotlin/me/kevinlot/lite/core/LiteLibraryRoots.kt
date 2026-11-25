package me.kevinlot.lite.core

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

object LiteLibraryRoots {
    // `invokeLater(action, expired)` alone means ModalityState.any(), where the platform refuses model changes
    internal val modality: ModalityState get() = ModalityState.nonModal()

    /** Tells the platform that the roots of the library [libraryId] changed, then drops what was resolved against the old ones. */
    fun changed(project: Project, name: String, old: Collection<VirtualFile>, new: Collection<VirtualFile>, libraryId: String) {
        ApplicationManager.getApplication().invokeLater({
            WriteAction.run<RuntimeException> { AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, name, old, new, libraryId) }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, modality, project.disposed)
    }
}
