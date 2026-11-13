package me.kevinlot.lite.formats

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class FmtTest : BasePlatformTestCase() {

    /** <ref> resolves to <def> (or to the first file when it has no <def>: a path). */
    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val refs = generateSequence(file.findElementAt(refOffset)) { it.parent }.takeWhile { it !is PsiFile }
            .flatMap { e -> e.references.filter { it.rangeInElement.shiftRight(e.textRange.startOffset).containsOffset(refOffset) }.asSequence() }.toList()
        assertFalse("no reference at <ref>", refs.isEmpty())
        val targets = refs.mapNotNull { it.resolve() }
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }}", targets.any { t ->
            if (t is com.intellij.psi.PsiFileSystemItem) {
                val expected = defFile ?: files.first().first
                // a folder: the one holding the expected file
                return@any if (t is com.intellij.psi.PsiDirectory) "/src/$expected".startsWith(t.virtualFile.path + "/") else t.virtualFile.path.endsWith(expected)
            }
            val off = (t as? FmtWord)?.decl?.range?.startOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile!!) && off == defOffset
        })
    }

    private fun tokens(name: String, text: String): List<Pair<Cat?, String>> {
        val format = (myFixture.configureByText(name, text) as FmtFile).format
        val lexer = format.lexer()
        lexer.start(text)
        val out = mutableListOf<Pair<Cat?, String>>()
        var last = 0
        while (lexer.tokenType != null) {
            assertEquals("gap before ${lexer.tokenStart}", last, lexer.tokenStart)
            out += (lexer.tokenType as? FmtToken)?.cat to text.substring(lexer.tokenStart, lexer.tokenEnd)
            last = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, last)
        return out.filter { it.second.isNotBlank() }
    }

    private fun assertToken(tokens: List<Pair<Cat?, String>>, cat: Cat, text: String) =
        assertTrue("no $cat '$text' in $tokens", tokens.any { it.first == cat && it.second == text })

    // ---------------------------------------------------------------- coloring

    fun `test toml`() = tokens("Cargo.toml", "[package]\nname = \"lite\" # c\n[dependencies.serde]\nversion = '1'\nfeatures = [\"derive\"]\nwhen = 2024-01-01T10:00:00Z\n").let {
        assertToken(it, Cat.SECTION, "[package]"); assertToken(it, Cat.KEY, "name"); assertToken(it, Cat.STRING, "\"lite\"")
        assertToken(it, Cat.COMMENT, "# c"); assertToken(it, Cat.CONSTANT, "2024-01-01T10:00:00Z")
    }
    fun `test yaml`() = tokens("ci.yml", "on:\n  push:\n    branches: [main] # c\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - run: |\n          echo hi\n").let {
        assertToken(it, Cat.KEY, "runs-on"); assertToken(it, Cat.KEY, "uses"); assertToken(it, Cat.COMMENT, "# c"); assertToken(it, Cat.OPERATOR, "|")
    }
    fun `test kdl`() = tokens("config.kdl", "layout {\n    pane size=1 borderless=#true\n    // c\n}\n").let {
        assertToken(it, Cat.KEY, "layout"); assertToken(it, Cat.ATTRIBUTE, "size"); assertToken(it, Cat.CONSTANT, "#true")
    }
    fun `test ini`() = tokens(".editorconfig", "root = true\n[*.kt]\nindent_size = 4\n; c\n").let {
        assertToken(it, Cat.SECTION, "[*.kt]"); assertToken(it, Cat.KEY, "indent_size"); assertToken(it, Cat.COMMENT, "; c")
    }
    fun `test json5`() = tokens("a.json5", "{\n  // c\n  key: 'v',\n  \"q\": Infinity,\n}\n").let {
        assertToken(it, Cat.KEY, "key"); assertToken(it, Cat.KEY, "\"q\""); assertToken(it, Cat.CONSTANT, "Infinity")
    }
    fun `test sql`() = tokens("q.sql", "SELECT id, count(*) FROM users u -- c\nWHERE u.name = 'x' AND age > :min;\n").let {
        assertToken(it, Cat.KEYWORD, "SELECT"); assertToken(it, Cat.FUNCTION, "count"); assertToken(it, Cat.STRING, "'x'"); assertToken(it, Cat.VARIABLE, ":min")
    }
    fun `test csv columns`() = tokens("a.csv", "name;age;city\nann;31;\"Paris; FR\"\n").let {
        assertToken(it, Cat.COL0, "ann"); assertToken(it, Cat.COL1, "31"); assertToken(it, Cat.COL2, "\"Paris; FR\"")
    }

    // ---------------------------------------------------------------- Ctrl+Click

    fun `test yaml anchor`() = check("a.yml" to "base: &<def>defaults\n  a: 1\nprod:\n  <<: *<ref>defaults\n")
    fun `test env variable`() = check(".env" to "<def>HOST=localhost\nURL=http://\${<ref>HOST}:8080\n")
    fun `test hocon substitution`() = check("application.conf" to "db {\n  <def>host = \"localhost\"\n}\nurl = \"jdbc://\"\${db.<ref>host}\n")
    fun `test hocon key of an included conf file`() = check(
        "defaults.conf" to "app {\n  http {\n    <def>host = \"localhost\"\n  }\n}\n",
        "application.conf" to "include \"defaults.conf\"\nurl = \"http://\"\${app.http.<ref>host}\n",
    )
    fun `test hocon include`() = check("base.conf" to "a = 1", "application.conf" to "include \"<ref>base.conf\"\n")
    fun `test dockerfile arg`() = check("Dockerfile" to "ARG <def>VERSION=1\nFROM node:\${<ref>VERSION}\n")
    fun `test dockerfile stage`() = check("Dockerfile" to "FROM node AS <def>build\nRUN make\nFROM nginx\nCOPY --from=<ref>build /app /usr/share\n")
    fun `test css custom property across files`() = check("vars.css" to ":root { <def>--brand: #333; }", "app.css" to "a { color: var(<ref>--brand); }")
    fun `test css import`() = check("base.css" to "a{}", "app.css" to "@import \"<ref>base.css\";")
    fun `test scss variable`() = check("a.scss" to "$<def>gap: 4px;\n.x { margin: $<ref>gap; }")
    fun `test scss mixin from partial`() = check("_mixins.scss" to "@mixin <def>card { padding: 1px; }", "app.scss" to "@use \"mixins\";\n.x { @include <ref>card; }")
    fun `test sass variable`() = check("a.sass" to "$<def>gap: 4px\n.card\n  margin: $<ref>gap\n")
    fun `test sass shorthand mixin`() = check("a.sass" to "=<def>rounded\n  border-radius: 4px\n.card\n  +<ref>rounded\n")
    fun `test sass mixin from partial`() = check("_mixins.sass" to "@mixin <def>card\n  padding: 1px", "app.sass" to "@use \"mixins\"\n.x\n  @include <ref>card\n")
    fun `test sass coloring`() = tokens("a.sass", "// c\n\$gap: 4px\n.card\n  color: red\n  +rounded\n").let {
        assertToken(it, Cat.COMMENT, "// c"); assertToken(it, Cat.KEY, "color"); assertToken(it, Cat.FUNCTION, "+rounded"); assertToken(it, Cat.TYPE, ".card")
    }
    fun `test gitignore folder`() = check("build/out.txt" to "x", ".gitignore" to "# c\n/<ref>build/\n*.log\n")
    fun `test gitignore negated file`() = check("config.local.json" to "{}", ".gitignore" to "*.json\n!<ref>config.local.json\n")
    fun `test dockerignore`() = check("node_modules/a.js" to "", ".dockerignore" to "<ref>node_modules\n")
    fun `test ignore coloring`() = tokens(".gitattributes", "# c\n*.png binary -diff\n!keep\n").let {
        assertToken(it, Cat.COMMENT, "# c"); assertToken(it, Cat.KEY, "*.png"); assertToken(it, Cat.ATTRIBUTE, "-diff"); assertToken(it, Cat.OPERATOR, "!")
    }
    fun `test make variable`() = check("Makefile" to "<def>CC := gcc\nall:\n\t$(<ref>CC) main.c\n")
    fun `test make target`() = check("Makefile" to "all: <ref>build\n<def>build:\n\techo\n")
    fun `test make include`() = check("rules.mk" to "x = 1", "Makefile" to "include <ref>rules.mk\n")
    fun `test make substitution reference`() = check("Makefile" to "<def>SRCS := a.c\nOBJS := $(<ref>SRCS:.c=.o)\n")
    fun `test make call`() = check("Makefile" to "define <def>greet\n\techo $(1)\nendef\nall:\n\t$(call <ref>greet,world)\n")
    fun `test make ifdef`() = check("Makefile" to "<def>DEBUG ?= 1\nifdef <ref>DEBUG\nX = 1\nendif\n")
    fun `test make prerequisite file`() = check("src/main.c" to "int main;", "Makefile" to "main.o: <ref>src/main.c\n\tcc -c $<\n")
    fun `test make phony`() = check("Makefile" to ".PHONY: <ref>test\n<def>test:\n\techo\n")
    fun `test make shell variable is not make`() {
        myFixture.configureByText("Makefile", "HOME := x\nall:\n\techo $$" + "{HOME}\n")
        val refs = Fmt.links(myFixture.file).refs
        assertTrue(refs.toString(), refs.none { "var:HOME" in it.keys })
    }
    fun `test make variable of the including makefile`() = check(
        "Makefile" to "<def>APP := lite\ninclude build/common.mk\n",
        "build/common.mk" to "show:\n\techo $(<ref>APP)\n",
    )
    fun `test make coloring`() = tokens("Makefile", "CC := gcc\nall: main.o\n\t$(CC) $(wildcard *.c) $$" + "HOME\n").let {
        assertToken(it, Cat.DECL, "CC"); assertToken(it, Cat.FUNCTION, "$(wildcard"); assertToken(it, Cat.VARIABLE, "$(CC)")
    }

    fun `test just dependency`() = check("justfile" to "<def>build:\n    cargo build\ntest: <ref>build\n    cargo test\n")
    fun `test just quiet recipe and dependency with arguments`() = check("justfile" to "@<def>greet who:\n    echo {{who}}\nall: (<ref>greet \"x\")\n")
    fun `test just variable in body`() = check("justfile" to "<def>version := \"1.0\"\nrelease:\n    git tag v{{ <ref>version }}\n")
    fun `test just parameter wins over variable`() = check("justfile" to "target := \"x\"\nbuild <def>target=\"debug\":\n    echo {{<ref>target}}\n")
    fun `test just exported parameter`() = check("justfile" to "run \$<def>PORT=\"8080\":\n    ./serve --port \$<ref>PORT\n")
    fun `test just variable in expression`() = check("justfile" to "<def>root := justfile_directory()\nout := <ref>root / \"out\"\n")
    fun `test just alias`() = check("justfile" to "<def>build:\n    make\nalias b := <ref>build\n")
    fun `test just recipe from an import`() = check("common.just" to "<def>lint:\n    echo", "justfile" to "import 'common.just'\nci: <ref>lint\n")
    fun `test just import`() = check("common.just" to "x := '1'", "justfile" to "import '<ref>common.just'\n")
    fun `test just module`() = check("docs/mod.just" to "x := '1'", "justfile" to "mod <ref>docs\n")
    fun `test just function call is not a variable`() {
        myFixture.configureByText("justfile", "os := os()\nx := env_var('HOME')\n")
        assertTrue(Fmt.links(myFixture.file).refs.none { it.keys.any { k -> k == "var:os" || k == "var:env_var" } })
    }
    fun `test just coloring`() = tokens("justfile", "set shell := [\"bash\", \"-c\"]\n# c\n[private]\n@build target='x': lint\n    #!/usr/bin/env bash\n    echo {{target}}\n").let {
        assertToken(it, Cat.KEYWORD, "set"); assertToken(it, Cat.KEY, "shell"); assertToken(it, Cat.META, "[private]")
        assertToken(it, Cat.DECL, "@build"); assertToken(it, Cat.VARIABLE, "{{target}}"); assertToken(it, Cat.META, "#!/usr/bin/env bash")
    }
    fun `test rename just recipe`() {
        myFixture.configureByText("justfile", "bu<caret>ild:\n    make\ntest: build\nalias b := build\n")
        myFixture.renameElementAtCaret("compile")
        myFixture.checkResult("compile:\n    make\ntest: compile\nalias b := compile\n")
    }

    private val oas3 = """
        openapi: 3.1.0
        paths:
          /pets/{id}:
            get:
              responses:
                '200':
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/<ref>Pet'
        components:
          schemas:
            <def>Pet:
              type: object
    """
    fun `test openapi 3 local ref`() = check("api.yaml" to oas3)
    fun `test openapi ref to a path item`() = check("api.yaml" to "openapi: 3.0.0\npaths:\n  /pets/{id}:\n    <def>get: {}\nx:\n  \$ref: '#/paths/~1pets~1{id}/<ref>get'\n")
    fun `test openapi unquoted ref to another file`() = check(
        "schemas/common.yaml" to "<def>Error:\n  type: object\n",
        "api.yaml" to "openapi: 3.0.0\ncomponents:\n  responses:\n    E:\n      \$ref: schemas/common.yaml#/<ref>Error\n",
    )
    fun `test openapi ref to a whole file`() = check("pet.yaml" to "type: object", "api.yaml" to "schema:\n  \$ref: './<ref>pet.yaml'\n")
    fun `test openapi flow ref in a sequence`() = check("api.yaml" to "components:\n  schemas:\n    <def>Cat: {}\n    Pet:\n      oneOf:\n        - { \$ref: '#/components/schemas/<ref>Cat' }\n")
    fun `test openapi discriminator mapping`() = check("api.yaml" to "components:\n  schemas:\n    <def>Dog: {}\n    Pet:\n      discriminator:\n        mapping:\n          dog: '#/components/schemas/<ref>Dog'\n")
    fun `test swagger 2 definitions`() = check("swagger.yaml" to "swagger: '2.0'\ndefinitions:\n  <def>Tag:\n    type: object\n  Pet:\n    properties:\n      tags:\n        type: array\n        items:\n          \$ref: '#/definitions/<ref>Tag'\n")
    fun `test swagger 2 bare model name`() = check("swagger.yaml" to "swagger: '2.0'\ndefinitions:\n  <def>Tag: {}\nx:\n  \$ref: <ref>Tag\n")
    fun `test yaml pointer into a sequence`() = check("a.yaml" to "tags:\n  - name: a\n  - <def>name: b\nx:\n  \$ref: '#/tags/1/<ref>name'\n")
    fun `test yaml keys after a block scalar`() = check("a.yaml" to "info:\n  description: |\n    text: not a key\n  <def>title: x\ny:\n  \$ref: '#/info/<ref>title'\n")
    fun `test json openapi local ref`() = check("api.json" to "{\"openapi\": \"3.0.0\", \"components\": {\"schemas\": {<def>\"Pet\": {}}},\n \"x\": {\"\$ref\": \"#/components/schemas/<ref>Pet\"}}")
    fun `test json ref to a yaml file`() = check("pet.yaml" to "<def>Pet:\n  type: object\n", "api.json" to "{\"x\": {\"\$ref\": \"pet.yaml#/<ref>Pet\"}}")
    fun `test yaml ref to a json file`() = check("defs.json" to "{<def>\"Pet\": {}}", "api.yaml" to "x:\n  \$ref: 'defs.json#/<ref>Pet'\n")
    fun `test swagger 1 models`() = check("pet.json" to "{\"swaggerVersion\": \"1.2\", \"models\": {<def>\"Tag\": {\"id\": \"Tag\"},\n \"Pet\": {\"properties\": {\"tags\": {\"type\": \"array\", \"items\": {\"\$ref\": \"<ref>Tag\"}}}}}}")
    fun `test swagger 1 type`() = check("pet.json" to "{\"swaggerVersion\": \"1.2\", \"models\": {<def>\"Category\": {},\n \"Pet\": {\"properties\": {\"category\": {\"type\": \"<ref>Category\"}}}}}")
    fun `test rename openapi schema`() {
        val other = myFixture.addFileToProject("other.json", "{\"\$ref\": \"api.yaml#/components/schemas/Pet\"}")
        myFixture.configureByText("api.yaml", "components:\n  schemas:\n    P<caret>et: {}\n    List:\n      items:\n        \$ref: '#/components/schemas/Pet'\n")
        myFixture.renameElementAtCaret("Animal")
        myFixture.checkResult("components:\n  schemas:\n    Animal: {}\n    List:\n      items:\n        \$ref: '#/components/schemas/Animal'\n")
        assertEquals("{\"\$ref\": \"api.yaml#/components/schemas/Animal\"}", other.text)
    }
    fun `test find usages of an openapi schema`() {
        myFixture.addFileToProject("a.yaml", "x:\n  \$ref: 'api.yaml#/components/schemas/Pet'\n")
        myFixture.addFileToProject("b.json", "{\"\$ref\": \"api.yaml#/components/schemas/Pet\"}")
        val api = myFixture.addFileToProject("api.yaml", "components:\n  schemas:\n    Pet: {}\n    L:\n      \$ref: '#/components/schemas/Pet'\n")
        val pet = PsiTreeUtil.findChildrenOfType(api, FmtWord::class.java).first { it.name == "Pet" }
        assertEquals(3, ReferencesSearch.search(pet, GlobalSearchScope.projectScope(project)).findAll().size)
    }

    fun `test terraform variable across files`() = check("vars.tf" to "variable \"<def>region\" {}", "main.tf" to "provider \"aws\" { region = var.<ref>region }")
    fun `test terraform local`() = check("main.tf" to "locals {\n  <def>name = \"x\"\n  tags = { a = 1 }\n}\noutput \"o\" { value = local.<ref>name }")
    fun `test terraform resource`() = check("main.tf" to "resource \"aws_s3_bucket\" \"<def>logs\" {}\noutput \"o\" { value = aws_s3_bucket.<ref>logs.arn }")
    fun `test terraform interpolation`() = check("main.tf" to "variable \"<def>env\" {}\nlocals { n = \"app-\${var.<ref>env}\" }")
    fun `test terraform module source`() {
        myFixture.addFileToProject("modules/vpc/main.tf", "x = 1")
        val main = myFixture.addFileToProject("main.tf", "module \"vpc\" { source = \"./modules/vpc\" }")
        val ref = PsiTreeUtil.findChildrenOfType(main, FmtWord::class.java).flatMap { it.references.toList() }.first { it is FmtPathReference }
        assertEquals("vpc", (ref.resolve() as com.intellij.psi.PsiDirectory).name)
    }
    fun `test tfvars`() = check("variables.tf" to "variable \"<def>region\" {}", "prod.tfvars" to "<ref>region = \"eu-west-3\"")
    fun `test terragrunt dependency`() = check("terragrunt.hcl" to "dependency \"<def>vpc\" { config_path = \"../vpc\" }\ninputs = { id = dependency.<ref>vpc.outputs.id }")

    // ---------------------------------------------------------------- editing

    fun `test rename hocon key`() {
        myFixture.configureByText("application.conf", "db {\n  ho<caret>st = \"x\"\n}\nurl = \${db.host}\n")
        myFixture.renameElementAtCaret("server")
        myFixture.checkResult("db {\n  server = \"x\"\n}\nurl = \${db.server}\n")
    }

    fun `test rename terraform variable across files`() {
        val main = myFixture.addFileToProject("main.tf", "provider \"aws\" { region = var.region }\n")
        myFixture.configureByText("vars.tf", "variable \"reg<caret>ion\" {}\n")
        myFixture.renameElementAtCaret("aws_region")
        myFixture.checkResult("variable \"aws_region\" {}\n")
        assertEquals("provider \"aws\" { region = var.aws_region }\n", main.text)
    }

    fun `test rename make variable`() {
        myFixture.configureByText("Makefile", "C<caret>C := gcc\nall:\n\t$(CC) main.c\n")
        myFixture.renameElementAtCaret("COMPILER")
        myFixture.checkResult("COMPILER := gcc\nall:\n\t$(COMPILER) main.c\n")
    }

    fun `test find usages of a custom property`() {
        val vars = myFixture.addFileToProject("vars.css", ":root { --brand: #333; }\n")
        myFixture.addFileToProject("a.css", "a { color: var(--brand); }\n")
        myFixture.addFileToProject("b.css", "b { color: var(--brand); border-color: var(--brand); }\n")
        val decl = PsiTreeUtil.findChildrenOfType(vars, FmtWord::class.java).first { it.decl != null }
        assertEquals(3, ReferencesSearch.search(decl, GlobalSearchScope.projectScope(project)).findAll().size)
        assertEquals(1, ReferencesSearch.search(decl, GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("a.css")))).findAll().size)
    }

    // ---------------------------------------------------------------- HTML

    /** The official CSS/JavaScript plugins are loaded in tests (they parse `<style>`/`<script>` themselves): the choice is tested. */
    fun `test html hosts`() {
        val file = myFixture.configureByText("a.html", "<html><body style=\"margin: 0\" onclick=\"go()\" title=\"t\"></body></html>")
        val values = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.xml.XmlAttributeValue::class.java).toList()
        assertEquals(listOf("LiteCss", "LiteTypeScript", null), values.map { HtmlInjector().languageFor(it) })
    }

    // ---------------------------------------------------------------- real files (read-only, optional)

    /** LITE_FMT_CORPUS=<dir:dir>: every file of a format lexes (contiguous tokens) and links without error. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_FMT_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val counts = sortedMapOf<String, Int>()
        var total = 0; var resolved = 0; val misses = mutableListOf<String>()
        val started = System.nanoTime()
        for (d in dirs) for (f in d.walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", "target", "build", ".gradle", ".dart_tool") }.filter { it.isFile && it.length() < 2_000_000 }) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) ?: continue
            val psi = psiManager.findFile(vf) as? FmtFile ?: continue
            val lexer = psi.format.lexer()
            lexer.start(psi.text)
            var last = 0
            while (lexer.tokenType != null) { assertEquals(f.path, last, lexer.tokenStart); last = lexer.tokenEnd; lexer.advance() }
            assertEquals(f.path, psi.textLength, last)
            val links = Fmt.links(psi)
            for (r in links.refs.map { it.range } + links.paths.map { it.range }) {
                val word = PsiTreeUtil.getParentOfType(psi.findElementAt(r.startOffset), FmtWord::class.java) ?: continue
                val refs = word.references.filter { it.rangeInElement.shiftRight(word.textRange.startOffset) == r }
                total++
                if (refs.any { (it as com.intellij.psi.PsiPolyVariantReference).multiResolve(false).isNotEmpty() }) resolved++
                else misses += "${f.path}: ${r.substring(psi.text)}"
            }
            counts.merge(psi.format.displayName, 1, Int::plus)
        }
        println("fmt corpus in ${(System.nanoTime() - started) / 1_000_000} ms: $counts; links $resolved/$total resolved\n" + misses.take(40).joinToString("\n"))
    }
}
