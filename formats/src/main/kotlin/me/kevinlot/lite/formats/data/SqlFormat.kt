package me.kevinlot.lite.formats

object Sql : Format("Sql", "SQL", "sql") {
    override val lineComment = "--"
    override val blockComment = "/*" to "*/"
    override val rules = listOf(
        Rule("""--[^\n]*""", Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""'[^']*+(?:''[^']*+)*+'?""", Cat.STRING),
        Rule("""\$(\w*)\$[\s\S]*?\$\1\$""", Cat.STRING), // PostgreSQL $$ … $$
        Rule(""""[^"\n]*"|`[^`\n]*`|\[[^\]\n]*]""", Cat.KEY),
        Rule(
            """(?i)\b(?:select|from|where|and|or|not|insert|into|values|update|set|delete|create|alter|drop|table|view|index|unique|primary|foreign|key""" +
                """|references|constraint|default|null|is|in|exists|between|like|ilike|as|on|join|inner|left|right|full|outer|cross|natural|using|group|by""" +
                """|order|having|limit|offset|union|all|distinct|case|when|then|else|end|with|recursive|returning|begin|commit|rollback|transaction|grant""" +
                """|revoke|if|database|schema|sequence|trigger|function|procedure|returns|return|declare|language|replace|cascade|restrict|check|asc|desc""" +
                """|nulls|first|last|over|partition|window|filter|lateral|true|false|current_date|current_timestamp|interval|cast|to|do|nothing|conflict""" +
                """|truncate|explain|analyze|vacuum|use|show|describe)\b""",
            Cat.KEYWORD,
        ),
        Rule(
            """(?i)\b(?:int|integer|smallint|bigint|serial|bigserial|decimal|numeric|real|float|double|precision|char|varchar|character|varying|text""" +
                """|uuid|bool|boolean|date|time|timestamp|timestamptz|json|jsonb|bytea|blob|clob|money|inet|array)\b""",
            Cat.TYPE,
        ),
        Rule("""[A-Za-z_]\w*(?=\s*\()""", Cat.FUNCTION),
        Rule(""":\w+|\$\d+|\?|@\w+""", Cat.VARIABLE),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}
