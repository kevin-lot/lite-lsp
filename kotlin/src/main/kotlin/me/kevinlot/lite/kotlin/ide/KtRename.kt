package me.kevinlot.lite.kotlin.ide

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.kotlin.upstream.lexer.KotlinLexer
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.parser.KtParserDefinition
import me.kevinlot.lite.kotlin.psi.*

class KtFindUsagesProvider : LiteFindUsagesProvider(
    ::KotlinLexer, TokenSet.create(KtTokens.IDENTIFIER), KtParserDefinition.COMMENTS, KtTokens.STRINGS,
) {
    override fun isDeclaration(element: PsiElement): Boolean = element is KtNamedDeclaration

    override fun kindOf(element: PsiElement): String = when (element) {
        is KtClass -> if (element.isInterface) "interface" else "class"
        is KtObjectDeclaration -> "object"
        is KtFunction -> "function"
        is KtProperty -> "property"
        is KtParameter -> "parameter"
        is KtTypeParameter -> "type parameter"
        is KtTypeAlias -> "type alias"
        is KtEnumEntry -> "enum entry"
        else -> "declaration"
    }
}

class KtRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        (element is KtProperty && element.isLocal) || element is KtDestructuringEntry ||
            (element is KtParameter && element.parent?.parent !is KtClass)

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = element is KtNamedDeclaration
}

/** Backticks make any name valid: `` `fun` ``. */
class KtNamesValidator : LiteNamesValidator(KtNames.KEYWORDS) {
    override fun unescape(name: String): String = name.removeSurrounding("`")
    override fun isEscaped(name: String): Boolean = name.length > 2 && name.startsWith("`") && name.endsWith("`")
}
