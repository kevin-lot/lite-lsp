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
import me.kevinlot.lite.formats.template.Handlebars
import me.kevinlot.lite.formats.template.HtmlInjector
import me.kevinlot.lite.formats.template.Jinja
import me.kevinlot.lite.formats.template.JinjaLexer

class FmtTest : FmtTestCase() {

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
    fun `test dockerfile arg`() = check("Containerfile" to "ARG <def>VERSION=1\nFROM node:\${<ref>VERSION}\n")
    fun `test dockerfile stage`() = check("Containerfile" to "FROM node AS <def>build\nRUN make\nFROM nginx\nCOPY --from=<ref>build /app /usr/share\n")
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
    fun `test make coloring`() = tokens("Makefile", "CC := gcc\nOBJ := $(wildcard *.c) $(CC)\nall: main.o\n\t$(CC) $$" + "HOME\n").let {
        assertToken(it, Cat.DECL, "CC"); assertToken(it, Cat.FUNCTION, "$(wildcard"); assertToken(it, Cat.VARIABLE, "$(CC)")
        assertToken(it, Cat.TEXT, "$(CC) $$" + "HOME")
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
        assertToken(it, Cat.DECL, "@build"); assertToken(it, Cat.TEXT, "#!/usr/bin/env bash\n    echo {{target}}")
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

    fun `test terraform module output`() = check(
        "modules/naming/main.tf" to "output \"<def>bucket\" { value = \"b\" }",
        "main.tf" to "module \"naming\" {\n  source = \"./modules/naming\"\n}\nlocals {\n  b = module.naming[0].<ref>bucket\n}\n",
    )
    fun `test terraform module argument`() = check(
        "modules/naming/variables.tf" to "variable \"<def>prefix\" {}",
        "main.tf" to "variable \"prefix\" {}\nmodule \"naming\" {\n  source = \"./modules/naming\"\n  <ref>prefix = var.prefix\n}\n",
    )
    fun `test terraform downloaded module`() = check(
        "stack/.terraform/modules/db/terraform/variables.tf" to "variable \"<def>size\" {}",
        "stack/.terraform/modules/modules.json" to "{\"Modules\":[{\"Key\":\"\",\"Dir\":\".\"},{\"Key\":\"db\",\"Dir\":\".terraform/modules/db/terraform\"}]}",
        "stack/main.tf" to "module \"db\" {\n  source = \"git@github.com:acme/db.git//terraform?ref=1.0.0\"\n  <ref>size   = 1\n}\n",
    )
    private val terragruntUnit = "terraform {\n  source = \"\${get_parent_terragrunt_dir()}/../stacks//api\"\n}\ninclude \"root\" {\n  path   = find_in_parent_folders(\"root.hcl\")\n  expose = true\n}\n"
    fun `test terragrunt input through the included root`() = check(
        "deploy/stacks/api/variables.tf" to "variable \"<def>app_version\" {}",
        "deploy/live/root.hcl" to "locals {\n  environment = \"dev\"\n}\n",
        "deploy/live/api/terragrunt.hcl" to terragruntUnit + "inputs = {\n  <ref>app_version = \"1\"\n  nested = { app_version = 2 }\n}\n",
    )
    fun `test terragrunt find in parent folders`() = check(
        "deploy/live/root.hcl" to "locals {}",
        "deploy/live/api/terragrunt.hcl" to "include \"root\" {\n  path = find_in_parent_folders(\"<ref>root.hcl\")\n}\n",
    )
    fun `test terragrunt source with directory functions`() = check(
        "deploy/stacks/api/main.tf" to "x = 1",
        "deploy/live/root.hcl" to "locals {}",
        "deploy/live/api/terragrunt.hcl" to "include \"root\" {\n  path = find_in_parent_folders(\"root.hcl\")\n}\nterraform {\n  source = \"\${get_parent_terragrunt_dir()}/../stacks//<ref>api\"\n}\n",
    )
    fun `test terragrunt included local`() = check(
        "deploy/live/root.hcl" to "locals {\n  <def>environment = \"dev\"\n}\n",
        "deploy/live/api/terragrunt.hcl" to terragruntUnit + "locals {\n  env = include.root.locals.<ref>environment\n}\n",
    )
    fun `test terragrunt include block`() = check("live/root.hcl" to "locals {}", "live/api/terragrunt.hcl" to "include \"<def>root\" {\n  path = find_in_parent_folders(\"root.hcl\")\n}\nlocals {\n  e = include.<ref>root.locals.e\n}\n")
    fun `test terragrunt dependency output`() = check(
        "deploy/stacks/base/outputs.tf" to "output \"<def>key\" { value = \"k\" }",
        "deploy/live/base/terragrunt.hcl" to "terraform {\n  source = \"../../stacks/base\"\n}\n",
        "deploy/live/api/terragrunt.hcl" to "dependency \"base\" {\n  config_path = \"../base\"\n}\ninputs = {\n  key = dependency.base.outputs.<ref>key\n}\n",
    )
    fun `test terraform registry source is not a path`() {
        val main = myFixture.addFileToProject("main.tf", "module \"vpc\" {\n  source = \"terraform-aws-modules/vpc/aws\"\n}\n")
        val paths = PsiTreeUtil.findChildrenOfType(main, FmtWord::class.java).flatMap { it.references.toList() }.filterIsInstance<FmtPathReference>()
        assertTrue(paths.isEmpty())
    }

    fun `test hocon substitution segment`() = check("application.conf" to "db {\n  <def>pool { size = 10 }\n}\nworkers = \${db.<ref>pool.size}\n")
    fun `test hocon dotted key declares each level`() = check("application.conf" to "<def>db.pool.size = 10\nworkers = \${<ref>db.pool.size}\n")
    fun `test hocon key of another file of the project`() = check(
        "conf/reference.conf" to "db.pool.<def>size = 1\n",
        "application.conf" to "workers = \${db.pool.<ref>size}\n",
    )
    fun `test hocon key of the file wins over another file`() = check(
        "reference.conf" to "db.size = 1\n",
        "application.conf" to "db.<def>size = 2\nworkers = \${db.<ref>size}\n",
    )
    fun `test hocon include without extension`() = check("conf/base.conf" to "a = 1", "application.conf" to "include \"conf/<ref>base\"\n")
    fun `test any conf file is hocon`() = check("config.conf" to "aws {\n  <def>region = \"eu-west-1\"\n}\nendpoint = \${aws.<ref>region}\n")
    fun `test hocon block opened after an equals sign`() = check("application.conf" to "db = {\n  <def>host = \"x\"\n}\nother : {\n  host = \"y\"\n}\nurl = \${db.<ref>host}\n")

    fun `test terragrunt include with a literal path`() = check(
        "live/root.hcl" to "locals {}",
        "live/api/terragrunt.hcl" to "include \"root\" {\n  path = \"../<ref>root.hcl\"\n}\n",
    )
    fun `test terraform two references in one string`() = check("main.tf" to "variable \"a\" {}\nvariable \"<def>b\" {}\nlocals { n = \"\${var.a}-\${var.<ref>b}\" }")
    fun `test dockerfile env pairs`() = check("Containerfile" to "FROM node\nENV A=1 <def>B=2\nRUN echo \$<ref>B\n")
    fun `test dockerfile env without an equals sign`() = check("Containerfile" to "FROM node\nENV <def>NAME some value\nRUN echo \$<ref>NAME\n")
    fun `test just module with an explicit path`() = check("documentation/x.just" to "x := '1'", "justfile" to "mod docs '<ref>documentation/x.just'\n")
    fun `test env single-quoted value is literal`() {
        myFixture.configureByText(".env", "HOST=x\nA='\$HOST'\nB=\$HOST\nC='it' \$HOST 's'\n")
        assertEquals(2, Fmt.links(myFixture.file).refs.size)
    }
    fun `test scss declaration is not a use of the variable`() {
        myFixture.configureByText("a.scss", "\$gap: 4px;\n.x { margin: \$gap; }\n")
        assertEquals(listOf(25),Fmt.links(myFixture.file).refs.map { it.range.startOffset })
    }
    fun `test csv doubled quote stays in the value`() = tokens("b.csv", "a,\"x \"\"y\"\", z\",c\n").let {
        assertToken(it, Cat.COL1, "\"x \"\"y\"\", z\""); assertToken(it, Cat.COL2, "c")
    }
    fun `test a stylesheet added later is seen`() {
        val app = myFixture.addFileToProject("app.css", "a { color: var(--brand); }")
        val reference = { PsiTreeUtil.findChildrenOfType(app, FmtWord::class.java).flatMap { it.references.toList() }.single() }
        assertNull(reference().resolve())
        myFixture.addFileToProject("vars.css", ":root { --brand: #333; }")
        assertNotNull(reference().resolve())
    }
    fun `test a hocon file added later is seen`() {
        val app = myFixture.addFileToProject("application.conf", "workers = \${db.size}\n")
        val reference = { PsiTreeUtil.findChildrenOfType(app, FmtWord::class.java).flatMap { it.references.toList() }.last() }
        assertNull(reference().resolve())
        myFixture.addFileToProject("conf/reference.conf", "db.size = 1\n")
        assertNotNull(reference().resolve())
    }

    fun `test fish function of an autoloaded file`() = check(
        "functions/greet.fish" to "function <def>greet\n  echo hi\nend\n",
        "other/greet_all.fish" to "function greet_all\n  echo all\nend\n",
        "main.fish" to "set -l who world\n<ref>greet \$who\n",
    )
    fun `test fish variable`() = check("main.fish" to "set -l <def>who world\necho \"hi \$<ref>who\"\n")
    fun `test fish later assignment refers to the first`() = check("main.fish" to "set <def>count 1\nset <ref>count 2\necho \$count\n")
    fun `test fish local variable of a function`() = check(
        "main.fish" to "set name global\nfunction greet\n  set -l <def>name local\n  if true\n    echo \$<ref>name\n  end\nend\necho \$name\n",
    )
    fun `test fish global variable seen from a function`() = check("main.fish" to "set -g <def>mode dev\nfunction show\n  echo \$<ref>mode\nend\n")
    fun `test fish function argument`() = check("main.fish" to "function greet -a <def>who greeting\n  echo \$greeting \$<ref>who\nend\n")
    fun `test fish loop variable`() = check("main.fish" to "function all\n  for <def>f in *.txt\n    echo \$<ref>f\n  end\nend\n")
    fun `test fish source`() = check("lib/common.fish" to "set x 1", "main.fish" to "source <ref>lib/common.fish\n")
    fun `test fish function of a sourced file`() = check(
        "lib/common.fish" to "function <def>log\n  echo \$argv\nend\n",
        "elsewhere/log.fish" to "function log\nend\n",
        "main.fish" to "source lib/common.fish\nif true; <ref>log start; end\n",
    )
    fun `test fish quoted text is not code`() {
        val file = myFixture.configureByText("main.fish", "set x 1\necho '\$x' # \$x\n")
        val refs = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).flatMap { it.references.toList() }
        assertTrue(refs.none { it.canonicalText.contains("x") && it.element.text.startsWith("'") })
    }
    fun `test fish script without extension`() {
        val file = myFixture.configureByText("deploy", "#!/usr/bin/env fish\nfunction helper\nend\nhelper\n")
        assertEquals("fish", (file as? FmtFile)?.format?.displayName)
    }
    fun `test fish coloring`() = tokens("a.fish", "# c\nfunction greet --description 'hi'\n  set -l who \$argv[1]\nend\n").let {
        assertToken(it, Cat.COMMENT, "# c"); assertToken(it, Cat.KEYWORD, "function"); assertToken(it, Cat.DECL, "greet")
        assertToken(it, Cat.ATTRIBUTE, "--description"); assertToken(it, Cat.STRING, "'hi'"); assertToken(it, Cat.KEY, "who"); assertToken(it, Cat.VARIABLE, "\$argv")
    }

    fun `test handlebars partial file`() = check("partials/footer.hbs" to "<footer/>", "views/page.hbs" to "<main>{{> <ref>footer year=2026}}</main>")
    fun `test handlebars partial with underscore and folder`() = check(
        "shared/_card.hbs" to "<div/>",
        "other/_card.hbs" to "<p/>",
        "views/page.hbs" to "{{> <ref>shared/card item}}",
    )
    fun `test handlebars nearest partial wins`() = check(
        "emails/welcome/header.hbs" to "<h1/>",
        "emails/reset/header.hbs" to "<h2/>",
        "emails/welcome/body.hbs" to "{{> <ref>header}}",
    )
    fun `test handlebars inline partial`() = check(
        "row.hbs" to "<p/>",
        "page.hbs" to "{{#*inline \"<def>row\"}}<li>{{name}}</li>{{/inline}}\n{{#each items}}{{> <ref>row}}{{/each}}\n",
    )
    fun `test handlebars partial block`() = check("layout.hbs" to "<html>{{> @partial-block}}</html>", "page.hbs" to "{{#> <ref>layout}}body{{/layout}}")
    fun `test handlebars block parameter`() = check("page.hbs" to "{{#each users as |<def>user index|}}<li>{{index}} {{<ref>user.name}}</li>{{/each}}")
    fun `test handlebars inner block parameter wins`() = check(
        "page.hbs" to "{{#each groups as |item|}}{{#each item.users as |<def>item|}}{{#if <ref>item.active}}x{{/if}}{{/each}}{{item.name}}{{/each}}",
    )
    fun `test handlebars block parameter as helper argument`() = check("page.hbs" to "{{#with user as |<def>u|}}{{format name=<ref>u.name}}{{/with}}")
    fun `test handlebars name outside its block is not a parameter`() {
        val file = myFixture.configureByText("page.hbs", "{{#each users as |user|}}{{/each}}{{user.name}} {{../user}} {{!-- {{> gone}} --}}")
        val refs = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).flatMap { it.references.toList() }
        assertTrue(refs.toString(), refs.isEmpty())
    }
    fun `test mustache partial file`() = check("partials/item.mustache" to "<li/>", "list.mustache" to "<ul>{{#items}}{{> <ref>item}}{{/items}}</ul>")
    fun `test handlebars coloring`() = tokens("a.hbs", "<a href=\"/x\" class=\"b {{kind}}\">{{!-- note --}}{{#if user.admin}}{{> badge size=2 label=\"A\"}}{{else}}{{{raw}}}{{/if}}</a>").let {
        assertToken(it, Cat.TAG, "<a"); assertToken(it, Cat.ATTRIBUTE, "href"); assertToken(it, Cat.STRING, "\"/x\""); assertToken(it, Cat.COMMENT, "{{!-- note --}}")
        assertToken(it, Cat.LBRACE, "{{"); assertToken(it, Cat.KEYWORD, "if"); assertToken(it, Cat.VARIABLE, "admin"); assertToken(it, Cat.FUNCTION, "badge")
        assertToken(it, Cat.ATTRIBUTE, "size"); assertToken(it, Cat.NUMBER, "2"); assertToken(it, Cat.STRING, "\"A\""); assertToken(it, Cat.KEYWORD, "else")
        assertToken(it, Cat.LBRACE, "{{{"); assertToken(it, Cat.RBRACE, "}}}"); assertToken(it, Cat.VARIABLE, "kind"); assertToken(it, Cat.TAG, "</a")
    }
    fun `test mustache coloring`() = tokens("a.mustache", "Hello {{name}}{{! greeting }}\n{{#items}}{{.}}{{/items}}{{^items}}none{{/items}}").let {
        assertToken(it, Cat.VARIABLE, "name"); assertToken(it, Cat.COMMENT, "{{! greeting }}"); assertToken(it, Cat.OPERATOR, "^"); assertToken(it, Cat.VARIABLE, "items")
        assertEquals(null, it.first { (_, text) -> text == "Hello" }.first?.key)
    }
    fun `test template lexer restarts inside a tag`() {
        val text = "<p>{{#each users as |user|}}{{user.name}}{{/each}}</p>"
        val lexer = Handlebars.lexer()
        lexer.start(text, text.indexOf("users"), text.length, 0)
        assertEquals(Cat.VARIABLE, (lexer.tokenType as FmtToken).cat)
        lexer.start(text, text.indexOf("</p>"), text.length, 0)
        assertEquals(Cat.TAG, (lexer.tokenType as FmtToken).cat)
    }

    private val flex = "package x;\n%%\n%class L\n%state STRING, COMMENT // lexing modes\n%xstate RAW\nDIGIT = [0-9]\nNUMBER = {DIGIT}+ (\".\" {DIGIT}+)?\nSTRING = \\\" [^\\\"]* \\\"\n%%\n" +
        "<YYINITIAL> {NUMBER} { return T.NUMBER; }\n<YYINITIAL> \"/*\" { yybegin(COMMENT); return T.STRING; }\n<STRING, RAW> {STRING} { push(RAW); }\n// {DIGIT} COMMENT\n"


    fun `test jflex macro`() = check("L.flex" to mark(flex, "|DIGIT =", "{^DIGIT}+ ("))
    fun `test jflex macro in a rule`() = check("L.flex" to mark(flex, "|NUMBER =", "<YYINITIAL> {^NUMBER}"))
    fun `test jflex macro named like a state`() = check("L.flex" to mark(flex, "|STRING = ", "RAW> {^STRING}"))
    fun `test jflex state in a rule`() = check("L.flex" to mark(flex, "%state |STRING", "<^STRING, RAW>"))
    fun `test jflex second state of a rule`() = check("L.flex" to mark(flex, "%xstate |RAW", "<STRING, ^RAW>"))
    fun `test jflex state in java code`() = check("L.flex" to mark(flex, "STRING, |COMMENT //", "yybegin(^COMMENT)"))
    fun `test jflex comments and token types are not names`() {
        val file = myFixture.configureByText("L.flex", flex)
        val refs = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).flatMap { word -> word.references.map { it.rangeInElement.shiftRight(word.textRange.startOffset).startOffset } }
        assertFalse(flex.indexOf("T.STRING") + 2 in refs)
        assertFalse(flex.indexOf("// {DIGIT}") + 4 in refs)
        assertFalse(flex.indexOf("lexing") in refs)
        assertEquals(listOf("macro:DIGIT", "macro:NUMBER", "macro:STRING", "state:STRING", "state:COMMENT", "state:RAW"), Fmt.links(file).decls.map { it.key })
    }
    fun `test jflex coloring`() = tokens("L.flex", flex).let {
        assertToken(it, Cat.META, "%%"); assertToken(it, Cat.META, "%state"); assertToken(it, Cat.DECL, "DIGIT"); assertToken(it, Cat.STRING, "[0-9]")
        assertToken(it, Cat.VARIABLE, "{DIGIT}"); assertToken(it, Cat.TYPE, "<STRING, RAW>"); assertToken(it, Cat.KEYWORD, "return"); assertToken(it, Cat.COMMENT, "// {DIGIT} COMMENT")
    }
    fun `test the lexers of this repository are read`() {
        val grammars = listOf("kotlin/src/main/grammars/Kotlin.flex", "protobuf/src/main/grammars/Proto.flex", "rust/src/main/grammars/RustLexer.flex")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        grammars.forEach { path ->
            val file = myFixture.configureByText(File(path).name, File(root, path).readText())
            val links = Fmt.links(file)
            assertTrue(path, links.decls.size > 10)
            val unresolved = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).flatMap { it.references.toList() }.filter { it.resolve() == null }
            assertTrue("$path: ${unresolved.map { it.canonicalText }}", unresolved.size <= 2)
        }
    }

    private val feature = """
        Feature: Offers
          Background:
            Given the server is running

          # Given a comment
          @smoke
          Scenario: Ongoing offer
            Given API missions V2 returns "filled" fixture for user
            When the offer tool has been called
            Then I have 3 cucumbers and 2.5 apples

          Scenario Outline: Eating
            Given there are <start> cucumbers
            When I eat <eat> cucumbers
            Then I should have <left> cucumber

            Examples:
              | start | eat | left |
              | 12    | 5   | 7    |

          Scenario Outline: Other
            Given there are <start> cucumbers
            Examples:
              | start |
              | 1     |
    """.trimIndent() + "\n"

    fun `test gherkin step to a rust definition`() = check(
        "tests/steps/offer.rs" to "use cucumber::{given, when};\n#[given(expr = \"<def>the server is running\")]\nasync fn running(w: &mut World) {}\n#[when(expr = \"the server is stopped\")]\nasync fn stopped(w: &mut World) {}\n",
        "tests/features/offer.feature" to feature.replaceFirst("the server is", "the <ref>server is"),
    )
    fun `test gherkin step with a string parameter`() = check(
        "tests/steps/offer.rs" to "use cucumber::given;\n#[given(expr = \"<def>API missions V{int} returns {string} fixture for user\")]\nasync fn f(w: &mut World, v: u8, name: String) {}\n",
        "tests/features/offer.feature" to feature.replaceFirst("returns \"filled\"", "<ref>returns \"filled\""),
    )
    fun `test gherkin step to a rust regex`() = check(
        "tests/steps/offer.rs" to "use cucumber::then;\n#[then(regex = r\"<def>^I have (\\d+) cucumbers and ([\\d.]+) apples$\")]\nasync fn f(w: &mut World) {}\n",
        "tests/features/offer.feature" to feature.replaceFirst("3 cucumbers and", "3 <ref>cucumbers and"),
    )
    fun `test gherkin step to a java definition`() = check(
        "src/test/java/Steps.java" to "import io.cucumber.java.en.When;\nclass Steps {\n  @When(\"<def>the offer tool has been called\")\n  public void called() {}\n}\n",
        "src/test/resources/offer.feature" to feature.replaceFirst("offer tool has", "offer <ref>tool has"),
    )
    fun `test gherkin step to a kotlin regex with escapes`() = check(
        "src/test/kotlin/Steps.kt" to "import io.cucumber.java8.En\nclass Steps : En {\n  init {\n    Then(\"<def>^I have (\\\\d+) cucumbers and (.+) apples$\") { a: Int, b: String -> }\n  }\n}\n",
        "src/test/resources/offer.feature" to feature.replaceFirst("3 cucumbers and", "3 <ref>cucumbers and"),
    )
    fun `test gherkin step to a javascript definition`() = check(
        "features/steps.js" to "const { Given, When } = require('@cucumber/cucumber');\nWhen('<def>I eat {int} cucumber(s)', function (n) {});\nGiven(/^there were (\\d+) cucumbers$/, function (n) {});\n",
        "features/eat.feature" to feature.replaceFirst("I eat <eat>", "I <ref>eat <eat>"),
    )
    fun `test gherkin step to a python definition`() = check(
        "features/steps/eat.py" to "from behave import given\n\n@given(u'<def>there are {start:d} cucumbers')\ndef step_impl(context, start):\n    pass\n",
        "features/eat.feature" to feature.replaceFirst("there are <start>", "<ref>there are <start>"),
    )
    fun `test gherkin placeholder`() = check("eat.feature" to feature.replaceFirst("I eat <eat>", "I eat <<ref>eat>").replaceFirst("| eat |", "| <def>eat |"))
    fun `test gherkin placeholder of the second outline`() = check(
        "eat.feature" to feature.replace("Scenario Outline: Other\n    Given there are <start>", "Scenario Outline: Other\n    Given there are <<ref>start>").replace("| start |\n", "| <def>start |\n"),
    )
    fun `test gherkin comment is not a step`() {
        val file = myFixture.configureByText("offer.feature", feature)
        val words = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { it.references.isNotEmpty() }.map { it.textRange.startOffset }
        assertFalse(feature.indexOf("a comment") in words)
        assertTrue(feature.indexOf("server is running") in words)
    }
    fun `test gherkin coloring`() = tokens("offer.feature", feature).let {
        assertToken(it, Cat.KEYWORD, "Feature"); assertToken(it, Cat.KEYWORD, "Scenario Outline"); assertToken(it, Cat.KEYWORD, "Given"); assertToken(it, Cat.META, "@smoke")
        assertToken(it, Cat.COMMENT, "# Given a comment"); assertToken(it, Cat.STRING, "\"filled\""); assertToken(it, Cat.VARIABLE, "<eat>"); assertToken(it, Cat.KEY, "left")
        assertToken(it, Cat.KEY, "12"); assertToken(it, Cat.KEYWORD, "Examples")
    }
    fun `test gherkin french keywords`() = tokens("offre.feature", "# language: fr\nFonctionnalité: Offres\n  Scénario: En cours\n    Etant donné que le serveur tourne\n    Quand j'appelle l'outil\n    Alors la réponse est \"ok\"\n").let {
        assertToken(it, Cat.KEYWORD, "Fonctionnalité"); assertToken(it, Cat.KEYWORD, "Scénario"); assertToken(it, Cat.KEYWORD, "Etant donné que"); assertToken(it, Cat.KEYWORD, "Alors")
    }

    private val workflow = """
        <# Deploys one environment. #>
        <% set runner = r.get('runner', 'l') %>
        <% macro deploy_with(envname, wait=false) %>
        <% set lines = ['environment: ' ~ envname] %>
        <% for line in lines|sort %>
              << line >>
        <% endfor %>
        <% endmacro %>
        name: Release
        on: push
        jobs:
          deploy:
            runs-on: << runner >>
            if: ${'$'}{{ github.ref == 'refs/heads/master' }}
            with:
        << deploy_with('prod') >>
        <% for img in imgs %>
              << img.input >>: ${'$'}{{ needs.build.outputs.image }}
        <% endfor %>
        <% set runner = 'xl' %>
        <% include "parts/_footer.yml.j2" %>
    """.trimIndent() + "\n"

    fun `test jinja variable`() = check("release.yml.j2" to mark(workflow, "set |runner = r.get", "runs-on: << ^runner >>"))
    fun `test jinja later set refers to the first`() = check("release.yml.j2" to mark(workflow, "set |runner = r.get", "set ^runner = 'xl'"))
    fun `test jinja macro call`() = check("release.yml.j2" to mark(workflow, "macro |deploy_with(", "<< ^deploy_with('prod') >>"))
    fun `test jinja macro parameter`() = check("release.yml.j2" to mark(workflow, "deploy_with(|envname, wait", "~ ^envname]"))
    fun `test jinja variable set in a macro`() = check("release.yml.j2" to mark(workflow, "set |lines = [", "for line in ^lines|sort"))
    fun `test jinja loop variable`() = check("release.yml.j2" to mark(workflow, "for |line in lines", "<< ^line >>"))
    fun `test jinja loop variable attribute`() = check("release.yml.j2" to mark(workflow, "for |img in imgs", "<< ^img.input >>"))
    fun `test jinja include`() = check("templates/parts/_footer.yml.j2" to "# end", "templates/release.yml.j2" to workflow.replaceFirst("include \"parts/", "include \"<ref>parts/"))
    fun `test jinja include from the loader root`() = check(
        "templates/parts/_footer.yml.j2" to "# end",
        "templates/workflows/release.yml.j2" to "<% if x %><% include \"<ref>parts/_footer.yml.j2\" %><% endif %>",
    )
    fun `test jinja imported macro`() = check(
        "macros.j2" to "{% macro <def>field(name) %}<input name=\"{{ name }}\">{% endmacro %}",
        "form.html.j2" to "{% from \"macros.j2\" import field %}\n<form>{{ <ref>field('email') }}</form>",
    )
    fun `test jinja default delimiters`() = check("page.html.j2" to "{% set <def>title = 'Home' %}\n<h1>{{ <ref>title|upper }}</h1>\n{# {{ title }} #}")
    fun `test jinja github expressions and unknown names are not jinja names`() {
        val file = myFixture.configureByText("release.yml.j2", workflow)
        val resolved = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { word -> word.references.any { it.resolve() != null } }.map { it.text }
        assertFalse(resolved.toString(), resolved.any { it in setOf("github", "needs", "r", "imgs", "sort", "wait") })
        val macro = workflow.indexOf("<% macro")
        val lines = workflow.indexOf("<% for line")
        val images = workflow.indexOf("<% for img")
        val expected = listOf("macro:deploy_with", "var:envname@$macro", "var:img@$images", "var:line@$lines", "var:lines@$macro", "var:runner", "var:wait@$macro")
        assertEquals(expected, Fmt.links(file).decls.map { it.key }.sorted())
    }
    fun `test jinja template with custom variables only`() {
        val text = "name: << name >>\nrun: echo ${'$'}{{ github.sha }} && cat <<EOF\n"
        val file = myFixture.configureByText("setup.yml.j2", text)
        assertEquals(listOf("name"), PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { it.references.isNotEmpty() }.map { it.text })
    }
    fun `test jinja default delimiters leave github expressions alone`() {
        val file = myFixture.configureByText("ci.yml.j2", "{% set <caret>ref = 'main' %}\nif: ${'$'}{{ github.ref }} == {{ ref }}\n")
        assertEquals(listOf("ref", "'main'", "ref"), PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).map { it.text })
    }
    fun `test jinja coloring`() = tokens("release.yml.j2", workflow).let {
        assertToken(it, Cat.COMMENT, "<# Deploys one environment. #>"); assertToken(it, Cat.LBRACE, "<%"); assertToken(it, Cat.RBRACE, "%>"); assertToken(it, Cat.KEYWORD, "macro")
        assertToken(it, Cat.VARIABLE, "deploy_with"); assertToken(it, Cat.STRING, "'prod'"); assertToken(it, Cat.FUNCTION, "sort"); assertToken(it, Cat.LBRACE, "<<")
        assertToken(it, Cat.CONSTANT, "false"); assertToken(it, Cat.KEYWORD, "endfor")
        assertFalse(it.any { (cat, text) -> cat == Cat.VARIABLE && text == "github" })
    }
    fun `test jinja text is colored as the generated format`() {
        val lexer = JinjaLexer(Jinja, Jinja.hostOf("release.yml.j2"))
        lexer.start(workflow)
        val seen = generateSequence { lexer.tokenType?.let { type -> ((type as? FmtToken)?.cat to workflow.substring(lexer.tokenStart, lexer.tokenEnd)).also { lexer.advance() } } }.toList()
        assertEquals("YAML", Jinja.hostOf("release.yml.j2")?.displayName)
        assertNull(Jinja.hostOf("macros.j2"))
        assertToken(seen, Cat.KEY, "jobs"); assertToken(seen, Cat.KEY, "runs-on"); assertToken(seen, Cat.VARIABLE, "runner"); assertToken(seen, Cat.KEYWORD, "set")
        assertEquals(workflow, seen.joinToString("") { it.second })
    }
    fun `test jinja lexer restarts inside a tag`() {
        val lexer = Jinja.lexer()
        lexer.start(workflow, workflow.indexOf("runner = r.get"), workflow.length, 0)
        assertEquals(Cat.VARIABLE, (lexer.tokenType as FmtToken).cat)
        lexer.start(workflow, workflow.indexOf("name: Release"), workflow.length, 0)
        assertEquals(Cat.PUNCT, (lexer.tokenType as FmtToken).cat)
    }

    private val request = "meta {\n  name: missions\n  seq: 2\n}\n\nget {\n  url: {{url}}/api/users/{{user}}/missions?page={{page}}\n  auth: none\n}\n\nheaders {\n  X-Token: {{token}}\n  ~X-Debug: 1\n}\n\nvars:pre-request {\n  page: 1\n}\n\nscript:post-response {\n  bru.setVar(\"token\", res.body.token);\n}\n"

    fun `test bruno variable of the request`() = check("api/missions.bru" to mark(request, "  |page: 1", "page={{^page}}"))
    fun `test bruno variable set by a script`() = check("api/missions.bru" to mark(request, "setVar(\"|token\"", "X-Token: {{^token}}"))
    fun `test bruno variable of an environment`() = check(
        "api/environments/dev.bru" to "vars {\n  <def>url: https://dev.example.com\n  user: 12\n}\n",
        "api/bruno.json" to "{}",
        "api/offers/missions.bru" to request.replaceFirst("{{url}}", "{{<ref>url}}"),
    )
    fun `test bruno variable of the folder`() = check(
        "api/offers/folder.bru" to "vars:pre-request {\n  <def>user: 42\n}\n",
        "api/bruno.json" to "{}",
        "api/offers/missions.bru" to request.replaceFirst("{{user}}", "{{<ref>user}}"),
    )
    fun `test bruno secret variable`() = check(
        "api/environments/prod.bru" to "vars {\n  url: https://example.com\n}\nvars:secret [\n  <def>user\n]\n",
        "api/collection.bru" to "meta {\n  name: api\n}\n",
        "api/missions.bru" to request.replaceFirst("{{user}}", "{{<ref>user}}"),
    )
    fun `test bruno variable of another collection is not seen`() {
        myFixture.addFileToProject("other/environments/dev.bru", "vars {\n  url: https://other.example.com\n}\n")
        myFixture.addFileToProject("other/bruno.json", "{}")
        myFixture.addFileToProject("api/bruno.json", "{}")
        val file = myFixture.addFileToProject("api/missions.bru", request)
        val word = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).first { it.text == "{{url}}" }
        assertEmpty(word.references.mapNotNull { it.resolve() })
    }
    fun `test bruno coloring`() = tokens("missions.bru", request).let {
        assertToken(it, Cat.KEYWORD, "meta"); assertToken(it, Cat.KEYWORD, "vars:pre-request"); assertToken(it, Cat.KEY, "url"); assertToken(it, Cat.VARIABLE, "{{user}}")
        assertToken(it, Cat.KEY, "~X-Debug"); assertToken(it, Cat.STRING, "\"token\""); assertToken(it, Cat.NUMBER, "2")
    }

    private val owners = "# Owners\n*           @acme/platform-team\n/deploy/    @acme/sre ops@example.com\nsrc/main.rs @alice # entry point\n*.sql       @betclicgroup/dba\n/docs/guide.md @bob\n"

    fun `test codeowners folder`() = check("deploy/live/main.tf" to "", "CODEOWNERS" to owners.replaceFirst("/deploy/", "/<ref>deploy/"))
    fun `test codeowners file`() = check("src/main.rs" to "fn main() {}", "CODEOWNERS" to owners.replaceFirst("src/main.rs", "<ref>src/main.rs"))
    fun `test codeowners in the github folder`() = check("docs/guide.md" to "# Guide", ".github/CODEOWNERS" to owners.replaceFirst("/docs/guide.md", "/<ref>docs/guide.md"))
    fun `test codeowners globs are not paths`() {
        val file = myFixture.configureByText("CODEOWNERS", owners)
        assertEquals(listOf("deploy", "src/main.rs", "docs/guide.md"), Fmt.links(file).paths.map { it.path })
    }
    fun `test codeowners coloring`() = tokens("CODEOWNERS", owners).let {
        assertToken(it, Cat.COMMENT, "# Owners"); assertToken(it, Cat.KEY, "/deploy/"); assertToken(it, Cat.KEY, "*.sql"); assertToken(it, Cat.META, "@acme/sre")
        assertToken(it, Cat.STRING, "ops@example.com"); assertToken(it, Cat.META, "@alice"); assertToken(it, Cat.COMMENT, "# entry point")
    }

    private val helpers = "package common\n\nimport future.keywords.if\n\nin_department(name) if {\n\tinput.user.department == name\n}\n\nhas_role(role) if {\n\tsome r in input.user.roles\n\tr == role\n}\n"
    private val policy = """
        package care.qualifiers

        import data.common
        import data.care.access as acl
        import future.keywords.if

        default is_admin := false

        # is_admin: a comment
        is_admin if {
        	common.in_department("CARE")
        	level := acl.levels[input.user.id]
        	level > 2
        }

        is_admin if {
        	data.common.has_role("ADMIN")
        }

        allowed contains user if {
        	some user, level in data.care.access.levels
        	is_admin
        	level >= minimum("is_admin")
        }

        minimum(kind) := 3 if {
        	kind == "strict"
        }
    """.trimIndent().replace("    ", "\t") + "\n"
    private val access = "package care.access\n\nlevels := {\"alice\": 3}\n"

    private fun rego(def: String, ref: String) = arrayOf("policies/common/helpers.rego" to helpers, "policies/care/access.rego" to access, "policies/care/qualifiers.rego" to mark(policy, def, ref))

    fun `test rego local variable`() = check(*rego("\t|level := acl", "\t^level > 2"))
    fun `test rego variable of another rule is another variable`() = check(*rego("some user, |level in", "\t^level >= minimum"))
    fun `test rego some variable`() = check("a.rego" to "package a\n\nallowed contains name if {\n\tsome <def>user in input.users\n\tname := <ref>user.name\n}\n")
    fun `test rego head variable`() = check("a.rego" to "package a\n\nallowed contains <def>user if {\n\t<ref>user == input.user\n}\n")
    fun `test rego function argument`() = check(*rego("minimum(|kind)", "\t^kind == \"strict\""))
    fun `test rego rule of the file`() = check(*rego("default |is_admin := false", "user, level in data.care.access.levels\n\t^is_admin"))
    fun `test rego later definition refers to the first`() = check(*rego("default |is_admin := false", "}\n\n^is_admin if {\n\tdata.common"))
    fun `test rego function of the file`() = check(*rego("|minimum(kind)", "level >= ^minimum("))
    fun `test rego imported package`() = check(*rego("import data.|common", "\t^common.in_department"))
    fun `test rego import alias`() = check(*rego("as |acl", "level := ^acl.levels"))
    fun `test rego rule through an import`() = check(
        "policies/common/helpers.rego" to helpers.replaceFirst("in_department(name)", "<def>in_department(name)"),
        "policies/care/qualifiers.rego" to policy.replaceFirst("common.in_department", "common.<ref>in_department"),
    )
    fun `test rego rule through an alias`() = check(
        "policies/care/access.rego" to access.replaceFirst("levels :=", "<def>levels :="),
        "policies/care/qualifiers.rego" to policy.replaceFirst("acl.levels", "acl.<ref>levels"),
    )
    fun `test rego rule through data`() = check(
        "policies/common/helpers.rego" to helpers.replaceFirst("has_role(role)", "<def>has_role(role)"),
        "policies/care/qualifiers.rego" to policy.replaceFirst("data.common.has_role", "data.common.<ref>has_role"),
    )
    fun `test rego package segment`() = check(
        "policies/care/access.rego" to access.replaceFirst("care.access", "care.<def>access"),
        "policies/care/qualifiers.rego" to policy.replaceFirst("in data.care.access.levels", "in data.care.<ref>access.levels"),
    )
    fun `test rego rule of another file of the package`() = check(
        "policies/care/base.rego" to "package care.qualifiers\n\n<def>is_agent if input.user.agent\n",
        "policies/other/base.rego" to "package other\n\nis_agent := true\n",
        "policies/care/qualifiers.rego" to "package care.qualifiers\n\nallow if <ref>is_agent\n",
    )
    fun `test rego variable bound by unification`() = check("a.rego" to "package a\n\nallow {\n\t<def>level = input.level\n\t<ref>level > 2\n}\n")
    fun `test rego unification with a rule compares`() = check("a.rego" to "package a\n\n<def>limit := 3\n\nallow {\n\t<ref>limit = input.level\n}\n")
    fun `test rego wildcard and boolean keywords are not names`() {
        val file = myFixture.configureByText("a.rego", "package a\n\nallow if {\n\tsome _, user in input.users\n\tuser.admin or user.owner and not user.banned\n}\n")
        assertEquals(listOf("local:user", "pkg:a", "rule:a.allow"), Fmt.links(file).decls.map { it.key.substringBefore('@') }.sorted())
        assertToken(tokens("b.rego", file.text), Cat.KEYWORD, "or"); assertToken(tokens("c.rego", file.text), Cat.KEYWORD, "and")
    }
    fun `test rego comments strings and builtins are not names`() {
        val file = myFixture.configureByText("qualifiers.rego", policy)
        val resolved = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { word -> word.references.any { it.resolve() != null } }.map { it.textRange.startOffset }
        assertFalse(policy.indexOf("# is_admin") + 2 in resolved)
        assertFalse(policy.indexOf("\"is_admin\"") + 1 in resolved)
        assertFalse(policy.indexOf("input.user.id") in resolved)
        val keys = Fmt.links(file).decls.map { it.key.substringBefore('@') }.sorted()
        assertEquals(listOf("import:acl", "import:common", "local:kind", "local:level", "local:level", "local:user", "pkg:care.qualifiers", "rule:care.qualifiers.allowed", "rule:care.qualifiers.is_admin", "rule:care.qualifiers.minimum"), keys)
    }
    fun `test rego coloring`() = tokens("qualifiers.rego", policy).let {
        assertToken(it, Cat.KEYWORD, "package"); assertToken(it, Cat.KEYWORD, "default"); assertToken(it, Cat.KEYWORD, "contains"); assertToken(it, Cat.KEYWORD, "some")
        assertToken(it, Cat.DECL, "is_admin"); assertToken(it, Cat.DECL, "minimum"); assertToken(it, Cat.FUNCTION, "in_department"); assertToken(it, Cat.STRING, "\"CARE\"")
        assertToken(it, Cat.META, "input"); assertToken(it, Cat.META, "data"); assertToken(it, Cat.COMMENT, "# is_admin: a comment"); assertToken(it, Cat.CONSTANT, "false")
        assertToken(it, Cat.NUMBER, "2"); assertToken(it, Cat.WORD, "level")
    }

    fun `test a very long string does not overflow the regex stack`() {
        val long = "x".repeat(40_000)
        assertToken(tokens("a.yml", "key: \"$long\"\n"), Cat.STRING, "\"$long\"")
        assertToken(tokens("main.tf", "locals {\n  a = \"$long\"\n}\n"), Cat.STRING, "\"$long\"")
        assertToken(tokens("a.fish", "set a '$long'\n"), Cat.STRING, "'$long'")
        assertToken(tokens("a.json5", "{ k: '$long' }\n"), Cat.STRING, "'$long'")
        assertToken(tokens("b.yml", "key: '$long'\n"), Cat.STRING, "'$long'")
        assertToken(tokens("api.yaml", "x:\n  \$ref: $long\n"), Cat.STRING, long)
        assertToken(tokens("q.sql", "SELECT '$long';\n"), Cat.STRING, "'$long'")
        assertToken(tokens(".gitignore", "$long\n"), Cat.KEY, long)
    }

    fun `test a string of very many escapes does not overflow the regex stack`() {
        val escapes = "\\n".repeat(20_000)
        assertToken(tokens("a.json5", "{ k: \"$escapes\" }\n"), Cat.STRING, "\"$escapes\"")
        assertToken(tokens("main.tf", "locals {\n  a = \"$escapes\"\n}\n"), Cat.STRING, "\"$escapes\"")
        assertToken(tokens(".gitignore", "$escapes\n"), Cat.KEY, escapes)
    }

    fun `test a very long name does not overflow the regex stack of a linker`() {
        val long = "x".repeat(40_000)
        val keys = Fmt.links(myFixture.configureByText("a.yml", "\"$long\": 1\n'q$long': 2\nx:\n  \$ref: $long\n"))
        assertEquals(listOf("key:/$long", "key:/q$long", "key:/x", "key:/x/\$ref"), keys.decls.map { it.key })
        assertEquals(listOf("ref:$long"), keys.refs.flatMap { it.keys })
        val recipes = Fmt.links(myFixture.configureByText("justfile", "v := \"$long\"\nbuild target=\"$long\": (lint \"$long\")\n    echo\n"))
        assertEquals(listOf("var:v", "recipe:build", "param:build:target"), recipes.decls.map { it.key })
        assertEquals(1, Fmt.links(myFixture.configureByText(".gitignore", "$long\n")).paths.size)
    }

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

    fun `test rename terraform variable in what calls the module`() {
        val caller = myFixture.addFileToProject("root/main.tf", "variable \"size\" {}\nmodule \"api\" {\n  source = \"../stacks/api\"\n  size   = var.size\n}\n")
        val unit = myFixture.addFileToProject("live/api/terragrunt.hcl", "terraform {\n  source = \"../../stacks//api\"\n}\ninputs = {\n  size = 2\n  other = { size = 3 }\n}\n")
        val varFile = myFixture.addFileToProject("stacks/api/dev.tfvars", "size = 1\n")
        val module = myFixture.addFileToProject("stacks/api/main.tf", "variable \"size\" {}\nlocals {\n  twice = var.size * 2 # var.size\n}\n")
        myFixture.configureFromExistingVirtualFile(module.virtualFile)
        myFixture.editor.caretModel.moveToOffset(module.text.indexOf("size"))
        myFixture.renameElementAtCaret("capacity")
        myFixture.checkResult("variable \"capacity\" {}\nlocals {\n  twice = var.capacity * 2 # var.size\n}\n")
        assertEquals("capacity = 1\n", varFile.text)
        assertEquals("variable \"size\" {}\nmodule \"api\" {\n  source = \"../stacks/api\"\n  capacity   = var.size\n}\n", caller.text)
        assertEquals("terraform {\n  source = \"../../stacks//api\"\n}\ninputs = {\n  capacity = 2\n  other = { size = 3 }\n}\n", unit.text)
    }

    fun `test rename terraform output in what reads it`() {
        val caller = myFixture.addFileToProject("root/main.tf", "module \"api\" {\n  source = \"../stacks/api\"\n}\nlocals {\n  x = module.api.arn\n}\n")
        myFixture.addFileToProject("live/api/terragrunt.hcl", "terraform {\n  source = \"../../stacks/api\"\n}\n")
        val reader = myFixture.addFileToProject("live/web/terragrunt.hcl", "dependency \"api\" {\n  config_path = \"../api\"\n}\ninputs = {\n  arn = dependency.api.outputs.arn\n}\n")
        val module = myFixture.addFileToProject("stacks/api/main.tf", "output \"arn\" { value = \"a\" }\n")
        myFixture.configureFromExistingVirtualFile(module.virtualFile)
        myFixture.editor.caretModel.moveToOffset(module.text.indexOf("arn"))
        myFixture.renameElementAtCaret("bucket_arn")
        assertEquals("module \"api\" {\n  source = \"../stacks/api\"\n}\nlocals {\n  x = module.api.bucket_arn\n}\n", caller.text)
        assertEquals("dependency \"api\" {\n  config_path = \"../api\"\n}\ninputs = {\n  arn = dependency.api.outputs.bucket_arn\n}\n", reader.text)
    }

    fun `test rename hocon path level`() {
        myFixture.configureByText("application.conf", "db {\n  po<caret>ol { size = 10 }\n}\nworkers = \${db.pool.size}\nother.pool = 1\n")
        myFixture.renameElementAtCaret("connections")
        myFixture.checkResult("db {\n  connections { size = 10 }\n}\nworkers = \${db.connections.size}\nother.pool = 1\n")
    }

    fun `test rename fish local variable`() {
        myFixture.configureByText("main.fish", "set who global\nfunction greet\n  set -l who world\n  echo \$w<caret>ho\nend\necho \$who\n")
        myFixture.renameElementAtCaret("name")
        myFixture.checkResult("set who global\nfunction greet\n  set -l name world\n  echo \$name\nend\necho \$who\n")
    }

    fun `test rename fish function across files`() {
        val caller = myFixture.addFileToProject("main.fish", "greet world\n")
        myFixture.configureByText("greet.fish", "function gr<caret>eet\n  echo hi\nend\n")
        myFixture.renameElementAtCaret("hello")
        myFixture.checkResult("function hello\n  echo hi\nend\n")
        assertEquals("hello world\n", caller.text)
    }

    fun `test rename handlebars block parameter`() {
        myFixture.configureByText("page.hbs", "{{#each users as |user|}}{{us<caret>er.name}}{{/each}}{{user}}")
        myFixture.renameElementAtCaret("member")
        myFixture.checkResult("{{#each users as |member|}}{{member.name}}{{/each}}{{user}}")
    }

    fun `test rename handlebars inline partial`() {
        myFixture.configureByText("page.hbs", "{{#*inline \"ro<caret>w\"}}x{{/inline}}{{> row}}{{> row a=1}}")
        myFixture.renameElementAtCaret("line")
        myFixture.checkResult("{{#*inline \"line\"}}x{{/inline}}{{> line}}{{> line a=1}}")
    }

    fun `test rename handlebars partial file`() {
        val partial = myFixture.addFileToProject("partials/footer.hbs", "<footer/>")
        val page = myFixture.addFileToProject("page.hbs", "{{> footer}} {{> partials/footer year=1}}")
        myFixture.renameElement(partial, "bottom.hbs")
        assertEquals("{{> bottom}} {{> partials/bottom year=1}}", page.text)
    }

    fun `test rename jflex macro`() {
        myFixture.configureByText("L.flex", "%%\nDIGIT = [0-9]\nINT = {DIG<caret>IT}+\n%%\n{DIGIT} { return DIGIT; }\n")
        myFixture.renameElementAtCaret("NUM")
        myFixture.checkResult("%%\nNUM = [0-9]\nINT = {NUM}+\n%%\n{NUM} { return DIGIT; }\n")
    }

    fun `test rename jflex state`() {
        myFixture.configureByText("L.flex", "%%\n%state IN_STRING\n%%\n<IN_ST<caret>RING> \\\" { yybegin(YYINITIAL); }\n. { yybegin(IN_STRING); return T.IN_STRING; }\n")
        myFixture.renameElementAtCaret("TEXT")
        myFixture.checkResult("%%\n%state TEXT\n%%\n<TEXT> \\\" { yybegin(YYINITIAL); }\n. { yybegin(TEXT); return T.IN_STRING; }\n")
    }

    fun `test rename gherkin column`() {
        myFixture.configureByText("eat.feature", "Feature: F\n  Scenario Outline: A\n    Given <n> and <n<caret>>\n    Examples:\n      | n |\n      | 1 |\n  Scenario Outline: B\n    Given <n>\n    Examples:\n      | n |\n      | 2 |\n")
        myFixture.renameElementAtCaret("count")
        myFixture.checkResult("Feature: F\n  Scenario Outline: A\n    Given <count> and <count>\n    Examples:\n      | count |\n      | 1 |\n  Scenario Outline: B\n    Given <n>\n    Examples:\n      | n |\n      | 2 |\n")
    }

    fun `test rename jinja macro parameter`() {
        myFixture.configureByText("a.yml.j2", "<% macro job(name) %>\n<< na<caret>me >>: << name|upper >>\n<% endmacro %>\n<< name >>\n")
        myFixture.renameElementAtCaret("label")
        myFixture.checkResult("<% macro job(label) %>\n<< label >>: << label|upper >>\n<% endmacro %>\n<< name >>\n")
    }

    fun `test rename jinja macro`() {
        myFixture.configureByText("a.html.j2", "{% macro fi<caret>eld(n) %}{{ n }}{% endmacro %}\n{{ field('a') }} {{ field('b') }}\n")
        myFixture.renameElementAtCaret("input")
        myFixture.checkResult("{% macro input(n) %}{{ n }}{% endmacro %}\n{{ input('a') }} {{ input('b') }}\n")
    }

    fun `test rename bruno variable across the collection`() {
        myFixture.addFileToProject("api/bruno.json", "{}")
        val request = myFixture.addFileToProject("api/get.bru", "get {\n  url: {{host}}/x\n}\n")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("api/environments/dev.bru", "vars {\n  host: https://dev\n}\n").virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("host"))
        myFixture.renameElementAtCaret("base")
        myFixture.checkResult("vars {\n  base: https://dev\n}\n")
        assertEquals("get {\n  url: {{base}}/x\n}\n", request.text)
    }

    fun `test rename rego variable`() {
        myFixture.configureByText("a.rego", "package a\n\nallow if {\n\tlevel := input.level\n\tle<caret>vel > 2\n}\n\ndeny if {\n\tlevel := 0\n}\n")
        myFixture.renameElementAtCaret("rank")
        myFixture.checkResult("package a\n\nallow if {\n\trank := input.level\n\trank > 2\n}\n\ndeny if {\n\tlevel := 0\n}\n")
    }

    fun `test rename rego rule across files`() {
        val user = myFixture.addFileToProject("policies/b.rego", "package b\n\nimport data.a\n\nallow if a.is_admin\nallow if data.a.is_admin\n")
        myFixture.configureByText("a.rego", "package a\n\ndefault is_ad<caret>min := false\n\nis_admin if input.admin\n\nroot if is_admin\n")
        myFixture.renameElementAtCaret("is_root")
        myFixture.checkResult("package a\n\ndefault is_root := false\n\nis_root if input.admin\n\nroot if is_root\n")
        assertEquals("package b\n\nimport data.a\n\nallow if a.is_root\nallow if data.a.is_root\n", user.text)
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
            // a regex that recurses per character overflows the stack on a long token: name the file
            val links = try {
                lexer.start(psi.text)
                var last = 0
                while (lexer.tokenType != null) { assertEquals(f.path, last, lexer.tokenStart); last = lexer.tokenEnd; lexer.advance() }
                assertEquals(f.path, psi.textLength, last)
                Fmt.links(psi)
            } catch (e: StackOverflowError) {
                throw AssertionError("${f.path} (${psi.format.displayName}, ${psi.textLength} chars): stack overflow in a regex")
            }
            for (r in links.refs.map { it.range } + links.paths.map { it.range }) {
                val word = PsiTreeUtil.getParentOfType(psi.findElementAt(r.startOffset), FmtWord::class.java) ?: continue
                val refs = word.references.filter { it.rangeInElement.shiftRight(word.textRange.startOffset) == r }
                total++
                when {
                    refs.any { (it as com.intellij.psi.PsiPolyVariantReference).multiResolve(false).isNotEmpty() } -> resolved++
                    else -> misses += "${f.path}: ${r.substring(psi.text)}"
                }
            }
            counts.merge(psi.format.displayName, 1, Int::plus)
        }
        val missesByType = misses.groupingBy { it.substringBefore(": ").substringAfterLast('.') }.eachCount().toSortedMap()
        val shown = misses.take(when (System.getenv("LITE_SHOW")) { "all" -> misses.size; else -> 40 })
        println("fmt corpus in ${(System.nanoTime() - started) / 1_000_000} ms: $counts; links $resolved/$total resolved; misses by extension $missesByType\n" + shown.joinToString("\n"))
    }
}
