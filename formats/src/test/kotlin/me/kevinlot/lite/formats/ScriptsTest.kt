package me.kevinlot.lite.formats

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class ScriptsTest : FmtTestCase() {
    private val d = "$"

    private val workflow = """
        on: push
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - id: tag
                run: |
                  version=$(git describe)
                  if [ "$d{{ inputs.dry-run }}" == 'true' ]; then
                    echo "dry $d{version}"
                  fi
                  cat <<EOF >> "${d}GITHUB_OUTPUT"
                  value=${d}version
                  EOF
              - run: echo "one line"
              - shell: python
                run: |
                  print("not shell: $(")
    """.trimIndent()

    private fun injected(text: String, at: String): PsiFile? {
        val file = myFixture.configureByText("ci.yml", text)
        val element = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, text.indexOf(at)) ?: return null
        return element.containingFile
    }

    fun `test a run block is shell, without its indentation nor its expressions`() {
        val script = injected(workflow, "git describe")!!
        assertEquals("LiteShell", script.language.id)
        assertEquals(
            "version=$(git describe)\nif [ \"__name__\" == 'true' ]; then\n  echo \"dry $d{version}\"\nfi\ncat <<EOF >> \"${d}GITHUB_OUTPUT\"\nvalue=${d}version\nEOF",
            script.text,
        )
        assertEmpty(PsiTreeUtil.findChildrenOfType(script, PsiErrorElement::class.java))
    }

    fun `test a step with another shell is left as text`() = assertNull(injected(workflow, "print("))

    fun `test nothing is reported in the workflow`() {
        myFixture.configureByText("ci.yml", workflow)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun `test a variable of the script leads to its assignment`() {
        myFixture.configureByText("ci.yml", workflow.replace("echo \"dry $d{version}\"", "echo \"dry $d{ver<caret>sion}\""))
        val target = myFixture.elementAtCaret
        assertEquals("LiteShell", target.language.id)
        assertTrue(target.text, target.text.startsWith("version"))
    }

    fun `test expressions of a script still lead to the workflow`() = check(
        "ci.yml" to "on:\n  workflow_dispatch:\n    inputs:\n      <def>dry-run:\n        type: boolean\njobs:\n  a:\n    steps:\n      - run: |\n          echo \"$d{{ inputs.<ref>dry-run }}\"\n",
    )

    fun `test a block under another key is not shell`() = assertNull(injected("description: |\n  echo hello\n", "echo"))

    // ---------------------------------------------------------------- other formats

    private fun script(name: String, text: String, at: String): String? {
        val file = myFixture.configureByText(name, text)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, text.indexOf(at))?.containingFile?.text
    }

    private fun clean(name: String, text: String) {
        myFixture.configureByText(name, text)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun `test dockerfile run, with flags and continued lines`() {
        val text = "FROM node\nARG VERSION=1\nRUN --mount=type=cache,target=/root/.npm npm ci && \\\n    # a comment in the middle\n    echo \"${d}VERSION ${d}HOME\"\nCMD [\"node\", \"x.js\"]\nENTRYPOINT exec node x.js\n"
        assertEquals("npm ci && \\\n    # a comment in the middle\n    echo \"${d}__name__ ${d}HOME\"", script("Containerfile", text, "npm ci"))
        assertEquals("exec node x.js", script("Containerfile", text, "exec node"))
        assertNull(script("Containerfile", text, "\"node\""))
        clean("Containerfile", text)
    }

    fun `test dockerfile argument is still the dockerfile's`() = check("Containerfile" to "FROM node\nARG <def>VERSION=1\nRUN echo \"${d}<ref>VERSION\"\n")

    fun `test makefile recipe without its tab, prefixes and make variables`() {
        val text = "CC := gcc\nall: main.o\n\t@echo building\n\t-${d}(CC) -o app main.o && \\\n\t  echo ${d}${d}HOME\n\nclean:\n\trm -f app\n"
        assertEquals("echo building\n${d}(__name__) -o app main.o && \\\n  echo ${d}HOME", script("Makefile", text, "echo building"))
        assertEquals("rm -f app", script("Makefile", text, "rm -f"))
        clean("Makefile", text)
    }

    fun `test a continued recipe line keeps the blanks after its tab`() {
        val text = "check:\n\t@if [ \"${d}{dir}\" = \"\" ]; then\\\n\t\techo empty;\\\n\tfi\n"
        assertEquals("if [ \"${d}{dir}\" = \"\" ]; then\\\n\techo empty;\\\nfi", script("Makefile", text, "echo empty"))
        clean("Makefile", text)
    }

    fun `test a conditional indented with a tab is make's`() =
        assertNull(script("Makefile", "\tifneq (${d}(A), )\n\tARGS := x\n\tendif\nall:\n\techo x\n", "ifneq"))

    fun `test makefile variable in a recipe is still make's`() = check("Makefile" to "<def>CC := gcc\nall:\n\t${d}(<ref>CC) -o app\n")

    fun `test a tab after a continued assignment is no recipe`() =
        assertNull(script("Makefile", "SRC = a.c \\\n\tb.c\nall:\n\techo x\n", "b.c"))

    fun `test just recipe lines and interpolations`() {
        val text = "version := '1'\nbuild target='x':\n    @echo {{target}}\n    cargo build --release\n\ntest:\n    cargo test\n"
        assertEquals("echo __name__\ncargo build --release", script("justfile", text, "cargo build"))
        assertEquals("cargo test", script("justfile", text, "cargo test"))
        clean("justfile", text)
    }

    fun `test just shebang recipe`() {
        val bash = "run:\n    #!/usr/bin/env bash\n    set -euo pipefail\n    if true; then\n      echo ok\n    fi\n"
        assertEquals("set -euo pipefail\nif true; then\n  echo ok\nfi", script("justfile", bash, "set -euo"))
        clean("justfile", bash)
        assertNull(script("justfile", "run:\n    #!/usr/bin/env python3\n    print('x')\n", "print"))
    }

    fun `test just interpolation is still just's`() = check("justfile" to "<def>version := '1'\nrelease:\n    git tag v{{ <ref>version }}\n")

    fun `test mise task scripts`() {
        val text = "[tools]\nnode = \"22\"\nrun = \"not a task\"\n\n[tasks.build]\ndescription = \"build\"\nrun = \"\"\"\nset -e\ncargo build {{arg(name=\"profile\")}}\n\"\"\"\n\n[tasks.lint]\nrun = ['cargo fmt --check', 'cargo clippy']\n"
        assertEquals("set -e\ncargo build __name__\n", script("mise.toml", text, "cargo build"))
        assertEquals("cargo clippy", script("mise.toml", text, "cargo clippy"))
        assertNull(script("mise.toml", text, "not a task"))
        assertNull(script("mise.toml", text, "build\"\n"))
        clean("mise.toml", text)
    }

    fun `test other toml files are left alone`() = assertNull(script("Cargo.toml", "[package]\nrun = \"echo x\"\n", "echo"))

    fun `test a file of tasks names each task by its table`() =
        assertEquals("cargo build", script("tasks.toml", "[build]\nrun = \"cargo build\"\n", "cargo"))

    /** LITE_FMT_CORPUS=<dir:dir>: the shell scripts held by other formats, per kind of file, and those the shell parser reports an error in. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_FMT_CORPUS")?.split(':')?.map { java.io.File(it) } ?: return
        dirs.forEach { com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val manager = InjectedLanguageManager.getInstance(project)
        val skipped = setOf("node_modules", ".git", "target", "build", "dist", ".terraform", ".terragrunt-cache", ".venv")
        val wanted = Regex("""(?i).*\.(?:ya?ml|toml|mk|just|dockerfile)|Dockerfile.*|Containerfile|Makefile|justfile""")
        val scripts = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in skipped }.filter { it.isFile && it.length() < 1_000_000 && wanted.matches(it.name) } }
            .mapIndexedNotNull { index, file ->
                // copied into the test project; the bundled Docker plugin takes the name `Dockerfile` in tests, `Containerfile` is ours alone
                val name = "Containerfile".takeIf { file.name.startsWith("Dockerfile") } ?: file.name
                myFixture.addFileToProject("corpus/$index/$name", file.readText()) as? FmtFile
            }
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { Scripts.pieces(it).isNotEmpty() } }
            .mapNotNull { word -> manager.getInjectedPsiFiles(word)?.firstOrNull()?.first as? PsiFile }
        val broken = scripts.mapNotNull { script -> PsiTreeUtil.findChildOfType(script, PsiErrorElement::class.java)?.let { script to it } }
        println("script corpus: ${scripts.groupingBy { manager.getTopLevelFile(it).name.substringAfterLast('.') }.eachCount()}, ${broken.size} with a shell syntax error")
        broken.groupingBy { (_, error) -> "${error.errorDescription} at '${error.text.take(30)}' <- ${manager.getTopLevelFile(error).name}: ${error.parent.text.take(60).replace('\n', ' ')}" }
            .eachCount().entries.sortedByDescending { it.value }.take(20).forEach { println("  ${it.value} x ${it.key}") }
    }
}
