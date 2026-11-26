package me.kevinlot.lite.formats

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile

private const val PREFIX_LENGTH = 64

private val SHEBANG = Regex("""^#![^\n]*\bfish\b""")

/** A fish script without extension is recognised by its `#!` line (see ShShebangDetector for why not `hashBangs`). */
class FishShebangDetector : FileTypeRegistry.FileTypeDetector {
    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val firstLine = firstCharsIfText?.lineSequence()?.firstOrNull() ?: return null
        return FishFileType.takeIf { file.extension == null && SHEBANG.containsMatchIn(firstLine) }
    }

    override fun getDesiredContentPrefixLength(): Int = PREFIX_LENGTH
}
