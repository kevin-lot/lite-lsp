package me.kevinlot.lite.formats

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * HCL (Terraform, Terragrunt): `var.x`, `local.x`, `module.x`, `data.t.n`, `t.n` (resources), `dependency.x`, `include.x`
 * → their blocks, across the `.tf` files of the folder (a Terraform module); `.tfvars` keys → `variable`.
 * `local.x.attribute` / `var.x.attribute` → the key of the object the local holds, or of the variable's `type` or `default`;
 * the variable of a `for` and the iterator of a `dynamic` block → where it is named; `provider = aws.west` → its `alias`.
 * Across modules: `module.x.out` → the output of the module's folder, `dependency.x.outputs.out` → the output of the
 * stack that unit deploys, `include.x.locals.y` → the local of the included file, the arguments of a `module` block
 * and the keys of Terragrunt `inputs` → the variable they set. `source`/`config_path`/`path` and
 * `find_in_parent_folders("x")` → the folder or file (see HclModules.kt for the Terragrunt path functions).
 */
object Hcl : Format("Hcl", "HCL", "tf") {
    override val lineComment = "#"
    override val blockComment = "/*" to "*/"
    override val nameCategories = setOf(Cat.VARIABLE, Cat.STRING, Cat.KEY, Cat.WORD)
    private val RESERVED = setOf("var", "local", "module", "data", "dependency", "each", "count", "path", "terraform", "self", "include")
    private val META_ARGUMENTS = setOf("count", "depends_on", "for_each", "providers", "source", "version")
    private const val FIND_IN_PARENT = "find_in_parent_folders:"
    private const val HCL_NAME = """[A-Za-z_][\w-]*"""
    private val FILE_CALL = Regex("""\b(?:file|templatefile|fileexists)\(\s*"(?:\$\{path\.module}/)?([^"$]+)"""")
    private val FIND_IN_PARENT_CALL = Regex("""\bfind_in_parent_folders\(\s*"([^"\n$]+)"""")
    private val LABELLED_BLOCKS = listOf("var" to "variable", "module" to "module", "output" to "output", "dep" to "dependency", "include" to "include")
        .map { (kind, block) -> kind to Regex("""(?m)^\s*$block\s+"([^"]+)"""") }
    private val LOCAL_KEY = Regex("""\s*([A-Za-z_][\w-]*)\s*=(?![=>])""")
    private val LOCALS = Regex("""(?m)^\s*locals\s*\{""")
    private val NAMED_USE = Regex("""\b(var|local|module|dependency|include)\.([A-Za-z_][\w-]*)""")
    private val OBJECT_PATH = Regex("""\b(var|local)\.($HCL_NAME)((?:\.$HCL_NAME)+)""")
    private val PARSED = Key.create<CachedValue<HclFile>>("lite.hcl.parsed")
    private val PATH_ATTRIBUTE = Regex("""\b(?:source|config_path|path)\s*=\s*"([^"\n]+)"""")
    private val PROVIDER_ALIAS = Regex("""\balias[ \t]*=[ \t]*"([^"\n]+)"""")
    private val PROVIDER_USE = Regex("""\bprovider[ \t]*=[ \t]*($HCL_NAME)\.($HCL_NAME)""")
    private val PROVIDER_VALUE = Regex("""=[ \t]*($HCL_NAME)\.($HCL_NAME)""")
    private val PROVIDERS = Regex("""\bproviders[ \t]*=[ \t]*\{[^}]*}""")
    // what another module or file defines: resolved by `resolve` below
    private val REMOTE_USES = listOf(
        """\bmodule\.($HCL_NAME)(?:\[[^\]\n]*])?\.($HCL_NAME)""" to "modout",
        """\bdependency\.($HCL_NAME)\.outputs\.($HCL_NAME)""" to "depout",
        """\binclude\.($HCL_NAME)\.locals\.($HCL_NAME)""" to "inclocal",
    ).map { (pattern, kind) -> Regex(pattern) to kind }
    private val RESOURCE_USE = Regex("""(?<![\w.])([a-z][a-z0-9]*_[\w-]+)\.([A-Za-z_][\w-]*)""")
    private val TFVARS_KEY = Regex("""(?m)^\s*([A-Za-z_][\w-]*)\s*=""")
    private val TYPED_BLOCKS = listOf("res" to "resource", "data" to "data", "ephemeral" to "ephemeral")
        .map { (kind, block) -> kind to Regex("""(?m)^\s*$block\s+"([^"]+)"\s+"([^"]+)"""") }
    private val TYPED_USE = Regex("""\b(data|ephemeral)\.($HCL_NAME)\.($HCL_NAME)""")
    // where the attributes of `var.x.attribute` are written, and the kind they are declared as
    private val VARIABLE_OBJECTS = mapOf("default" to "var", "type" to "vartype")
    /** Strings are lexed by [HclLexer]. */
    override val rules = listOf(
        Rule("""(?:#|//)[^\n]*""", Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""<<-?(\w+)[\s\S]*?\n\s*\1(?=\s*$)""", Cat.STRING, flags = java.util.regex.Pattern.MULTILINE),
        Rule(
            """(?:resource|data|variable|output|module|provider|locals|terraform|backend|required_providers|dynamic|moved|import|check|removed""" +
                """|include|dependency|dependencies|generate|remote_state|inputs)\b""",
            Cat.KEYWORD, lineStart = true,
        ),
        // whatever opens a block: `lifecycle {`, `provisioner "local-exec" {`
        Rule("""[A-Za-z_][\w-]*+(?=(?:[ \t]+"[^"\n]*")*[ \t]*\{)""", Cat.KEYWORD, lineStart = true),
        Rule("""\b(?:true|false|null)\b""", Cat.CONSTANT),
        Rule("""\b(?:for|in|if|endfor|endif)\b""", Cat.KEYWORD),
        Rule("""[A-Za-z_][\w-]*(?=\s*=(?![=>]))""", Cat.KEY),
        Rule("""[a-z_][\w]*(?=\()""", Cat.FUNCTION),
        Rule("""[A-Za-z_][\w-]*(?:\.(?:[A-Za-z_*][\w-]*|\d+)|\[[^\]\n]*])+""", Cat.VARIABLE),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            LABELLED_BLOCKS.forEach { (kind, block) -> block.ranges(text).forEach { (r, n) -> decls += Decl(r, "$kind:$n") } }
            TYPED_BLOCKS.forEach { (kind, block) ->
                block.findAll(text).forEach { m ->
                    val g = m.groups[2] ?: return@forEach
                    decls += Decl(TextRange(g.range.first, g.range.last + 1), "$kind:${m.groupValues[1]}.${g.value}")
                }
            }
            // keys of `locals { … }` blocks (depth 1 only)
            LOCALS.findAll(text).forEach { m ->
                var depth = 1
                var i = m.range.last + 1
                val start = i
                while (i < text.length && depth > 0) { when (text[i]) { '{' -> depth++; '}' -> depth-- }; i++ }
                val body = text.subSequence(start, i)
                var d = 0
                var lineStart = 0
                for (j in body.indices) {
                    if (j == lineStart && d == 0) LOCAL_KEY.matchAt(body, j)?.groups?.get(1)?.let { g ->
                        decls += Decl(TextRange(start + g.range.first, start + g.range.last + 1), "local:${g.value}")
                    }
                    when (body[j]) { '{', '[', '(' -> d++; '}', ']', ')' -> d--; '\n' -> lineStart = j + 1 }
                }
            }
            val parsed = HclFile(text)
            decls += decls.filter { it.key.startsWith("local:") }.flatMap { local ->
                nestedKeys(parsed, HclAttribute(name = local.key.substringAfter(':'), offset = local.range.startOffset), local.key)
            }
            parsed.blocks.filter { it.type == "variable" }.forEach { block ->
                val variable = block.labels.lastOrNull()?.first ?: return@forEach
                parsed.keys(block.body.first).forEach { attribute ->
                    VARIABLE_OBJECTS[attribute.name]?.let { kind -> decls += nestedKeys(parsed, attribute, "$kind:$variable") }
                }
            }
            PROVIDER_ALIAS.ranges(text).forEach { (r, alias) ->
                val provider = parsed.blockAt(r.startOffset)?.takeIf { it.type == "provider" }?.labels?.firstOrNull()?.first ?: return@forEach
                decls += Decl(r, "provider:$provider.$alias")
            }
            parsed.iterators().forEach { decls += Decl(TextRange.from(it.offset, it.name.length), iteratorKey(it)) }
            parsed.iteratorUses().forEach { (at, iterator) -> refs += Ref(TextRange.from(at, iterator.name.length), listOf(iteratorKey(iterator))) }
            NAMED_USE.findAll(text).forEach { m ->
                val g = m.groups[2] ?: return@forEach
                val kind = when (m.groupValues[1]) { "dependency" -> "dep"; else -> m.groupValues[1] }
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("$kind:${g.value}"))
            }
            OBJECT_PATH.findAll(text).forEach { m ->
                val tail = m.groups[3] ?: return@forEach
                val kinds = when (m.groupValues[1]) { "var" -> listOf("vartype", "var"); else -> listOf("local") }
                val names = tail.value.drop(1).split('.')
                names.indices.forEach { index ->
                    val start = tail.range.first + 1 + names.take(index).sumOf { it.length + 1 }
                    val path = (listOf(m.groupValues[2]) + names.take(index + 1)).joinToString(".")
                    refs += Ref(TextRange.from(start, names[index].length), kinds.map { "$it:$path" })
                }
            }
            val passedProviders = PROVIDERS.findAll(text)
                .flatMap { map -> PROVIDER_VALUE.findAll(text, map.range.first).takeWhile { it.range.last <= map.range.last } }
            (PROVIDER_USE.findAll(text) + passedProviders).forEach { m ->
                val g = m.groups[2] ?: return@forEach
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("provider:${m.groupValues[1]}.${g.value}"))
            }
            REMOTE_USES.forEach { (pattern, kind) ->
                pattern.findAll(text).forEach { m ->
                    m.groups[2]?.let { refs += Ref(TextRange(it.range.first, it.range.last + 1), listOf("$kind:${m.groupValues[1]}.${it.value}")) }
                }
            }
            parsed.blocks.filter { it.type == "module" }.forEach { block ->
                val module = block.labels.lastOrNull()?.first ?: return@forEach
                parsed.attributes(block.body.first + 1..block.body.last).filterNot { it.name in META_ARGUMENTS }.forEach {
                    refs += Ref(TextRange(it.offset, it.offset + it.name.length), listOf("modarg:$module.${it.name}"))
                }
            }
            if (fileName.endsWith(".hcl")) {
                parsed.attributes(0 until text.length).filter { it.name == "inputs" }.flatMap(parsed::entries).forEach {
                    refs += Ref(TextRange(it.offset, it.offset + it.name.length), listOf("input:${it.name}"))
                }
            }
            TYPED_USE.findAll(text).forEach { m ->
                val g = m.groups[3] ?: return@forEach
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("${m.groupValues[1]}:${m.groupValues[2]}.${g.value}"))
            }
            RESOURCE_USE.findAll(text).forEach { m ->
                if (m.groupValues[1] in RESERVED) return@forEach
                val g = m.groups[2] ?: return@forEach
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("res:${m.groupValues[1]}.${g.value}"))
            }
            if (fileName.endsWith(".tfvars")) TFVARS_KEY.ranges(text).forEach { (r, n) -> refs += Ref(r, listOf("var:$n")) }
            val paths = PATH_ATTRIBUTE.ranges(text).filterNot { (_, p) -> isRemoteHclSource(p) }.map { (r, p) -> PathLink(r, p) } +
                FIND_IN_PARENT_CALL.ranges(text).map { (r, p) -> PathLink(r, FIND_IN_PARENT + p) } +
                FILE_CALL.ranges(text).map { (r, p) -> PathLink(r, p) }
            return Links(decls, refs, paths.toList())
        }

        /** Terragrunt's directory functions and `//` are understood; a remote source (git, registry) leads nowhere. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            val from = file.originalFile.virtualFile ?: return null
            val target = when {
                path.startsWith(FIND_IN_PARENT) -> resolveHclPath("find_in_parent_folders(\"${path.removePrefix(FIND_IN_PARENT)}\")", from)
                else -> resolveHclLiteralPath(from, path)
            } ?: return null
            return PsiManager.getInstance(file.project).findItem(target)
        }

        /** References into another module or file; null leaves the others to the folder-wide lookup. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val key = ref.keys.firstOrNull() ?: return null
            val name = key.substringAfter(':').substringAfter('.')
            val owner = key.substringAfter(':').substringBefore('.')
            val from = file.originalFile.virtualFile ?: return null
            val (files, target) = when (key.substringBefore(':')) {
                "modout" -> moduleFiles(file, owner) to "output:$name"
                "modarg" -> moduleFiles(file, owner) to "var:$name"
                "input" -> folderFiles(file, terragruntSourceDir(from)) to "var:$name"
                "depout" -> folderFiles(file, dependencyUnits(file, owner).firstNotNullOfOrNull(::terragruntSourceDir)) to "output:$name"
                "inclocal" -> includedFiles(from).mapNotNull { PsiManager.getInstance(file.project).findFile(it) } to "local:$name"
                "iter" -> listOf(file) to key
                else -> return null
            }
            return Fmt.declarations(files, Hcl, target)
        }

        private fun moduleFiles(file: PsiFile, module: String): List<PsiFile> =
            Fmt.scope(file).flatMap { member ->
                val parsed = parsed(member)
                val from = member.originalFile.virtualFile ?: return@flatMap emptyList()
                parsed.blocks.filter { it.type == "module" && it.labels.lastOrNull()?.first == module }
                    .flatMap { folderFiles(file, moduleDir(block = it, file = from, parsed = parsed)) }
            }

        private fun dependencyUnits(file: PsiFile, dependency: String): List<com.intellij.openapi.vfs.VirtualFile> {
            val parsed = parsed(file)
            val from = file.originalFile.virtualFile ?: return emptyList()
            return parsed.blocks.filter { it.type == "dependency" && it.labels.lastOrNull()?.first == dependency }
                .mapNotNull { dependencyFile(block = it, file = from, parsed = parsed) }
        }

        private fun folderFiles(context: PsiFile, dir: com.intellij.openapi.vfs.VirtualFile?): List<PsiFile> {
            val manager = PsiManager.getInstance(context.project)
            return dir?.children.orEmpty().filter { it.extension == "tf" || it.extension == "tfvars" }.sortedBy { it.name }.mapNotNull { manager.findFile(it) }
        }

        /** A Terraform module is a folder: its `.tf`/`.tfvars` files share their names. Terragrunt files stand alone. */
        override fun scope(file: PsiFile): List<PsiFile> {
            if (file.name.endsWith(".hcl")) return listOf(file)
            val dir = file.originalFile.containingDirectory ?: return listOf(file)
            return listOf(file) + dir.files.filter { it != file && (it.name.endsWith(".tf") || it.name.endsWith(".tfvars")) }
        }
    }

    override fun lexer(): FmtLexer = HclLexer(this)

    // An iterator is known by where it is declared: the name alone would join the `s` of two loops.
    private fun iteratorKey(iterator: HclIterator) = "iter:${iterator.offset}.${iterator.name}"

    /** The keys of the object [owner] holds, at every depth: `cfg = { db = { port = 1 } }` declares `cfg.db` and `cfg.db.port`. */
    private fun nestedKeys(parsed: HclFile, owner: HclAttribute, path: String): List<Decl> =
        parsed.entries(owner).flatMap {
            listOf(Decl(TextRange.from(it.offset, it.name.length), "$path.${it.name}")) + nestedKeys(parsed, it, "$path.${it.name}")
        }

    /** The structure of [file], parsed once per PSI change: resolving a module name reads every file of the folder. */
    private fun parsed(file: PsiFile): HclFile =
        CachedValuesManager.getCachedValue(file, PARSED) { CachedValueProvider.Result.create(HclFile(file.text), PsiModificationTracker.MODIFICATION_COUNT) }
}
