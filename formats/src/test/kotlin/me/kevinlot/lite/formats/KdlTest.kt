package me.kevinlot.lite.formats

class KdlTest : FmtTestCase() {

    private fun List<Pair<Cat?, String>>.of(cat: Cat) = filter { it.first == cat }.map { it.second }

    fun `test node name after a brace or a semicolon`() =
        tokens("a.kdl", "keybinds {\n    bind \"Ctrl g\" { SwitchToMode \"Locked\"; Detach; }\n}\n").let {
            assertEquals(listOf("keybinds", "bind", "SwitchToMode", "Detach"), it.of(Cat.KEY))
        }

    fun `test property with spaces around the equals sign`() = tokens("a.kdl", "pane size = 1 name=\"x\" other\n").let {
        assertEquals(listOf("size", "name"), it.of(Cat.ATTRIBUTE))
        assertEquals(listOf("pane"), it.of(Cat.KEY))
    }
}
