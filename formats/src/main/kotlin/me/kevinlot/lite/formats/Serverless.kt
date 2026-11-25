package me.kevinlot.lite.formats

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/*
 * Serverless Framework and CloudFormation, on top of the YAML keys (every key is a target, by its JSON pointer).
 * Serverless variables: each segment of `${self:a.b.c}` leads to the key of that level in the service file, followed
 * through a `${file(…)}` value; `${file(./x.yml):a.b}` to the file and its keys; `${param:x}` to the parameter.
 * CloudFormation: `!Ref`, `!GetAtt`, `!Sub`, `DependsOn`, `Condition`, `!If`, `!FindInMap` (and their `Fn::` forms)
 * lead to the resource, parameter, condition or mapping, in the file or in the files the service includes.
 */
object Serverless {
    private const val NAME = """[A-Za-z][A-Za-z0-9]*"""
    private const val OPEN = """\s*(?:\[\s*|-\s*)?["']?"""

    private val CONDITION = Regex("""(?m)\bCondition:[ \t]*($NAME)[ \t]*(?:#.*)?$|(?:![Ii]f[ \t]+\[[ \t]*|Fn::If:$OPEN)($NAME)""")
    private val DEPENDS = Regex("""\bDependsOn:((?:[ \t]*\[[^\]\n]*]|[ \t]*$NAME|(?:\s*\n[ \t]*-[ \t]*$NAME[ \t]*)+))""")
    private val FILE = Regex("""\$\{file\(["']?([^)"'\n]+)["']?\)(?::([\w.-]+))?""")
    private val FILE_ITEM = Regex("""(?m)^[ \t]*-[ \t]*["']?\$\{file\(["']?([^)"'\n]+)["']?\)(?::([\w.-]+))?}""")
    private val VARIABLE = Regex("""\$\{[^}]*}""")
    private val FIND_IN_MAP = Regex("""(?:!FindInMap[ \t]+\[[ \t]*|Fn::FindInMap:$OPEN)["']?($NAME)""")
    private val GET_ATT = Regex("""(?:!GetAtt[ \t]+|Fn::GetAtt:$OPEN)["']?($NAME)""")
    private val PARAM = Regex("""\$\{param:([\w-]+)""")
    private val REF = Regex("""(?:!Ref[ \t]+|\bRef:[ \t]*)["']?($NAME)(?!::|\w)""")
    private val SELF = Regex("""\$\{self:([\w.-]+)""")
    private val SUB = Regex("""(?m)^([ \t]*).*(?:!Sub\b|Fn::Sub:)(.*)$""")
    private val SUB_NAME = Regex("""\$\{($NAME)(?:\.[A-Za-z0-9.]+)?}""")
    private val SEGMENT = Regex("""[\w-]+""")
    private val WORD = Regex(NAME)
    private val FILE_VALUE = Regex("""["']?[ \t]*:[ \t]*["']?\$\{file\(["']?([^)"'\n]+)["']?\)(?::([\w.-]+))?}""")
    private val SERVICE_FILES = listOf("serverless.yml", "serverless.yaml")
    private val DECLARED = Regex("""key:(?:/resources(?:/\d+)?)?/(Resources|Parameters|Conditions|Mappings)/([^/]+)""")
    private val DECLARATIONS = Key.create<CachedValue<Map<String, List<Decl>>>>("lite.sls.declarations")
    private val GENERATED = mapOf("LambdaFunction" to "functions", "LambdaLayer" to "layers", "LogGroup" to "functions")
    private val GENERATED_FROM = Regex("""key:(?:/(functions|layers))?/([^/]+)""")
    private val INCLUDES = Key.create<CachedValue<List<PsiFile>>>("lite.sls.includes")
    private val SERVICE = Key.create<CachedValue<PsiFile>>("lite.sls.service")
    private val PSEUDO = setOf("AWS")

    /** References and file paths of a Serverless or CloudFormation file; null when the text holds none of their forms. */
    fun links(text: CharSequence): Links? {
        if (listOf("\${self:", "\${file(", "\${param:", "!Ref", "!GetAtt", "!Sub", "Fn::", "Ref:", "DependsOn:").none { text.contains(it) }) return null
        val files = FILE.findAll(text).toList()
        return Links(
            refs = (self(text) + files.flatMap(::fileKeys) + named(text, PARAM, "sls:param:") + cloudFormation(text)).distinctBy { it.range },
            // a path with a variable names no single file
            paths = files.mapNotNull { it.groups[1] }.filter { "\${" !in it.value }.map { PathLink(range(it), it.value) },
        )
    }

    private fun range(group: MatchGroup, shift: Int = 0) = TextRange(group.range.first + shift, group.range.last + 1 + shift)

    private fun named(text: CharSequence, regex: Regex, prefix: String): List<Ref> =
        regex.findAll(text).flatMap { it.groups.drop(1).filterNotNull() }.filter { it.value !in PSEUDO }.map { Ref(range(it), listOf(prefix + it.value)) }.toList()

    /** One reference per segment, to the key of that level; the index of a list (`functions.0`) names no key. */
    private fun segments(path: MatchGroup, prefix: String): List<Ref> {
        val parts = SEGMENT.findAll(path.value).toList()
        return parts.indices.filter { parts[it].value.toIntOrNull() == null }.map { index ->
            val pointer = parts.take(index + 1).joinToString("") { "/" + Pointers.escape(it.value) }
            Ref(TextRange.from(path.range.first + parts[index].range.first, parts[index].value.length), listOf(prefix + pointer))
        }
    }

    private fun self(text: CharSequence): List<Ref> = SELF.findAll(text).mapNotNull { it.groups[1] }.flatMap { segments(it, "sls:self:") }.toList()

    private fun fileKeys(match: MatchResult): List<Ref> = match.groups[2]?.let { segments(it, "sls:file:${match.groupValues[1]}#") }.orEmpty()

    private fun cloudFormation(text: CharSequence): List<Ref> =
        named(text, REF, "cfn:value:") + named(text, GET_ATT, "cfn:Resources:") + named(text, CONDITION, "cfn:Conditions:") +
            named(text, FIND_IN_MAP, "cfn:Mappings:") + substituted(text) +
            DEPENDS.findAll(text).mapNotNull { it.groups[1] }.flatMap { list -> WORD.findAll(list.value).map { Ref(TextRange.from(list.range.first + it.range.first, it.value.length), listOf("cfn:Resources:${it.value}")) } }

    /** `${Name}` and `${Name.Attr}` in the text given to a `!Sub`: the rest of its line, and the lines indented under it. */
    private fun substituted(text: CharSequence): List<Ref> = SUB.findAll(text).flatMap { sub ->
        val indent = sub.groupValues[1].length
        val end = generateSequence(sub.range.last + 1) { lineEnd(it + 1, text) }.takeWhile { it < text.length }
            .firstOrNull { eol -> text.subSequence(eol + 1, lineEnd(eol + 1, text)).let { it.isNotBlank() && it.takeWhile { c -> c == ' ' }.length <= indent } } ?: text.length
        val start = sub.groups[2]?.range?.first ?: return@flatMap emptySequence()
        SUB_NAME.findAll(text.subSequence(start, end)).mapNotNull { it.groups[1] }.filter { it.value !in PSEUDO }.map { Ref(range(it, start), listOf("cfn:value:${it.value}")) }
    }.toList()

    // ---------------------------------------------------------------- resolution

    fun resolve(file: PsiFile, ref: Ref, linker: Linker): List<PsiElement>? {
        val key = ref.keys.singleOrNull() ?: return null
        val service = serviceOf(file)
        return when {
            key.startsWith("cfn:") -> key.removePrefix("cfn:").split(':').let { (kind, name) -> declared(project(service, file, linker), kind, name) }
            key.startsWith("sls:file:") -> key.removePrefix("sls:file:").let { value ->
                val path = value.substringAfter('#').split('/').drop(1).map(Pointers::unescape)
                included(file, value.substringBefore('#')).flatMap { follow(it, path, depth = 0) }.distinct()
            }
            key.startsWith("sls:param:") -> params(service, key.removePrefix("sls:param:"))
            key.startsWith("sls:self:") -> follow(service, key.removePrefix("sls:self:").split('/').drop(1).map(Pointers::unescape), depth = 0).distinct()
            else -> null
        }
    }

    /** A `${file(…)}` path starts at the folder of the file, else at the folder of the service. */
    fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
        val folders = listOfNotNull(file.originalFile.virtualFile?.parent, serviceOf(file).originalFile.virtualFile?.parent).distinct()
        val target = folders.firstNotNullOfOrNull { it.findFileByRelativePath(path) } ?: return null
        return PsiManager.getInstance(file.project).findItem(target)
    }

    /** The files a `${file(…)}` path names: one, or every file its variable part may stand for (`env.${self:custom.stage}.yml`). */
    private fun included(file: PsiFile, path: String): List<PsiFile> {
        if ("\${" !in path) return listOfNotNull(resolvePath(file, path) as? PsiFile)
        val folder = path.substringBeforeLast('/', "").takeIf { "\${" !in it } ?: return emptyList()
        val name = Regex(VARIABLE.split(path.substringAfterLast('/')).joinToString(".*") { Regex.escape(it) })
        val directory = resolvePath(file, folder.ifEmpty { "." })?.virtualFile?.takeIf { it.isDirectory } ?: return emptyList()
        val manager = PsiManager.getInstance(file.project)
        return directory.children.filter { name.matches(it.name) }.sortedBy { it.name }.mapNotNull(manager::findFile)
    }

    /**
     * The `serverless.yml` of [file]: itself, or the nearest one above it or in a folder beside one of its parents
     * (`middleware/serverless.yml` including `deploy/serverless/…`). An included file reads `self:` there.
     */
    private fun serviceOf(file: PsiFile): PsiFile = CachedValuesManager.getCachedValue(file, SERVICE) {
        CachedValueProvider.Result.create(findService(file), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }

    private fun findService(file: PsiFile): PsiFile {
        val own = file.originalFile.virtualFile ?: return file
        if (own.name in SERVICE_FILES) return file
        val found = generateSequence(own.parent) { it.parent }.take(6).firstNotNullOfOrNull { dir ->
            (listOf(dir) + dir.children.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" })
                .firstNotNullOfOrNull { folder -> SERVICE_FILES.firstNotNullOfOrNull(folder::findChild) }
        } ?: return file
        return PsiManager.getInstance(file.project).findFile(found) ?: file
    }

    /** The file, its service and the files the service includes: a resource may be declared in any of them. */
    private fun project(service: PsiFile, file: PsiFile, linker: Linker): List<PsiFile> {
        val includes = CachedValuesManager.getCachedValue(service, INCLUDES) {
            val files = Fmt.links(service).paths.mapNotNull { linker.resolvePath(service, it.path) as? PsiFile }
            CachedValueProvider.Result.create(files, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
        return (listOf(file, service) + includes).distinct()
    }

    private fun element(file: PsiFile, decl: Decl): PsiElement? =
        file.findElementAt(decl.range.startOffset)?.let { PsiTreeUtil.getParentOfType(it, FmtWord::class.java, false) ?: it }

    /**
     * What a file declares for CloudFormation, by `Kind/Name`: `Resources` and the like sit at the top of a template,
     * under `resources:` of a service, or in an item of its list. Also the resources Serverless makes itself, by
     * their name: `HelloLambdaFunction` and `HelloLogGroup` of the function `hello` (under `functions:` in the service,
     * at the top of a file it includes), `XLambdaLayer` of the layer `x`.
     */
    private fun declarations(file: PsiFile): Map<String, List<Decl>> = CachedValuesManager.getCachedValue(file, DECLARATIONS) {
        val decls = Fmt.links(file).decls
        val written = decls.mapNotNull { decl -> DECLARED.matchEntire(decl.key)?.let { "${it.groupValues[1]}/${Pointers.unescape(it.groupValues[2])}" to decl } }
        val generated = decls.flatMap { decl ->
            val match = GENERATED_FROM.matchEntire(decl.key) ?: return@flatMap emptyList()
            val id = Pointers.unescape(match.groupValues[2]).replace("-", "Dash").replace("_", "Underscore").replaceFirstChar(Char::uppercase)
            GENERATED.filter { (_, section) -> match.groupValues[1].isEmpty() || match.groupValues[1] == section }.map { (suffix, _) -> "generated/$id$suffix" to decl }
        }
        CachedValueProvider.Result.create((written + generated).groupBy({ it.first }, { it.second }), PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun declared(files: List<PsiFile>, kind: String, name: String): List<PsiElement> {
        val kinds = listOf("Resources", "Parameters").takeIf { kind == "value" } ?: listOf(kind)
        fun find(key: String) = files.flatMap { file -> declarations(file)[key].orEmpty().mapNotNull { element(file, it) } }
        return kinds.flatMap { find("$it/$name") }.ifEmpty { find("generated/$name") }.distinct()
    }

    private fun params(service: PsiFile, name: String): List<PsiElement> {
        val pointer = Regex("""key:(?:/params/[^/]+|/stages/[^/]+/params)/${Regex.escape(Pointers.escape(name))}""")
        return Fmt.links(service).decls.filter { pointer.matches(it.key) }.mapNotNull { element(service, it) }
    }

    /**
     * The key at [path] in [file]. Where a key on the way holds a `${file(…)}`, or a list of them indexed by the next
     * segment (`functions.0.hello`), the rest of the path is read in that file, or in each file its name may stand for.
     */
    private fun follow(file: PsiFile, path: List<String>, depth: Int): List<PsiElement> {
        fun pointer(parts: List<String>) = "key:" + parts.joinToString("") { "/" + Pointers.escape(it) }
        val links = Fmt.links(file)
        links.declared(pointer(path)).firstOrNull()?.let { return listOfNotNull(element(file, it)) }
        if (depth > 4) return emptyList()
        val known = (path.size - 1 downTo 1).firstOrNull { links.declared(pointer(path.take(it))).isNotEmpty() } ?: return emptyList()
        val holder = links.declared(pointer(path.take(known))).first()
        val text = file.viewProvider.contents
        val direct = FILE_VALUE.matchAt(text, holder.range.endOffset)
        val item = path[known].toIntOrNull()?.takeIf { direct == null }?.let { index -> items(text, holder.range.endOffset).getOrNull(index) }
        val include = direct ?: item ?: return emptyList()
        val inside = include.groupValues[2].split('.').filter { it.isNotEmpty() }
        val rest = path.drop(known + (1.takeIf { item != null } ?: 0))
        if (inside.isEmpty() && rest.isEmpty()) return emptyList()
        return included(file, include.groupValues[1]).flatMap { follow(it, inside + rest, depth + 1) }
    }

    /** The `- ${file(…)}` items listed under the key that ends at [keyEnd]. */
    private fun items(text: CharSequence, keyEnd: Int): List<MatchResult> {
        val start = lineEnd(keyEnd, text) + 1
        val lines = generateSequence(start) { lineEnd(it, text) + 1 }.takeWhile { it < text.length }
            .takeWhile { text.subSequence(it, lineEnd(it, text)).let { line -> line.isBlank() || line.trimStart().startsWith("-") || line.trimStart().startsWith("#") } }
        return lines.mapNotNull { FILE_ITEM.matchAt(text, it) }.toList()
    }
}
