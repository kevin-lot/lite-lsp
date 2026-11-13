package me.kevinlot.lite.core

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.util.io.VoidDataExternalizer

/**
 * "Key -> files" index for one language: values are not stored, matching files are re-read
 * (and their PSI cached) on query. Enough for names, packages and impl/extension lookups.
 */
abstract class LiteKeysIndex<F : PsiFile>(private val fileType: LanguageFileType, private val fileClass: Class<F>) :
    FileBasedIndexExtension<String, Void>() {

    abstract fun keys(file: F): Collection<String>

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { input ->
        val file = input.psiFile
        if (!fileClass.isInstance(file)) emptyMap() else keys(fileClass.cast(file)).associateWith { null }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer() = VoidDataExternalizer.INSTANCE
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(fileType)
    override fun dependsOnFileContent() = true
    override fun getVersion() = 1

    fun files(project: Project, key: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): Collection<VirtualFile> =
        FileBasedIndex.getInstance().getContainingFiles(name, key, scope)
}
