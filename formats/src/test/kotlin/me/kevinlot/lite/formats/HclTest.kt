package me.kevinlot.lite.formats

import me.kevinlot.lite.formats.infra.Hcl

class HclTest : FmtTestCase() {

    private fun iteratorUses(text: String): List<String> {
        myFixture.configureByText("main.tf", text)
        return Fmt.links(myFixture.file).refs.filter { it.keys.first().startsWith("iter:") }.map { it.range.substring(text) }
    }

    // ---------------------------------------------------------------- object attributes

    fun `test local object attribute`() = check("main.tf" to "locals {\n  cfg = { <def>name = \"x\", size = 1 }\n}\noutput \"o\" { value = local.cfg.<ref>name }")
    fun `test local object attribute on a later place of the line`() = check("main.tf" to "locals {\n  cfg = { name = \"x\", <def>size = 1 }\n}\noutput \"o\" { value = local.cfg.<ref>size }")
    fun `test local nested object attribute`() = check("main.tf" to "locals {\n  cfg = {\n    db = {\n      <def>port = 5432\n    }\n    port = 1\n  }\n}\noutput \"o\" { value = local.cfg.db.<ref>port }")
    fun `test local object level`() = check("main.tf" to "locals {\n  cfg = {\n    <def>db = {\n      port = 5432\n    }\n  }\n}\noutput \"o\" { value = local.cfg.<ref>db.port }")
    fun `test local holding an object is still followed`() = check("main.tf" to "locals {\n  <def>cfg = { name = \"x\" }\n}\noutput \"o\" { value = local.<ref>cfg.name }")
    fun `test variable default attribute across files`() = check(
        "vars.tf" to "variable \"settings\" {\n  default = {\n    <def>region = \"eu-west-1\"\n  }\n}",
        "main.tf" to "output \"o\" { value = var.settings.<ref>region }",
    )
    fun `test variable default attribute on one line`() = check("main.tf" to "variable \"settings\" { default = { <def>region = \"x\" } }\noutput \"o\" { value = var.settings.<ref>region }")
    fun `test variable object type attribute wins over the default`() = check(
        "main.tf" to "variable \"settings\" {\n  type = object({\n    <def>region = string\n  })\n  default = {\n    region = \"x\"\n  }\n}\noutput \"o\" { value = var.settings.<ref>region }",
    )
    fun `test object attribute inside a template`() = check("main.tf" to "locals {\n  cfg = { <def>name = \"x\" }\n  full = \"app-\${local.cfg.<ref>name}\"\n}")

    // ---------------------------------------------------------------- iterators

    fun `test for variable of a list`() = check("main.tf" to "locals {\n  names = [for <def>s in var.list : <ref>s.name]\n}")
    fun `test for key of a map`() = check("main.tf" to "locals {\n  m = { for <def>k, v in var.map : <ref>k => v }\n}")
    fun `test for value of a map`() = check("main.tf" to "locals {\n  m = { for k, <def>v in var.map : k => <ref>v }\n}")
    fun `test for variable inside a template`() = check("main.tf" to "locals {\n  names = [for <def>s in var.list : \"x-\${<ref>s.name}\"]\n}")
    fun `test for variable on several lines`() = check("main.tf" to "locals {\n  m = {\n    for <def>k, v in var.map :\n    <ref>k => v\n    if v != null\n  }\n}")
    fun `test for variable of the inner loop`() = check("main.tf" to "locals {\n  x = [for s in var.a : [for <def>s in var.b : <ref>s.id]]\n}")
    fun `test for variable of the second loop`() = check("main.tf" to "locals {\n  a = [for s in var.a : s]\n  b = [for <def>s in var.b : <ref>s]\n}")
    fun `test a key or an attribute named like the for variable is not a use`() =
        assertEquals(listOf("s"), iteratorUses("locals {\n  x = [for s in var.l : { s = s.name, t = var.s }]\n  s = 1\n}\n"))
    fun `test dynamic block label`() = check(
        "main.tf" to "resource \"aws_x\" \"y\" {\n  dynamic \"<def>setting\" {\n    for_each = var.settings\n    content {\n      value = <ref>setting.value\n    }\n  }\n}",
    )
    fun `test dynamic block iterator`() = check(
        "main.tf" to "resource \"aws_x\" \"y\" {\n  dynamic \"setting\" {\n    for_each = var.settings\n    iterator = <def>it\n    content {\n      value = <ref>it.value\n    }\n  }\n}",
    )
    fun `test dynamic block label is unused once an iterator is named`() =
        assertEquals(listOf("it"), iteratorUses("resource \"aws_x\" \"y\" {\n  dynamic \"setting\" {\n    for_each = var.settings\n    iterator = it\n    content {\n      value = it.value\n      other = setting.value\n    }\n  }\n}\n"))

    // ---------------------------------------------------------------- provider aliases

    fun `test provider alias across files`() = check(
        "providers.tf" to "provider \"aws\" {\n  alias  = \"<def>west\"\n  region = \"us-west-2\"\n}",
        "main.tf" to "resource \"aws_s3_bucket\" \"b\" {\n  provider = aws.<ref>west\n}",
    )
    fun `test provider alias in the providers of a module`() = check(
        "main.tf" to "provider \"aws\" {\n  alias = \"east\"\n}\nprovider \"aws\" {\n  alias = \"<def>west\"\n}\nmodule \"m\" {\n  source = \"./m\"\n  providers = {\n    aws = aws.<ref>west\n  }\n}",
    )
    fun `test alias of another provider is not the one`() {
        myFixture.configureByText("main.tf", "provider \"google\" {\n  alias = \"west\"\n}\nresource \"aws_s3_bucket\" \"b\" {\n  provider = aws.west\n}\n")
        val reference = Fmt.links(myFixture.file).refs.single { it.keys.first().startsWith("provider:") }
        assertEmpty(Fmt.declarations(listOf(myFixture.file), Hcl, reference.keys.first()))
    }

    // ---------------------------------------------------------------- colors

    fun `test whatever opens a block is a keyword`() {
        val text = "resource \"aws_instance\" \"x\" {\n  lifecycle {\n    precondition {\n      condition = true\n    }\n  }\n  provisioner \"local-exec\" {\n    command = \"x\"\n  }\n" +
            "  dynamic \"s\" {\n    content {}\n  }\n  tags = {\n    a = 1\n  }\n}\nephemeral \"aws_secret\" \"s\" {}\nterraform {\n  required_providers {}\n}\n"
        val found = tokens("main.tf", text)
        listOf("lifecycle", "precondition", "provisioner", "content", "ephemeral", "required_providers").forEach { assertToken(found, Cat.KEYWORD, it) }
        listOf("condition", "command", "tags", "a").forEach { assertToken(found, Cat.KEY, it) }
    }
    fun `test ephemeral resource`() = check("main.tf" to "ephemeral \"aws_secret\" \"<def>db\" {}\nlocals {\n  password = ephemeral.aws_secret.<ref>db.value\n}")
    fun `test a quote inside an interpolation does not end the string`() {
        val merged = "\"\${merge({a=1}, {b=\"x\"})}\""
        val directive = "\"%{ if var.x == \"a\" }y%{ endif }\""
        val found = tokens("main.tf", "locals {\n  a = $merged\n  b = $directive\n  c = 1\n}\n")
        assertToken(found, Cat.STRING, merged)
        assertToken(found, Cat.STRING, directive)
        assertToken(found, Cat.KEY, "c")
    }
    fun `test an unclosed string ends with its line`() = tokens("main.tf", "locals {\n  a = \"abc\n  b = \"it's \\\"x\\\"\"\n}\n").let {
        assertToken(it, Cat.STRING, "\"abc"); assertToken(it, Cat.KEY, "b"); assertToken(it, Cat.STRING, "\"it's \\\"x\\\"\"")
    }
    fun `test reference inside a directive holding a quote`() = check("main.tf" to "variable \"<def>x\" {}\nlocals {\n  a = \"%{ if var.<ref>x == \"a\" }y%{ endif }\"\n}")
    fun `test the key of a for result is not an attribute`() {
        val found = tokens("main.tf", "locals {\n  m = { for k, v in var.m :\n    k => v\n  }\n}\n")
        assertFalse(found.toString(), (Cat.KEY to "k") in found)
        assertToken(found, Cat.KEY, "m")
    }
    fun `test the key of a for result is not an input`() {
        myFixture.configureByText("terragrunt.hcl", "inputs = {\n  for k, v in local.m :\n  k => v\n}\n")
        assertEmpty(Fmt.links(myFixture.file).refs.filter { it.keys.first().startsWith("input:") })
    }
    fun `test inputs written on one line`() = check(
        "stacks/api/variables.tf" to "variable \"<def>size\" {}",
        "live/api/terragrunt.hcl" to "terraform {\n  source = \"../../stacks/api\"\n}\ninputs = { name = \"x\", <ref>size = 2 }\n",
    )
}
