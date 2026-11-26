package me.kevinlot.lite.formats.config

import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Rule

object Kdl : Format("Kdl", "KDL", "kdl") {
    override val lineComment = "//"
    override val blockComment = "/*" to "*/"
    private const val NAME = """[^\s{}()\[\]=;"#/\\]"""
    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""/-""", Cat.COMMENT),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""r?(#+)"[\s\S]*?"\1""", Cat.STRING),
        Rule(R.DQ, Cat.STRING),
        Rule("""#(?:true|false|null|inf|-inf|nan)\b|\b(?:true|false|null)\b""", Cat.CONSTANT),
        Rule("""\([^)\n]*\)""", Cat.TYPE),
        Rule("""$NAME+(?=[ \t]*=)""", Cat.ATTRIBUTE),
        Rule(R.NUMBER, Cat.NUMBER),
        Rule("""$NAME+""", Cat.KEY, lineStart = true),
        Rule("""$NAME(?<=[{;][ \t]{0,20}.)$NAME*""", Cat.KEY),
    )
}
