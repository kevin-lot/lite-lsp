package me.kevinlot.lite.formats

class TomlTest : FmtTestCase() {

    private fun List<Pair<Cat?, String>>.of(cat: Cat) = filter { it.first == cat }.map { it.second }

    fun `test nested array at the start of a line is no table header`() =
        tokens("a.toml", "deps = [\n  [\"a\", \"b\"],\n  [1, 2],\n]\n[table]\n  [table.sub] # c\n[[bin]]\n").let {
            assertEquals(listOf("[table]", "[table.sub]", "[[bin]]"), it.of(Cat.SECTION))
            assertEquals(listOf("\"a\"", "\"b\""), it.of(Cat.STRING))
            assertEquals(listOf("1", "2"), it.of(Cat.NUMBER))
        }

    fun `test single quoted key`() = tokens("a.toml", "'quoted' = 1\n\"dq\".'sq'.bare = 'v'\n").let {
        assertEquals(listOf("'quoted'", "\"dq\".'sq'.bare"), it.of(Cat.KEY))
        assertEquals(listOf("'v'"), it.of(Cat.STRING))
    }
}
