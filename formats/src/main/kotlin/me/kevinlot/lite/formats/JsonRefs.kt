package me.kevinlot.lite.formats

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/*
 * `$ref` in JSON files (the JSON plugin's PSI; loaded with it, see lite-json.xml): OpenAPI v1/v2/v3, AsyncAPI,
 * JSON Schema. Swagger 1.2 also names its models in `"type"` / `"responseClass"` (`"type": "Pet"`).
 */

/** JSON pointer → property (or array item) of a JSON file. */
class JsonPointerTargets : PointerTargets {
    override fun find(file: PsiFile, pointer: String): PsiElement? {
        var value: JsonValue? = (file as? JsonFile)?.topLevelValue ?: return null
        var found: PsiElement? = null
        for (raw in pointer.removePrefix("/").split('/')) {
            val segment = Pointers.unescape(raw)
            when (val v = value) {
                is JsonObject -> { val p = v.findProperty(segment) ?: return null; found = p; value = p.value }
                is JsonArray -> { val item = segment.toIntOrNull()?.let { v.valueList.getOrNull(it) } ?: return null; found = item; value = item }
                else -> return null
            }
        }
        return found
    }
}

class JsonRefContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as JsonStringLiteral
                val property = literal.parent as? JsonProperty ?: return PsiReference.EMPTY_ARRAY
                if (property.value !== literal || literal.textLength < 3) return PsiReference.EMPTY_ARRAY
                val value = literal.value
                val name = property.name
                val isRef = name == "\$ref" || name == "operationRef" ||
                    ("#/" in value && (property.parent?.parent as? JsonProperty)?.name == "mapping")
                val swagger1Model = !isRef && (name == "type" || name == "responseClass") && value !in SWAGGER1_PRIMITIVES && isSwagger1(literal.containingFile)
                if (!isRef && !swagger1Model) return PsiReference.EMPTY_ARRAY
                return arrayOf(JsonPointerReference(literal, value))
            }
        })
    }

    private companion object {
        val SWAGGER1_PRIMITIVES = setOf("integer", "number", "string", "boolean", "array", "object", "void", "File", "file", "date", "date-time", "int", "long", "float", "double", "byte")
        fun isSwagger1(file: PsiFile) = (file as? JsonFile)?.topLevelValue.let { it is JsonObject && it.findProperty("swaggerVersion") != null }
    }
}

class JsonPointerReference(literal: JsonStringLiteral, private val value: String) :
    PsiPolyVariantReferenceBase<JsonStringLiteral>(literal, TextRange(1, literal.textLength - 1), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(Pointers.resolve(element.containingFile, value, Yaml.linker))

    /** The last pointer segment (or the file name) takes the new name. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val name = Pointers.nameRange(value)
        val segment = if ('#' in value && name.first > value.indexOf('#')) Pointers.escape(newElementName) else newElementName
        return ElementManipulators.handleContentChange(element, rangeInElement, value.replaceRange(name, segment)) ?: element
    }
}
