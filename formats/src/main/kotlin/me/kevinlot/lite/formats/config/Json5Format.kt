package me.kevinlot.lite.formats.config

import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Rule

object Json5 : Format("Json5", "JSON5", "json5") {
    override val lineComment = "//"
    override val blockComment = "/*" to "*/"
    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""(?:"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"|'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'|[A-Za-z_$][\w$]*)(?=\s*:)""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""\b(?:true|false|null|Infinity|NaN)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}
