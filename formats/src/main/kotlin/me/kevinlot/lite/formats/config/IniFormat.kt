package me.kevinlot.lite.formats.config

import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Rule

/** INI and its relatives: `.editorconfig`, `.gitconfig`, systemd units, `.desktop`. */
object Ini : Format("Ini", "INI", "ini") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule("""[;#!][^\n]*""", Cat.COMMENT, lineStart = true),
        Rule("""\[[^\]\n]*]""", Cat.SECTION, lineStart = true),
        Rule("""[^=:\s\[;#!][^=:\n]*?(?=\s*[=:])""", Cat.KEY, lineStart = true),
        Rule(R.DQ, Cat.STRING),
        Rule("""\$\{[^}\n]*}|%\([\w.-]+\)s""", Cat.VARIABLE),
        Rule("""(?i)\b(?:true|false|yes|no|on|off)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}
