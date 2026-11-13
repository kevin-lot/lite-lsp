package me.kevinlot.lite.kotlin.resolve

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope

/** Bridge to Java PSI: JDK, project Java sources and library jars. */
object KtJava {
    /** Kotlin built-in types that the JVM represents with a Java class. */
    private val BUILTINS = mapOf(
        "Int" to "java.lang.Integer", "Long" to "java.lang.Long", "Short" to "java.lang.Short", "Byte" to "java.lang.Byte",
        "Double" to "java.lang.Double", "Float" to "java.lang.Float", "Boolean" to "java.lang.Boolean", "Char" to "java.lang.Character",
        "Any" to "java.lang.Object", "String" to "java.lang.String", "CharSequence" to "java.lang.CharSequence",
        "Throwable" to "java.lang.Throwable", "Exception" to "java.lang.Exception", "Comparable" to "java.lang.Comparable",
        "Number" to "java.lang.Number", "Enum" to "java.lang.Enum", "Annotation" to "java.lang.annotation.Annotation",
        "Iterable" to "java.lang.Iterable", "MutableIterable" to "java.lang.Iterable",
        "Collection" to "java.util.Collection", "MutableCollection" to "java.util.Collection",
        "List" to "java.util.List", "MutableList" to "java.util.List", "ArrayList" to "java.util.ArrayList",
        "Set" to "java.util.Set", "MutableSet" to "java.util.Set", "HashSet" to "java.util.HashSet", "LinkedHashSet" to "java.util.LinkedHashSet",
        "Map" to "java.util.Map", "MutableMap" to "java.util.Map", "HashMap" to "java.util.HashMap", "LinkedHashMap" to "java.util.LinkedHashMap",
        "Iterator" to "java.util.Iterator", "MutableIterator" to "java.util.Iterator",
        "IllegalArgumentException" to "java.lang.IllegalArgumentException", "IllegalStateException" to "java.lang.IllegalStateException",
        "RuntimeException" to "java.lang.RuntimeException", "IndexOutOfBoundsException" to "java.lang.IndexOutOfBoundsException",
        "NullPointerException" to "java.lang.NullPointerException", "UnsupportedOperationException" to "java.lang.UnsupportedOperationException",
        "NumberFormatException" to "java.lang.NumberFormatException", "StringBuilder" to "java.lang.StringBuilder",
    )

    /** Packages imported by default in every Kotlin/JVM file. */
    val DEFAULT_PACKAGES = listOf(
        "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io", "kotlin.ranges",
        "kotlin.sequences", "kotlin.text", "kotlin.jvm", "java.lang",
    )

    /** Same boundaries as Kotlin lookups: the file's Gradle project + libraries (JDK, jars). */
    fun scope(e: PsiElement): GlobalSearchScope = KtProjectScope.of(e)

    fun findClass(e: PsiElement, fqName: String): PsiClass? = KtClasspath.of(e.project).findClass(fqName, scope(e))

    fun findPackage(e: PsiElement, fqName: String): PsiElement? =
        JavaPsiFacade.getInstance(e.project).findPackage(fqName)
            ?: KtClasspath.of(e.project).jarDirectories(fqName).firstOrNull()?.let { com.intellij.psi.PsiManager.getInstance(e.project).findDirectory(it) }

    /** `String`, `List`… resolved to their Java class (no Kotlin stdlib sources needed). */
    fun builtin(e: PsiElement, name: String): PsiClass? = BUILTINS[name]?.let { findClass(e, it) }

    /** Static functions named [name] compiled from Kotlin top-level declarations of [packages]. */
    fun facadeFunctions(e: PsiElement, packages: Collection<String>, name: String): List<PsiMember> {
        val classpath = KtClasspath.of(e.project)
        val scope = scope(e)
        return packages.flatMap { classpath.facadeFunctions(it, scope)[name].orEmpty() }
    }

    /**
     * Members of a Java class as seen from Kotlin: methods for calls; fields, getters
     * (`name` -> `getName()`/`isName()`) and no-arg methods (`length`, `size`) for property access.
     */
    fun members(cls: PsiClass, name: String, call: Boolean): List<PsiElement> =
        javaMembers(cls, name, call).ifEmpty { decompiledMembers(cls, name) }

    /**
     * Members the JVM view cannot show (inline-only or mangled names of value classes such as
     * `Result.getOrNull`): found by name in IntelliJ's decompiled Kotlin view of the same class file.
     */
    private fun decompiledMembers(cls: PsiClass, name: String): List<PsiElement> {
        val vf = cls.containingFile?.virtualFile ?: return emptyList()
        if (vf.extension != "class") return emptyList()
        val decompiled = com.intellij.psi.PsiManager.getInstance(cls.project).findFile(vf) ?: return emptyList()
        if (decompiled is com.intellij.psi.PsiJavaFile) return emptyList()
        val out = mutableListOf<PsiElement>()
        com.intellij.psi.util.PsiTreeUtil.processElements(decompiled) { e ->
            if (e !is com.intellij.psi.PsiFile && e is com.intellij.psi.PsiNamedElement && e.name == name) out += e
            out.size < 10
        }
        return out
    }

    private fun javaMembers(cls: PsiClass, name: String, call: Boolean): List<PsiMember> {
        if (call) return methodsNamed(cls, name).toList()
        val cap = name.replaceFirstChar { it.uppercase() }
        val out = mutableListOf<PsiMember>()
        cls.findFieldByName(name, true)?.let { out += it }
        for (getter in listOf("get$cap", "is$cap", name)) {
            out += methodsNamed(cls, getter).filter { it.parameterList.parametersCount <= (if (it.name.contains('-')) 1 else 0) }
        }
        cls.findInnerClassByName(name, true)?.let { out += it }
        return out
    }

    /**
     * Methods by Kotlin name: also the compiler-mangled forms of inline/value classes
     * (`getOrNull-impl`, `copy-7Yh9...`).
     */
    private fun methodsNamed(cls: PsiClass, name: String): List<com.intellij.psi.PsiMethod> =
        cls.findMethodsByName(name, true).toList() + cls.allMethods.filter { it.name.startsWith("$name-") }

    /** Static members and nested classes: `Collections.emptyList()`, `Map.Entry`. */
    fun staticMembers(cls: PsiClass, name: String): List<PsiMember> =
        (cls.findMethodsByName(name, true).filter { it.hasModifierProperty(PsiModifier.STATIC) } +
            listOfNotNull(cls.findFieldByName(name, true)?.takeIf { it.hasModifierProperty(PsiModifier.STATIC) }) +
            listOfNotNull(cls.findInnerClassByName(name, true)) +
            // Kotlin companion objects compile to a nested `Companion` class: `Result.success(…)`
            cls.findInnerClassByName("Companion", false)?.let { javaMembers(it, name, call = true) + javaMembers(it, name, call = false) }.orEmpty())
            .distinct()

    /** Qualified names of [cls] and all its supertypes (for extension receiver matching). */
    fun supertypeNames(cls: PsiClass): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(c: PsiClass) {
            val q = c.qualifiedName ?: return
            if (!out.add(q)) return
            c.supers.forEach { walk(it) }
        }
        walk(cls)
        return out
    }
}
