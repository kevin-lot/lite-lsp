package me.kevinlot.lite.formats

class MakeTest : FmtTestCase() {
    private fun links(text: String) = Fmt.links(myFixture.configureByText("Makefile", text))
    private fun declared(text: String) = links(text).decls.map { it.key }.sorted()
    private fun referenced(text: String) = links(text).refs.flatMap { it.keys }.sorted()

    fun `test indented assignment`() = check("Makefile" to "ifeq ($(OS),Darwin)\n  <def>CC := clang\nendif\nall:\n\t$(<ref>CC) main.c\n")
    fun `test indented define`() = check("Makefile" to "ifdef X\n  define <def>greet\n\techo hi\n  endef\nendif\nall:\n\t$(call <ref>greet)\n")
    fun `test indented include`() = check("mac.mk" to "x = 1", "Makefile" to "ifeq (a,b)\n  include <ref>mac.mk\nendif\n")
    fun `test indented include shares its variables`() = check(
        "mac.mk" to "<def>CC := clang",
        "Makefile" to "ifeq (a,b)\n  include mac.mk\nendif\nall:\n\t$(<ref>CC) main.c\n",
    )
    fun `test indented rule`() = check("Makefile" to "ifdef X\n  <def>build:\n\techo\nendif\nall: <ref>build\n")
    fun `test recipe lines declare nothing`() =
        assertEquals(listOf("target:all"), declared("all:\n\tCC=gcc make\n\tdocker run \\\n    FOO=bar image\n\tcurl http://x \\\n  y: z\n"))

    fun `test continued prerequisites`() =
        check("Makefile" to ".PHONY: build \\\n  test \\\n  <ref>clean\nbuild:\n\techo\ntest:\n\techo\n<def>clean:\n\techo\n")
    fun `test continuation is not a prerequisite`() =
        assertEquals(listOf("target:build", "target:clean", "target:test"), referenced(".PHONY: build \\\n  test \\\n  clean\n"))

    fun `test target-specific variable`() = check("Makefile" to "test: <def>DEBUG := 1\ntest:\n\techo $(<ref>DEBUG)\n")
    fun `test exported target-specific variable`() = check("Makefile" to "test: export <def>GOFLAGS = -race\ntest:\n\tgo test $(<ref>GOFLAGS)\n")

    fun `test function call is not a rule`() = assertEquals(emptyList<String>(), declared("$(info Building: all)\n$(info $(X): y)\n"))
    fun `test define body is not a rule`() = assertEquals(listOf("var:help"), declared("define help\nUsage: make build\nendef\n"))
    fun `test grouped targets`() = assertEquals(listOf("target:a", "target:b"), declared("a b &: c\n\techo\n"))
    fun `test conditional and assignment are not rules`() =
        assertEquals(listOf("var:X", "var:Y"), declared("ifeq ($(A),b:c)\nX ::= 1\nelse ifeq ($(A),d:e)\nY :::= 2\nendif\n"))

    fun `test target with a variable`() =
        check("Makefile" to "$(BUILD_DIR)<def>/app: main.o\n\tcc -o $@ $^\nrun: $(BUILD_DIR)<ref>/app\n\t$<\n")
    fun `test variable inside a target`() = check("Makefile" to "<def>BUILD_DIR := out\n$(<ref>BUILD_DIR)/app: main.o\n\tcc\n")
    fun `test function call is not a prerequisite`() =
        assertEquals(listOf("target:main.o", "var:SRCS"), referenced("app: $(patsubst %.c,%.o,$(SRCS)) $(wildcard src/*.c) main.o\n"))

    fun `test export several names`() = check("Makefile" to "<def>A := 1\nB := 2\nexport <ref>A B\n")
    fun `test unexport several names`() = check("Makefile" to "A := 1\n<def>B := 2\nunexport A <ref>B # not for children\n")
    fun `test exported assignment is no reference`() = assertEquals(emptyList<String>(), referenced("export A = 1\nexport B ?= 2\n"))
}
