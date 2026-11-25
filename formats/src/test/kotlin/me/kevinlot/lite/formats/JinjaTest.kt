package me.kevinlot.lite.formats

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class JinjaTest : FmtTestCase() {

    private fun resolvedOffsets(file: PsiFile): List<Int> =
        PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { word -> word.references.any { it.resolve() != null } }.map { it.textRange.startOffset }

    // ---------------------------------------------------------------- blocks

    fun `test block of the extended template`() = check(
        "base.html.j2" to "<title>{% block <def>title %}Home{% endblock %}</title>",
        "page.html.j2" to "{% extends \"base.html.j2\" %}\n{% block <ref>title %}Page{% endblock title %}",
    )
    fun `test endblock names its block`() = check("base.html.j2" to "{% block <def>title %}Home{% endblock <ref>title %}")
    fun `test endblock of a child names the block of its parent`() = check(
        "base.html.j2" to "{% block <def>body scoped %}{% endblock %}",
        "page.html.j2" to "{%- extends 'base.html.j2' -%}\n{% block body %}Page{% endblock <ref>body %}",
    )
    fun `test block of the grandparent template`() = check(
        "base.html.j2" to "{% block <def>title %}Home{% endblock %}",
        "section.html.j2" to "{% extends \"base.html.j2\" %}{% block nav %}{% endblock %}",
        "page.html.j2" to "{% extends \"section.html.j2\" %}{% block <ref>title %}Page{% endblock %}",
    )
    fun `test block of the nearest ancestor wins`() = check(
        "base.html.j2" to "{% block title %}Home{% endblock %}{% block other %}{% endblock %}",
        "layouts/base.html.j2" to "{% block <def>title %}Layout{% endblock %}",
        "layouts/page.html.j2" to "{% extends \"base.html.j2\" %}{% block <ref>title %}Page{% endblock %}",
    )
    fun `test block name is not a variable`() {
        val text = "{% set title = 'x' %}{% block title %}{{ title }}{% endblock title %}"
        val file = myFixture.configureByText("base.html.j2", text)
        assertEquals(listOf("block:title", "var:title"), Fmt.links(file).decls.map { it.key }.sorted())
        check("page.html.j2" to "{% set <def>title = 'x' %}{% block title %}{{ <ref>title }}{% endblock %}")
    }

    // ---------------------------------------------------------------- raw

    fun `test raw block is text`() {
        val text = "{% set x = 1 %}{% raw %}{{ x }} {% if x %}{% endraw %}{{ x }}"
        val seen = tokens("a.html.j2", text)
        assertEquals(2, seen.count { it == Cat.VARIABLE to "x" })
        assertFalse(seen.toString(), seen.any { it == Cat.KEYWORD to "if" })
        assertToken(seen, Cat.KEYWORD, "raw"); assertToken(seen, Cat.KEYWORD, "endraw")
        assertEquals(listOf(text.lastIndexOf("x")), resolvedOffsets(myFixture.file))
    }
    fun `test raw block with custom delimiters is text`() {
        val text = "<% set x = 1 %>\n<%- raw -%>\n<< x >> <% if x %>\n<%- endraw -%>\n<< x >>\n"
        val seen = tokens("a.yml.j2", text)
        assertEquals(2, seen.count { it == Cat.VARIABLE to "x" })
        assertFalse(seen.toString(), seen.any { it == Cat.KEYWORD to "if" })
        assertEquals(listOf(text.lastIndexOf("x")), resolvedOffsets(myFixture.file))
    }

    // ---------------------------------------------------------------- with, call

    fun `test with variable`() = check("a.j2" to "{% with <def>a = 1, b = f(a, k=2) %}{{ <ref>a }}{{ b }}{% endwith %}")
    fun `test second with variable`() = check("a.j2" to "{% with a = 1, <def>b = f(a, k=2) %}{{ a }}{{ <ref>b }}{% endwith %}")
    fun `test with variable ends at endwith`() {
        val text = "{% with a = 1, b = f(x, k=[2, 3]) %}{{ a }}{% endwith %}{{ a }}{{ k }}"
        val file = myFixture.configureByText("a.j2", text)
        assertEquals(listOf("var:a@0", "var:b@0"), Fmt.links(file).decls.map { it.key }.sorted())
        assertEquals(listOf(text.indexOf("{{ a") + 3), resolvedOffsets(file))
    }
    fun `test call parameter`() = check(
        "a.j2" to "{% macro each(xs) %}{{ caller(xs) }}{% endmacro %}{% call(<def>item, last=none) each(items) %}{{ <ref>item }}{% endcall %}{{ item }}",
    )
    fun `test call parameter ends at endcall`() {
        val text = "{% call(item) each(items) %}{% endcall %}{{ item }}"
        val file = myFixture.configureByText("a.j2", text)
        assertEquals(listOf("var:item@0"), Fmt.links(file).decls.map { it.key })
        assertTrue(resolvedOffsets(file).isEmpty())
    }

    // ---------------------------------------------------------------- imported members

    fun `test member of an imported template`() = check(
        "m.j2" to "{% macro <def>render(x) %}{{ x }}{% endmacro %}",
        "page.j2" to "{% import \"m.j2\" as m %}{{ m.<ref>render(1) }}",
    )
    fun `test member is looked up in its own template`() = check(
        "forms.j2" to "{% macro render() %}{% endmacro %}",
        "tables.j2" to "{% macro <def>render() %}{% endmacro %}",
        "page.j2" to "{% import 'forms.j2' as f %}{% import 'tables.j2' as t %}{{ f.render() }}{{ t.<ref>render() }}",
    )
    fun `test variable of an imported template`() = check(
        "m.j2" to "{% set <def>version = '1' %}",
        "page.j2" to "{% import \"m.j2\" as m %}{{ m.<ref>version }}",
    )
    fun `test name imported under an alias`() = check(
        "m.j2" to "{% macro <def>field() %}{% endmacro %}",
        "page.j2" to "{% from 'm.j2' import <ref>field as f %}{{ f() }}",
    )
    fun `test alias of an imported name`() = check("page.j2" to "{% from 'm.j2' import field as <def>f, other %}{{ <ref>f() }}")
    fun `test attribute of a plain variable is not a member`() {
        val text = "{% import \"m.j2\" as m %}{% set u = 1 %}{{ u.render }}{{ 'm.render' }}"
        val file = myFixture.configureByText("page.j2", text)
        assertEquals(listOf("u"), Fmt.links(file).refs.map { it.range.substring(text) })
    }

    // ---------------------------------------------------------------- lexing

    fun `test braces inside an expression do not close it`() {
        val text = "{% set x = 1 %}{{ {'a': {'b': 1}} | combine(x) }}"
        val seen = tokens("a.j2", text)
        assertToken(seen, Cat.FUNCTION, "combine")
        assertEquals(1, seen.count { it == Cat.RBRACE to "}}" })
        assertEquals(listOf(text.lastIndexOf("x")), resolvedOffsets(myFixture.file))
    }
    fun `test closing delimiter inside a string does not close the tag`() {
        val seen = tokens("a.j2", "{% set s = '%}' ~ \"}}\" %}{{ s }}")
        assertToken(seen, Cat.STRING, "'%}'"); assertToken(seen, Cat.STRING, "\"}}\"")
        assertEquals(1, seen.count { it == Cat.RBRACE to "%}" })
        val custom = tokens("a.yml.j2", "key: << '>>' ~ x >>\n")
        assertToken(custom, Cat.STRING, "'>>'"); assertToken(custom, Cat.VARIABLE, "x")
    }
    fun `test unbalanced tag still ends at its delimiter`() {
        val seen = tokens("a.j2", "{{ foo( }} it's {{ bar] }} {{ baz }}")
        assertEquals(3, seen.count { it == Cat.RBRACE to "}}" })
        assertToken(seen, Cat.VARIABLE, "baz")
    }
    fun `test keywords`() = tokens("a.j2", "{% autoescape false %}{% endautoescape %}{% block a scoped %}{% endblock %}{% block b required %}{% endblock %}").let {
        assertToken(it, Cat.KEYWORD, "autoescape"); assertToken(it, Cat.KEYWORD, "endautoescape"); assertToken(it, Cat.KEYWORD, "scoped"); assertToken(it, Cat.KEYWORD, "required")
    }
    fun `test numbers`() = tokens("a.j2", "{{ 1e5 + 1_000 + 2.5e-3 + 0x1F + 42 + 4.2 }}").let {
        assertEquals(listOf("1e5", "1_000", "2.5e-3", "0x1F", "42", "4.2"), it.filter { (cat, _) -> cat == Cat.NUMBER }.map { (_, text) -> text })
    }
}
