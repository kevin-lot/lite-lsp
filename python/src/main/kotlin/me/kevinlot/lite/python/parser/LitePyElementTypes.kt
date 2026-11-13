package me.kevinlot.lite.python.parser

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.python.upstream.PyElementTypesFacade
import me.kevinlot.lite.python.upstream.psi.PyElementType
import java.util.function.Function

/**
 * Element types for the upstream parser: plain types (no stubs, no upstream PSI). Our PSI is
 * created by [me.kevinlot.lite.python.psi.PyPsiFactoryImpl] from the type, so constructors are unused.
 */
class LitePyElementTypes : PyElementTypesFacade() {
    override val functionDeclaration: IElementType = PyElementType("FUNCTION_DECLARATION")
    override val classDeclaration: IElementType = PyElementType("CLASS_DECLARATION")
    override val parameterList: IElementType = PyElementType("PARAMETER_LIST")
    override val decoratorList: IElementType = PyElementType("DECORATOR_LIST")
    override val namedParameter: IElementType = PyElementType("NAMED_PARAMETER")
    override val tupleParameter: IElementType = PyElementType("TUPLE_PARAMETER")
    override val slashParameter: IElementType = PyElementType("SLASH_PARAMETER")
    override val singleStarParameter: IElementType = PyElementType("SINGLE_STAR_PARAMETER")
    override val decoratorCall: IElementType = PyElementType("DECORATOR_CALL")
    override val importElement: IElementType = PyElementType("IMPORT_ELEMENT")
    override val annotation: IElementType = PyElementType("ANNOTATION")
    override val starImportElement: IElementType = PyElementType("STAR_IMPORT_ELEMENT")
    override val exceptPart: IElementType = PyElementType("EXCEPT_PART")
    override val fromImportStatement: IElementType = PyElementType("FROM_IMPORT_STATEMENT")
    override val importStatement: IElementType = PyElementType("IMPORT_STATEMENT")
    override val targetExpression: IElementType = PyElementType("TARGET_EXPRESSION")
    override val typeParameter: IElementType = PyElementType("TYPE_PARAMETER")
    override val typeParameterList: IElementType = PyElementType("TYPE_PARAMETER_LIST")
    override val typeAliasStatement: IElementType = PyElementType("TYPE_ALIAS_STATEMENT")
    override val statementList: IElementType = PyElementType("STATEMENT_LIST")
    override val argumentListConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val printTargetConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val decoratorConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val expressionStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val assignmentStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val augAssignmentStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val assertStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val breakStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val continueStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val delStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val execStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val forStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val typeDeclarationStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val globalStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val ifStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val passStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val printStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val raiseStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val returnStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val tryExceptStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val withStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val whileStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val nonlocalStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val withItemConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val emptyExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val referenceExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val integerLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val floatLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val imaginaryLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val stringLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val noneLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val ellipsisLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val boolLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val parenthesizedExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val subscriptionExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val sliceItemConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val binaryExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val prefixExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val callExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val listLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val tupleExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val keywordArgumentExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val starArgumentExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val lambdaExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val listCompExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val dictLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val keyValueExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val reprExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val generatorExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val conditionalExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val yieldExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val starExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val doubleStarExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val assignmentExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val setLiteralExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val setCompExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val dictCompExpressionConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val ifPartIfConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val ifPartElifConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val forPartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val whilePartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val tryPartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val finallyPartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val elsePartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val fStringNodeConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val fStringFragmentConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val fStringFragmentFormatPartConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val matchStatementConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val caseClauseConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val literalPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val valuePatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val capturePatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val wildcardPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val groupPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val sequencePatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val singleStarPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val doubleStarPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val mappingPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val keyValuePatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val classPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val patternArgumentListConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val keywordPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val orPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC
    override val asPatternConstructor: Function<in ASTNode, out PsiElement> = GENERIC

    private companion object {
        val GENERIC = Function<ASTNode, PsiElement> { ASTWrapperPsiElement(it) }
    }
}
