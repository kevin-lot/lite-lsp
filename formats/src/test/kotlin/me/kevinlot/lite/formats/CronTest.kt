package me.kevinlot.lite.formats

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

class CronTest : FmtTestCase() {
    private fun parse(text: String): PsiFile = PsiFileFactory.getInstance(project).createFileFromText("x.cron", CronFileType, text)
    private fun errors(text: String) = PsiTreeUtil.findChildrenOfType(parse(text), PsiErrorElement::class.java).map { "${it.text}: ${it.errorDescription}" }
    private fun fields(text: String) = PsiTreeUtil.findChildrenOfType(parse(text), PsiElement::class.java)
        .filter { it.elementType in CronTokens.FIELDS }.map { "${it.elementType}:${it.text}" }

    // ---------------------------------------------------------------- the language

    fun `test five fields`() {
        assertEquals(listOf("MINUTE:17", "HOUR:5", "DAY_OF_MONTH:*", "MONTH:*", "DAY_OF_WEEK:1"), fields("17 5 * * 1"))
        assertEmpty(errors("17 5 * * 1"))
    }

    fun `test steps, lists, ranges and names`() {
        assertEquals(listOf("MINUTE:*/15", "HOUR:8-18/2", "DAY_OF_MONTH:1,15", "MONTH:JAN-MAR", "DAY_OF_WEEK:mon-fri"), fields("*/15 8-18/2 1,15 JAN-MAR mon-fri"))
        assertEmpty(errors("*/15 8-18/2 1,15 JAN-MAR mon-fri"))
    }

    fun `test aws cron has six fields and one question mark`() {
        assertEquals(listOf("MINUTE:0", "HOUR:1", "DAY_OF_MONTH:*", "MONTH:*", "DAY_OF_WEEK:?", "YEAR:*"), fields("cron(0 1 * * ? *)"))
        assertEmpty(errors("cron(0 1 * * ? *)"))
    }

    fun `test aws special forms`() = listOf(
        "cron(0 18 ? * MON-FRI 2026)", "cron(0 8 LW * ? *)", "cron(0 8 15W * ? *)", "cron(0 8 L * ? *)",
        "cron(0 8 ? * 6#3 *)", "cron(0 8 ? * 6L *)", "cron(0 8 ? * FRIL *)", "cron(0/5 8-17 ? * 2-6 *)", "cron( 0 1 * * ? * )",
    ).forEach { assertEquals(it, emptyList<String>(), errors(it)) }

    fun `test aws day rule`() {
        val rule = "AWS wants ? in exactly one of day-of-month and day-of-week"
        assertEquals(listOf("*: $rule"), errors("cron(0 1 * * * *)"))
        assertEquals(listOf("?: $rule"), errors("cron(0 1 ? * ? *)"))
    }

    fun `test wrong number of fields`() {
        assertEquals(listOf(": Expected 6 fields (minute hour day-of-month month day-of-week year), found 5"), errors("cron(0 1 * * ?)"))
        assertEquals(listOf(": Expected 6 fields (minute hour day-of-month month day-of-week year), found 0"), errors("cron()"))
        assertEquals(listOf(": Expected 5 fields (minute hour day-of-month month day-of-week), found 4"), errors("5 * * 1"))
        assertEquals(listOf("2026: Unexpected field: 5 are expected"), errors("0 5 * * 1 2026"))
    }

    fun `test values out of range and unknown names`() {
        assertEquals(listOf("61: minute must be between 0 and 59"), errors("61 5 * * 1"))
        assertEquals(listOf("24: hour must be between 0 and 23"), errors("cron(0 24 * * ? *)"))
        assertEquals(listOf("1-32: day-of-month must be between 1 and 31"), errors("0 0 1-32 * *"))
        assertEquals(listOf("FOO: 'FOO' is not a month"), errors("0 0 1 FOO *"))
        assertEquals(listOf("*/0: Expected a step after /"), errors("*/0 * * * *"))
        assertEquals(listOf("5#3: Unexpected '#' in day-of-week"), errors("0 0 * * 5#3"))
    }

    fun `test question mark outside aws`() = assertEquals(listOf("?: ? is only valid in AWS cron(…)"), errors("0 5 ? * 1"))

    fun `test aws rate`() {
        assertEmpty(errors("rate(5 minutes)"))
        assertEmpty(errors("rate(1 hour)"))
        assertEmpty(errors("rate(1 minutes)"))
        assertEquals(listOf("0: Expected a positive number"), errors("rate(0 minutes)"))
        assertEquals(listOf("weeks: Expected minute(s), hour(s) or day(s)"), errors("rate(5 weeks)"))
        assertEquals(listOf("extra: Unexpected text after the expression"), errors("rate(5 minutes) extra"))
    }

    fun `test an empty expression`() = assertEquals(1, errors("").size)

    // ---------------------------------------------------------------- inside YAML and HCL

    private val fieldKeys = listOf(Cat.COL0, Cat.COL1, Cat.COL2, Cat.COL3, Cat.COL4, Cat.COL5, Cat.FUNCTION).mapNotNull { it.key }

    private fun colored(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting().filter { it.forcedTextAttributesKey in fieldKeys }.sortedBy { it.startOffset }.map { it.text }
    }

    private fun reported(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).sortedBy { it.startOffset }.map { "${it.text}: ${it.description}" }
    }

    fun `test yaml fields are colored, comments are not`() = assertEquals(
        listOf("17", "5", "*", "*", "1", "cron", "0", "3", "*", "*", "?", "*", "0", "6", "*", "*", "1-5"),
        colored("a.yml", "# cron: '1 2 3 4 5'\njobs:\n  - cron: '17 5 * * 1'\n  - schedule: cron(0 3 * * ? *)\n  - cron: 0 6 * * 1-5\n"),
    )

    fun `test terraform schedule expression is colored`() = assertEquals(
        listOf("cron", "0", "1", "*", "*", "?", "*"),
        colored("main.tf", "resource \"aws_cloudwatch_event_rule\" \"nightly\" {\n  schedule_expression = \"cron(0 1 * * ? *)\"\n}\n"),
    )

    fun `test a syntax error is reported in yaml and in terraform`() {
        assertEquals(listOf("61: minute must be between 0 and 59"), reported("a.yml", "on:\n  schedule:\n    - cron: '61 5 * * 1'\n"))
        assertEquals(
            listOf("*: AWS wants ? in exactly one of day-of-month and day-of-week"),
            reported("main.tf", "resource \"x\" \"y\" {\n  schedule_expression = \"cron(0 1 * * * *)\"\n}\n"),
        )
    }

    fun `test what is not an expression is left alone`() {
        val yaml = """
            description: It can only be a cron cron() or a rate expression rate().
            schedule: every day at noon
            schedule_expression: cron(0 ${'$'}{self:custom.hour} * * ? *)
            name: cron
            rate: 5
        """.trimIndent()
        assertEmpty(reported("a.yml", yaml))
        assertEmpty(colored("a.yml", yaml))
        assertEmpty(reported("main.tf", "x = \"cron(0 ${'$'}{var.hour} * * ? *)\"\ny = \"a cron() in a sentence\"\n"))
    }

    /** LITE_FMT_CORPUS=<dir:dir>: the cron expressions found in YAML and HCL files, and those with a syntax error. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_FMT_CORPUS")?.split(':')?.map { java.io.File(it) } ?: return
        dirs.forEach { com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val skipped = setOf("node_modules", ".git", "target", "build", ".terraform", ".terragrunt-cache")
        val expressions = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in skipped }.filter { it.isFile && it.extension in setOf("yml", "yaml", "tf", "hcl", "tfvars") } }
            .mapNotNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)?.let(psiManager::findFile) as? FmtFile }
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).mapNotNull { word -> CronPlaces.expression(word)?.substring(word.text) } }
        val broken = expressions.filter { errors(it).isNotEmpty() }
        println("cron corpus: ${expressions.size} expressions, ${broken.size} with a syntax error")
        broken.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).forEach { println("  ${it.value} x ${it.key} -> ${errors(it.key)}") }
    }
}
