package me.kevinlot.lite.typescript.resolve

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.psi.TsElement
import me.kevinlot.lite.typescript.psi.TsModuleSpecifier
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsReferenceExpr
import me.kevinlot.lite.typescript.psi.TsSpecifier
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

/**
 * Module specifiers → files (Node/TypeScript resolution, bounded by the project root) and module exports
 * (ES `export`, re-exports, `export =`, CommonJS `module.exports`/`exports.x`).
 */
object TsModules {
    private val GUARD = RecursionGuard(32)
    private val TARGETS = Key.create<CachedValue<List<PsiElement>>>("lite.ts.module")
    private val IS_MODULE = Key.create<CachedValue<Boolean>>("lite.ts.isModule")

    private val SOURCE_EXTENSIONS = listOf(".ts", ".tsx", ".d.ts", ".mts", ".d.mts", ".cts", ".d.cts", ".js", ".jsx", ".mjs", ".cjs")
    /** ESM TypeScript writes `./a.js` for `./a.ts`. */
    private val JS_TO_TS = mapOf(".js" to listOf(".ts", ".tsx", ".d.ts"), ".mjs" to listOf(".mts", ".d.mts"), ".cjs" to listOf(".cts", ".d.cts"), ".jsx" to listOf(".tsx"))
    private val CONDITIONS = listOf("types", "typings", "import", "module", "node", "require", "default")

    /** Files (or `declare module "x"` blocks) a specifier designates. */
    fun targets(spec: TsModuleSpecifier): List<PsiElement> = LiteCache.cached(spec, TARGETS, TsLibraryOracle) { resolve(spec.containingFile, spec.path) }

    fun resolve(from: PsiFile, spec: String): List<PsiElement> {
        val vf = from.originalFile.virtualFile ?: return emptyList()
        val dir = vf.parent ?: return emptyList()
        val manager = PsiManager.getInstance(from.project)
        val file = when {
            spec.startsWith("./") || spec.startsWith("../") || spec == "." || spec == ".." -> resolvePath(dir, spec)
            spec.startsWith("/") -> vf.fileSystem.findFileByPath(spec)?.let { if (it.isDirectory) directory(it) else it } ?: resolvePath(dir, spec)
            else -> configured(from, vf, spec) ?: bare(from, dir, spec)
        }
        file?.let { f -> manager.findFile(f)?.let { return listOf(it) } }
        // `declare module "fs" { … }` (@types/node), `declare module "*.css"`
        return TsGlobals.ambientModules(from, spec)
    }

    private fun resolvePath(base: VirtualFile, rel: String): VirtualFile? {
        // `./a.js` is `./a.ts` when it exists (TypeScript sources and typings first)
        for ((js, tsList) in JS_TO_TS) if (rel.endsWith(js)) for (ts in tsList) base.findFileByRelativePath(rel.removeSuffix(js) + ts)?.let { return it }
        val exact = base.findFileByRelativePath(rel)
        if (exact != null && !exact.isDirectory && isSource(exact)) return exact
        for (ext in SOURCE_EXTENSIONS) base.findFileByRelativePath(rel + ext)?.takeIf { !it.isDirectory }?.let { return it }
        return exact?.takeIf { it.isDirectory }?.let { directory(it) }
    }

    private fun isDeclaration(f: VirtualFile) = f.name.endsWith(".d.ts") || f.name.endsWith(".d.mts") || f.name.endsWith(".d.cts")

    private fun isSource(f: VirtualFile) = SOURCE_EXTENSIONS.any { f.name.endsWith(it) }

    private fun directory(dir: VirtualFile): VirtualFile? {
        dir.findChild("package.json")?.let { TsJsonFiles.read(it) }?.let { json -> packageEntry(dir, json, "")?.let { return it } }
        for (ext in SOURCE_EXTENSIONS) dir.findChild("index$ext")?.let { return it }
        return null
    }

    /** `compilerOptions.paths` / `baseUrl` of the nearest `tsconfig.json`/`jsconfig.json` (project root at most). */
    private fun configured(from: PsiFile, vf: VirtualFile, spec: String): VirtualFile? {
        val config = TsConfigs.of(from.project, vf) ?: return null
        for ((pattern, targets) in config.paths) {
            val star = pattern.indexOf('*')
            val captured = when {
                star < 0 -> if (spec == pattern) "" else continue
                spec.startsWith(pattern.substring(0, star)) && spec.endsWith(pattern.substring(star + 1)) && spec.length >= pattern.length - 1 ->
                    spec.substring(star, spec.length - (pattern.length - star - 1))
                else -> continue
            }
            for (t in targets) resolvePath(config.pathsBase, t.replace("*", captured))?.let { return it }
        }
        return config.baseUrl?.let { resolvePath(it, spec) }
    }

    /** `pkg`, `pkg/sub`, `@scope/pkg/sub`: `node_modules` from the file's folder up to its project root, then `@types`. */
    private fun bare(from: PsiFile, dir: VirtualFile, spec: String): VirtualFile? {
        val parts = spec.removePrefix("node:").split('/')
        val name = if (spec.startsWith("@") && parts.size > 1) parts[0] + "/" + parts[1] else parts[0]
        val sub = spec.removePrefix("node:").removePrefix(name).removePrefix("/")
        val root = TsEnvironments.getInstance(from.project).projectRoot(from.originalFile.virtualFile ?: return null)
        val typesName = "@types/" + if (name.startsWith("@")) name.substring(1).replace("/", "__") else name
        var d: VirtualFile? = dir
        while (d != null) {
            // typings first: the package's own, else its @types package, else its JavaScript
            val js = d.findFileByRelativePath("node_modules/$name")?.takeIf { it.isDirectory }?.let { packageFile(it, sub) }
            if (js != null && isDeclaration(js)) return js
            d.findFileByRelativePath("node_modules/$typesName")?.takeIf { it.isDirectory }?.let { pkg -> packageFile(pkg, sub)?.let { return it } }
            if (js != null) return js
            if (root == null || d.path == root) break
            d = d.parent
        }
        return null
    }

    private fun packageFile(pkg: VirtualFile, sub: String): VirtualFile? {
        val json = pkg.findChild("package.json")?.let { TsJsonFiles.read(it) }
        if (json != null) packageEntry(pkg, json, sub)?.let { return it }
        return if (sub.isEmpty()) directory(pkg) else resolvePath(pkg, sub)
    }

    /** `exports` (conditions: types first), then `types`/`typings`, then `module`/`main`. */
    private fun packageEntry(pkg: VirtualFile, json: JsonObject, sub: String): VirtualFile? {
        json.get("exports")?.let { exports -> exportTarget(exports, if (sub.isEmpty()) "." else "./$sub")?.let { t -> resolvePath(pkg, t)?.let { return it } } }
        if (sub.isNotEmpty()) return resolvePath(pkg, sub)
        for (key in listOf("types", "typings", "module", "main")) TsJsonFiles.string(json, key)?.let { t -> resolvePath(pkg, t.removePrefix("./").let { "./$it" })?.let { return it } }
        return null
    }

    private fun exportTarget(exports: JsonElement, sub: String): String? {
        if (exports.isJsonPrimitive) return exports.asString.takeIf { sub == "." }
        if (!exports.isJsonObject) return null
        val obj = exports.asJsonObject
        if (obj.keySet().any { it.startsWith(".") }) {
            obj.get(sub)?.let { return condition(it) }
            for ((key, value) in obj.entrySet()) {
                val star = key.indexOf('*')
                if (star < 0) continue
                val prefix = key.substring(0, star); val suffix = key.substring(star + 1)
                if (sub.startsWith(prefix) && sub.endsWith(suffix)) return condition(value)?.replace("*", sub.substring(prefix.length, sub.length - suffix.length))
            }
            return null
        }
        return if (sub == ".") condition(obj) else null
    }

    private fun condition(e: JsonElement): String? = when {
        e.isJsonPrimitive -> e.asString
        e.isJsonArray -> e.asJsonArray.firstNotNullOfOrNull { condition(it) }
        e.isJsonObject -> e.asJsonObject.let { o -> CONDITIONS.firstNotNullOfOrNull { c -> o.get(c)?.let { condition(it) } } ?: o.entrySet().firstNotNullOfOrNull { condition(it.value) } }
        else -> null
    }

    // ---------------------------------------------------------------- exports

    /** A file with top-level `import`/`export` is a module; otherwise its declarations are global. */
    fun isModule(file: PsiFile): Boolean = LiteCache.cached(file, IS_MODULE, TsLibraryOracle) {
        file.children.any { c ->
            val t = c.node.elementType
            t == E.IMPORT_DECLARATION || t == E.IMPORT_EQUALS || t == E.EXPORT_DECLARATION || t == E.EXPORT_STAR || t == E.EXPORT_ASSIGNMENT ||
                (c as? TsElement)?.hasModifier("export") == true
        }
    }

    /** The statements of a module: a file, an ambient module or a namespace (their block). */
    fun body(module: PsiElement): PsiElement? = if (module is PsiFile) module else (module as? TsElement)?.child(E.MODULE_BLOCK)

    /** Declarations of [module] exported as [name] (`"default"` for the default export). */
    fun exports(module: PsiElement, name: String): List<PsiElement> = GUARD.run(emptyList()) {
        val body = body(module) ?: return@run emptyList()
        val implicit = module !is PsiFile && (module.containingFile as? TsFile)?.isDeclarationFile == true || isAmbient(module)
        val out = mutableListOf<PsiElement>()
        for (s in body.children) {
            val st = s as? TsElement ?: continue
            when (st.type) {
                E.EXPORT_DECLARATION -> exportSpecifiers(st, name, module, out)
                E.EXPORT_STAR -> exportStar(st, name, out)
                E.EXPORT_ASSIGNMENT -> exportAssignment(st, name, out)
                E.EXPRESSION_STATEMENT -> commonJs(st, name, out)
                else -> if (st.hasModifier("export") || implicit) {
                    val wantDefault = name == "default"
                    for (d in TsScopes.declaredBy(st)) {
                        if (wantDefault && st.hasModifier("default") || !wantDefault && !st.hasModifier("default") && (d as? TsNamedDecl)?.name == name) out += d
                    }
                }
            }
        }
        // a plain script without CommonJS exports (e.g. a QML JavaScript resource): its top-level declarations
        if (out.isEmpty() && module is PsiFile && !isModule(module)) out += TsScopes.declarations(module)[name].orEmpty()
        out
    }

    private fun isAmbient(module: PsiElement) =
        module.node.elementType == E.AMBIENT_MODULE || generateSequence(module) { it.parent }.any { (it as? TsElement)?.hasModifier("declare") == true }

    private fun exportSpecifiers(st: TsElement, name: String, module: PsiElement, out: MutableList<PsiElement>) {
        val from = st.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier
        for (spec in st.children.filterIsInstance<TsSpecifier>()) {
            if (spec.name != name) continue
            val source = spec.sourceName ?: continue
            out += if (from != null) targets(from).flatMap { exports(it, source) }
            else spec.ref?.let { TsResolver.resolve(it) }.orEmpty()
        }
    }

    private fun exportStar(st: TsElement, name: String, out: MutableList<PsiElement>) {
        val from = st.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier ?: return
        val alias = (st as? TsNamedDecl)?.name
        when {
            alias != null -> if (alias == name) out += targets(from)
            name != "default" -> out += targets(from).flatMap { exports(it, name) }
        }
    }

    /** `export default x` / `export = x`. */
    private fun exportAssignment(st: TsElement, name: String, out: MutableList<PsiElement>) {
        val expr = st.children.lastOrNull { it is TsElement } ?: return
        val isDefault = st.hasToken(T.kw("default"))
        if (name == "default") out += valueTargets(expr)
        else if (!isDefault) out += TsTypes.members(TsTypes.exprTy(expr), name).map { it.first }
    }

    /** `module.exports = …`, `module.exports.x = …`, `exports.x = …`. */
    private fun commonJs(st: TsElement, name: String, out: MutableList<PsiElement>) {
        val assign = st.firstChild?.takeIf { it.node.elementType == E.ASSIGNMENT_EXPRESSION } ?: return
        val left = assign.firstChild as? TsReferenceExpr ?: return
        val right = assign.children.lastOrNull { it is TsElement && it != left } ?: return
        val path = left.text.replace(" ", "")
        when {
            path == "module.exports" -> if (name == "default") out += valueTargets(right) else out += TsTypes.members(TsTypes.exprTy(right), name).map { TsTypes.declarationOf(it.first) }
            path == "module.exports.$name" || path == "exports.$name" -> out += left
        }
    }

    private fun valueTargets(expr: PsiElement): List<PsiElement> =
        (expr as? TsReferenceExpr)?.takeIf { it.qualifier == null }?.let { TsResolver.resolve(it) }?.takeIf { it.isNotEmpty() } ?: listOf(expr)
}

/** Nearest `tsconfig.json`/`jsconfig.json`: `baseUrl` and `paths` (with one level of `extends`). */
data class TsConfig(val baseUrl: VirtualFile?, val pathsBase: VirtualFile, val paths: List<Pair<String, List<String>>>)

object TsConfigs {
    fun of(project: com.intellij.openapi.project.Project, file: VirtualFile): TsConfig? {
        val root = TsEnvironments.getInstance(project).projectRoot(file)
        var dir = file.parent
        while (dir != null) {
            (dir.findChild("tsconfig.json") ?: dir.findChild("jsconfig.json"))?.let { return parse(it) }
            if (root == null || dir.path == root) return null
            dir = dir.parent
        }
        return null
    }

    private fun parse(config: VirtualFile): TsConfig? {
        val json = TsJsonFiles.read(config) ?: return null
        var options = TsJsonFiles.obj(json, "compilerOptions")
        var optionsDir = config.parent
        if (options?.has("paths") != true && options?.has("baseUrl") != true) {
            TsJsonFiles.string(json, "extends")?.takeIf { it.startsWith(".") }?.let { ext ->
                val parent = config.parent.findFileByRelativePath(ext) ?: config.parent.findFileByRelativePath("$ext.json")
                parent?.let { TsJsonFiles.read(it) }?.let { TsJsonFiles.obj(it, "compilerOptions") }?.let { options = it; optionsDir = parent.parent }
            }
        }
        val o = options ?: return null
        val baseUrl = TsJsonFiles.string(o, "baseUrl")?.let { optionsDir.findFileByRelativePath(it) }
        val paths = TsJsonFiles.obj(o, "paths")?.entrySet()?.map { (k, v) ->
            k to (if (v.isJsonArray) v.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } else emptyList())
        }.orEmpty()
        if (baseUrl == null && paths.isEmpty()) return null
        return TsConfig(baseUrl, baseUrl ?: optionsDir, paths)
    }
}
