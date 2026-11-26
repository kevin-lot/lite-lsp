package me.kevinlot.lite.formats

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

private const val PARENT_DIR = "\${get_parent_terragrunt_dir()}"
private const val PATH_FROM_INCLUDE = "\${path_relative_from_include()}"
private const val REPO_ROOT = "\${get_repo_root()}"
private const val TERRAGRUNT_EXTENSION = "hcl"
private const val TERRAGRUNT_FILE = "terragrunt.hcl"

private val FIND_IN_PARENT = Regex("""find_in_parent_folders\(\s*(?:"([^"\n]+)")?""")
private val QUOTED = Regex(""""([^"\n]*)"""")
private val REPEATED_SLASH = Regex("""(?<!:)/{2,}""")
// A registry address (`hashicorp/consul/aws`) has no leading `.`, `/` or `$`; a local path always has one.
private val REMOTE_SOURCE = Regex("""^(?:git::|git@|https?://|(?![./$])[\w.-]+/[\w.-]+(?:/[\w.-]+)?$)""")

fun isRemoteHclSource(path: String): Boolean = REMOTE_SOURCE.containsMatchIn(path)

val VirtualFile.isTerragrunt: Boolean get() = extension == TERRAGRUNT_EXTENSION

// `find_in_parent_folders("root.hcl")` walks up from the parent directory; anything else is a path next to the file.
fun resolveHclPath(expression: String, fromFile: VirtualFile): VirtualFile? =
    findHclPath(expression = expression, followIncludes = true, fromFile = fromFile)

// Terragrunt's directory functions are replaced by what they return; `//` only marks the module root inside a source.
fun resolveHclLiteralPath(fromFile: VirtualFile, path: String): VirtualFile? =
    findHclLiteralPath(followIncludes = true, fromFile = fromFile, path = path)

// The path of an include is resolved without following the includes: it would ask for itself, without end.
fun includedFiles(file: VirtualFile): List<VirtualFile> {
    val parsed = file.text()?.let(::HclFile) ?: return emptyList()
    return parsed.blocks.filter { it.type == "include" }
        .mapNotNull { parsed.stringValue(block = it, name = "path") }
        .mapNotNull { findHclPath(expression = it, followIncludes = false, fromFile = file) }
}

private fun findHclPath(expression: String, followIncludes: Boolean, fromFile: VirtualFile): VirtualFile? {
    val search = FIND_IN_PARENT.find(expression)
    if (search != null) {
        val name = search.groupValues[1].ifEmpty { TERRAGRUNT_FILE }
        return generateSequence(fromFile.parent?.parent) { it.parent }.firstNotNullOfOrNull { it.findChild(name) }
    }
    val path = QUOTED.find(expression)?.groupValues?.get(1) ?: return null
    return findHclLiteralPath(followIncludes = followIncludes, fromFile = fromFile, path = path)
}

private fun findHclLiteralPath(followIncludes: Boolean, fromFile: VirtualFile, path: String): VirtualFile? {
    if (isRemoteHclSource(path)) return null
    val own = fromFile.parent ?: return null
    val root = own.takeIf { REPO_ROOT in path }?.let { generateSequence(it) { dir -> dir.parent }.firstOrNull { dir -> dir.findChild(".git") != null } }
    val readsInclude = followIncludes && (PARENT_DIR in path || PATH_FROM_INCLUDE in path)
    val parent = fromFile.takeIf { readsInclude }?.let(::includedFiles)?.firstOrNull()?.parent
    val expanded = path.substringBefore('?')
        .replace("\${get_terragrunt_dir()}", own.path)
        .replace("\${get_original_terragrunt_dir()}", own.path)
        .replace("\${path.module}", own.path)
        .replace(PARENT_DIR, (parent ?: own).path)
        .replace(REPO_ROOT, (root ?: own).path)
        .replace(PATH_FROM_INCLUDE, parent?.let { VfsUtilCore.findRelativePath(own, it, '/') } ?: ".")
    if ('$' in expanded) return null
    val normalized = expanded.replace(REPEATED_SLASH, "/")
    if (normalized.startsWith("/")) return own.fileSystem.findFileByPath(normalized)
    return own.findFileByRelativePath(normalized)
}

// The directory a `module "name"` block points to: a local path, else what `terraform init` downloaded.
fun moduleDir(block: HclBlock, file: VirtualFile, parsed: HclFile): VirtualFile? {
    val source = parsed.stringValue(block = block, name = "source")?.let { QUOTED.find(it)?.groupValues?.get(1) } ?: return null
    val local = resolveHclLiteralPath(fromFile = file, path = source)?.takeIf { it.isDirectory }
    return local ?: downloadedModuleDir(file = file, name = block.labels.lastOrNull()?.first ?: return null)
}

// The module a Terragrunt file deploys: its `terraform { source }`, or the one of a file it includes.
fun terragruntSourceDir(file: VirtualFile): VirtualFile? =
    (listOf(file) + includedFiles(file)).firstNotNullOfOrNull { candidate ->
        val parsed = candidate.text()?.let(::HclFile) ?: return@firstNotNullOfOrNull null
        parsed.blocks.filter { it.type == "terraform" }
            .mapNotNull { parsed.stringValue(block = it, name = "source") }
            .mapNotNull { QUOTED.find(it)?.groupValues?.get(1) }
            .firstNotNullOfOrNull { resolveHclLiteralPath(fromFile = file, path = it)?.takeIf { dir -> dir.isDirectory } }
    }

// The unit a `dependency "name"` block reads: the Terragrunt file under its `config_path`.
fun dependencyFile(block: HclBlock, file: VirtualFile, parsed: HclFile): VirtualFile? {
    val target = parsed.stringValue(block = block, name = "config_path")?.let { resolveHclPath(expression = it, fromFile = file) } ?: return null
    return target.takeUnless { it.isDirectory } ?: target.findChild(TERRAGRUNT_FILE)
}

private fun downloadedModuleDir(file: VirtualFile, name: String): VirtualFile? =
    generateSequence(file.parent) { it.parent }.firstNotNullOfOrNull { dir ->
        val manifest = dir.findFileByRelativePath(".terraform/modules/modules.json") ?: return@firstNotNullOfOrNull null
        runCatching { JsonParser.parseString(VfsUtilCore.loadText(manifest)).asJsonObject.getAsJsonArray("Modules") }.getOrNull()
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("Key")?.asString == name }
            ?.get("Dir")?.asString
            ?.let(dir::findFileByRelativePath)
    }

// The editor's unsaved text when the file is open, its saved content otherwise.
private fun VirtualFile.text(): CharSequence? = FileDocumentManager.getInstance().getDocument(this)?.charsSequence
