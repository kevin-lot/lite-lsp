package me.kevinlot.lite.shell

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile

private const val PREFIX_LENGTH = 64

private val SHEBANG = Regex("""^#![^\n]*\b(?:ash|bash|dash|ksh|sh|zsh)\b""")

/**
 * A script without extension (`bin/deploy`) is recognised by its `#!` line. The `hashBangs` attribute of a file type
 * matches substrings, so `sh` would also claim `#!/usr/bin/env fish`: the interpreter is matched as a whole word here.
 */
class ShShebangDetector : FileTypeRegistry.FileTypeDetector {
    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val firstLine = firstCharsIfText?.lineSequence()?.firstOrNull() ?: return null
        return ShFileType.takeIf { file.extension == null && SHEBANG.containsMatchIn(firstLine) }
    }

    override fun getDesiredContentPrefixLength(): Int = PREFIX_LENGTH
}
