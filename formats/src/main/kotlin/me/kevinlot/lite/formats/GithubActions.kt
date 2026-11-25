package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/*
 * GitHub Actions workflows and composite actions, on top of the YAML keys (every key is already a target, by its
 * JSON pointer): the names of `${{ … }}` expressions and of `if:` conditions lead to the job, step, input, output,
 * environment variable, matrix key or secret they name; `needs:` leads to the job; a local `uses:` to its file, and
 * the `with:` / `secrets:` given to it to the inputs and secrets that file declares.
 */
object GithubActions {
    private class Key(val range: TextRange, val path: List<String>)
    private class Step(val job: String, val prefix: List<String>, val first: Int, val last: Int, val id: String?)

    private val CONTEXT = Regex("""(?<![.\w-])(needs|steps|inputs|env|matrix|secrets|jobs|github\.event\.inputs)\.([A-Za-z_][\w-]*)(?:\.outputs\.([A-Za-z_][\w-]*))?""")
    private val EXPRESSION = Regex("""\$\{\{[\s\S]*?}}""")
    private val NAME = Regex("""[A-Za-z_][\w-]*""")
    private val OUTPUT = Regex("""(?m)\b([A-Za-z_][\w-]*)=.*>>\s*"?\$\{?GITHUB_OUTPUT""")
    private val VALUE = Regex("""["']?[ \t]*:[ \t]*(?:"([^"\n]*)"|'([^'\n]*)'|([^\s#'"][^\n#]*?))[ \t]*(?:#.*)?$""", RegexOption.MULTILINE)
    private val COMMENT = Regex("""#[^\n]*""")

    /** Declarations and references of a workflow or an action; null for any other YAML. [keys]: [Pointers.yamlKeys]. */
    fun links(text: CharSequence, keys: List<Pair<TextRange, String>>): Links? {
        val ordered = keys.map { (range, pointer) -> Key(range, pointer.split('/').drop(1).map(Pointers::unescape)) }
        if (ordered.none { it.path == listOf("jobs") || it.path == listOf("runs") }) return null
        return Reader(text, ordered).links()
    }

    /** Keys tried in order; `ref:` ones are JSON references into another file (a called workflow, a local action). */
    fun resolve(file: PsiFile, ref: Ref, linker: Linker): List<PsiElement>? {
        if (ref.keys.none { it.startsWith("ref:") }) return null
        return ref.keys.firstNotNullOfOrNull { key ->
            val found = when {
                key.startsWith("ref:") -> Pointers.resolve(file, key.removePrefix("ref:"), linker)
                else -> Fmt.declarations(listOf(file), Yaml, key)
            }
            found.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private class Reader(private val text: CharSequence, private val keys: List<Key>) {
        // a workflow of a few thousand keys asks these for each of its names: looked up, not scanned
        private val firstIndex = keys.indices.reversed().associateBy { keys[it].path }
        private val jobs = keys.filter { it.path.size == 2 && it.path[0] == "jobs" }
        private val jobStarts = jobs.map { it.range.startOffset }
        private val steps = steps()
        private val stepStarts = steps.map { keys[it.first].range.startOffset }
        private val envByName = keys.map { it.path }.filter { it.size >= 2 && it[it.size - 2] == "env" }.groupBy { it.last() }
        private val matrixIncludes = keys.map { it.path }.filter { it.size == 7 && it[0] == "jobs" && it[2] == "strategy" && it[3] == "matrix" && it[4] == "include" }
            .groupBy { it[1] to it.last() }

        fun links(): Links {
            val uses = keys.indices.filter { keys[it].path.last() == "uses" }.mapNotNull { index -> value(index)?.takeIf { it.second.startsWith("./") } }
            return Links(
                decls = stepIds() + stepOutputs(),
                refs = (expressions() + needs() + given()).distinctBy { it.range },
                paths = uses.map { (range, path) -> PathLink(range, path) },
            )
        }

        // ---------------------------------------------------------------- structure

        private fun pointer(path: List<String>) = "key:/" + path.joinToString("/", transform = Pointers::escape)
        private fun regionEnd(index: Int) = keys.getOrNull(index + 1)?.range?.startOffset ?: text.length
        private fun jobAt(offset: Int): String = jobs.getOrNull(lastAtOrBefore(jobStarts, offset))?.path?.get(1).orEmpty()
        private fun stepAt(offset: Int): Step? = steps.getOrNull(lastAtOrBefore(stepStarts, offset))?.takeIf { offset < regionEnd(it.last) }

        /** The index of the last of the sorted [starts] that is not after [offset], or -1. */
        private fun lastAtOrBefore(starts: List<Int>, offset: Int): Int = starts.binarySearch(offset).let { found -> found.takeIf { it >= 0 } ?: (-found - 2) }

        /** The one-line value of the key at [index]: its range without quotes, and its text. */
        private fun value(index: Int): Pair<TextRange, String>? {
            val match = VALUE.matchAt(text, keys[index].range.endOffset) ?: return null
            val group = match.groups.drop(1).firstOrNull { it != null } ?: return null
            return TextRange(group.range.first, group.range.last + 1) to group.value
        }

        private fun steps(): List<Step> = keys.indices
            .mapNotNull { index -> keys[index].path.indexOf("steps").takeIf { it >= 0 && keys[index].path.size > it + 2 }?.let { index to keys[index].path.take(it + 2) } }
            .groupBy({ it.second }, { it.first })
            .map { (prefix, indices) ->
                val id = indices.firstOrNull { keys[it].path.size == prefix.size + 1 && keys[it].path.last() == "id" }?.let(::value)?.second
                Step(job = prefix.getOrNull(1).takeIf { prefix[0] == "jobs" }.orEmpty(), prefix = prefix, first = indices.first(), last = indices.last(), id = id)
            }

        // ---------------------------------------------------------------- declarations

        private fun stepIds(): List<Decl> = steps.mapNotNull { step ->
            val index = firstIndex[step.prefix + "id"] ?: return@mapNotNull null
            value(index)?.let { (range, id) -> Decl(range, "gha:step:${step.job}:$id") }
        }

        /** `name=value >> $GITHUB_OUTPUT` in the script of a step that has an id. */
        private fun stepOutputs(): List<Decl> = steps.filter { it.id != null }.flatMap { step ->
            val region = text.subSequence(keys[step.first].range.startOffset, regionEnd(step.last))
            OUTPUT.findAll(region).mapNotNull { it.groups[1] }.map { group ->
                val start = keys[step.first].range.startOffset + group.range.first
                Decl(TextRange.from(start, group.value.length), "gha:stepout:${step.job}:${step.id}:${group.value}")
            }.distinctBy { it.key }
        }

        // ---------------------------------------------------------------- references

        private fun expressions(): List<Ref> {
            val conditions = keys.indices.filter { keys[it].path.last() == "if" }.map { keys[it].range.endOffset until regionEnd(it) }
            return (EXPRESSION.findAll(text).map { it.range } + conditions).flatMap { region ->
                CONTEXT.findAll(text.subSequence(region.first, region.last + 1)).flatMap { context(it, region.first) }
            }.toList()
        }

        private fun context(match: MatchResult, offset: Int): List<Ref> {
            val kind = match.groupValues[1]
            val name = match.groups[2] ?: return emptyList()
            val output = match.groups[3]
            val at = offset + match.range.first
            val job = jobAt(at)
            fun ref(group: MatchGroup, keys: List<String>) = Ref(TextRange.from(offset + group.range.first, group.value.length), keys)
            return when (kind) {
                "env" -> listOf(ref(name, env(name.value, at)))
                "inputs", "github.event.inputs" -> listOf(ref(name, listOf("on/workflow_call/inputs", "on/workflow_dispatch/inputs", "inputs").map { "key:/$it/${Pointers.escape(name.value)}" }))
                "jobs", "needs" -> listOfNotNull(
                    ref(name, listOf(pointer(listOf("jobs", name.value)))),
                    output?.let { ref(it, listOf(pointer(listOf("jobs", name.value, "outputs", it.value))) + called(name.value, "outputs", it.value)) },
                )
                "matrix" -> listOf(ref(name, matrix(job, name.value)))
                "secrets" -> listOf(ref(name, listOf(pointer(listOf("on", "workflow_call", "secrets", name.value)))))
                "steps" -> listOfNotNull(
                    ref(name, listOf("gha:step:$job:${name.value}")),
                    output?.let { ref(it, listOf("gha:stepout:$job:${name.value}:${it.value}")) },
                )
                else -> emptyList()
            }
        }

        /** The step's own `env`, then the job's, the workflow's, and last the other steps of the job. */
        private fun env(name: String, offset: Int): List<String> {
            val job = jobAt(offset)
            val own = stepAt(offset)?.prefix
            val declared = envByName[name].orEmpty().filter { it[0] != "jobs" || it.getOrNull(1) == job }
            return declared.sortedBy { path ->
                when {
                    own != null && path.take(own.size) == own -> 0
                    "steps" !in path -> 1
                    else -> 2
                }
            }.map(::pointer)
        }

        private fun matrix(job: String, name: String): List<String> {
            val base = listOf("jobs", job, "strategy", "matrix")
            val included = matrixIncludes[job to name].orEmpty()
            return (listOf(base + name) + included).map(::pointer)
        }

        /** `needs: a`, `needs: [a, b]` or a list of items under it. */
        private fun needs(): List<Ref> = keys.indices.filter { keys[it].path.size == 3 && keys[it].path[0] == "jobs" && keys[it].path[2] == "needs" }.flatMap { index ->
            val start = keys[index].range.endOffset
            val region = COMMENT.replace(text.subSequence(start, regionEnd(index))) { " ".repeat(it.value.length) }
            NAME.findAll(region).map { Ref(TextRange.from(start + it.range.first, it.value.length), listOf(pointer(listOf("jobs", it.value)))) }
        }

        /** What a job or a step gives to a local workflow or action: each key leads to the input or secret it fills. */
        private fun given(): List<Ref> = keys.filter { it.path.size >= 2 && it.path[it.path.size - 2] in GIVEN }.mapNotNull { key ->
            val owner = key.path.dropLast(2)
            val target = local(owner) ?: return@mapNotNull null
            // a job calls a workflow, a step calls an action
            val place = "on/workflow_call/${GIVEN.getValue(key.path[key.path.size - 2])}".takeIf { owner.size == 2 } ?: "inputs"
            Ref(key.range, listOf("ref:$target#/$place/${Pointers.escape(key.path.last())}"))
        }

        private fun called(job: String, section: String, name: String): List<String> =
            listOfNotNull(local(listOf("jobs", job))?.let { "ref:$it#/on/workflow_call/$section/${Pointers.escape(name)}" })

        /** The local file a job or a step `uses`, if it does. */
        private fun local(owner: List<String>): String? {
            val index = firstIndex[owner + "uses"] ?: return null
            return value(index)?.second?.takeIf { it.startsWith("./") }
        }
    }

    private val GIVEN = mapOf("secrets" to "secrets", "with" to "inputs")
}
