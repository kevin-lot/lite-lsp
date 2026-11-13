// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.kevinlot.lite.python.upstream;

import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

import static me.kevinlot.lite.python.upstream.PyElementTypes.ASSERT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.ASSIGNMENT_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.ASSIGNMENT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.AUG_ASSIGNMENT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.BINARY_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.BOOL_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.BREAK_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.CALL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.CASE_CLAUSE;
import static me.kevinlot.lite.python.upstream.PyElementTypes.CLASS_DECLARATION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.CONDITIONAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.CONTINUE_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.DEL_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.DICT_COMP_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.DICT_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.DOUBLE_STAR_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.ELLIPSIS_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.EMPTY_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.EXEC_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.EXPRESSION_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.FLOAT_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.FOR_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.FROM_IMPORT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.FUNCTION_DECLARATION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.GENERATOR_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.GLOBAL_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.IF_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.IMAGINARY_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.IMPORT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.INTEGER_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.KEYWORD_ARGUMENT_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.KEY_VALUE_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.LAMBDA_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.LIST_COMP_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.LIST_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.MATCH_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.NAMED_PARAMETER;
import static me.kevinlot.lite.python.upstream.PyElementTypes.NONE_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.NONLOCAL_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.PARENTHESIZED_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.PASS_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.PREFIX_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.PRINT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.RAISE_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.REFERENCE_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.REPR_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.RETURN_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SET_COMP_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SET_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SINGLE_STAR_PARAMETER;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SLASH_PARAMETER;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SLICE_ITEM;
import static me.kevinlot.lite.python.upstream.PyElementTypes.STAR_ARGUMENT_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.STAR_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.STRING_LITERAL_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.SUBSCRIPTION_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TARGET_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TRY_EXCEPT_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TUPLE_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TUPLE_PARAMETER;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TYPE_ALIAS_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.TYPE_DECLARATION_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.WHILE_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.WITH_STATEMENT;
import static me.kevinlot.lite.python.upstream.PyElementTypes.YIELD_EXPRESSION;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.AND_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.ASSERT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.ASYNC_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.AS_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.AWAIT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.BREAK_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.CASE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.CLASS_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.CONTINUE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.DEBUG_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.DEF_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.DEL_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.ELIF_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.ELLIPSIS_LITERAL;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.ELSE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.EXCEPT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.EXEC_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.FALSE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.FINALLY_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.FOR_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.FROM_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.GLOBAL_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.IF_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.IMPORT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.IN_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.IS_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.LAMBDA_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.LAZY_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.MATCH_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.NONE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.NONLOCAL_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.NOT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.OR_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.PASS_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.PRINT_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.RAISE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.RETURN_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.TRUE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.TRY_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.TYPE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.WHILE_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.WITH_KEYWORD;
import static me.kevinlot.lite.python.upstream.PyTokenTypes.YIELD_KEYWORD;

public final class PythonTokenSetContributor extends PythonDialectsTokenSetContributorBase {
  @Override
  public @NotNull TokenSet getStatementTokens() {
    return TokenSet.create(EXPRESSION_STATEMENT, ASSIGNMENT_STATEMENT, TYPE_DECLARATION_STATEMENT, AUG_ASSIGNMENT_STATEMENT, 
                           ASSERT_STATEMENT, BREAK_STATEMENT, CONTINUE_STATEMENT, DEL_STATEMENT, EXEC_STATEMENT, FOR_STATEMENT,
                           FROM_IMPORT_STATEMENT, GLOBAL_STATEMENT, IMPORT_STATEMENT, IF_STATEMENT, PASS_STATEMENT,
                           PRINT_STATEMENT, RAISE_STATEMENT, RETURN_STATEMENT, TRY_EXCEPT_STATEMENT, WITH_STATEMENT,
                           WHILE_STATEMENT, NONLOCAL_STATEMENT, CLASS_DECLARATION, FUNCTION_DECLARATION, MATCH_STATEMENT, CASE_CLAUSE,
                           TYPE_ALIAS_STATEMENT);
  }

  @Override
  public @NotNull TokenSet getExpressionTokens() {
    return TokenSet.create(EMPTY_EXPRESSION, REFERENCE_EXPRESSION, INTEGER_LITERAL_EXPRESSION, FLOAT_LITERAL_EXPRESSION,
                           IMAGINARY_LITERAL_EXPRESSION, STRING_LITERAL_EXPRESSION, PARENTHESIZED_EXPRESSION,
                           SUBSCRIPTION_EXPRESSION, SLICE_ITEM, BINARY_EXPRESSION, PREFIX_EXPRESSION, CALL_EXPRESSION,
                           LIST_LITERAL_EXPRESSION, TUPLE_EXPRESSION, KEYWORD_ARGUMENT_EXPRESSION, STAR_ARGUMENT_EXPRESSION,
                           LAMBDA_EXPRESSION, LIST_COMP_EXPRESSION, DICT_LITERAL_EXPRESSION, KEY_VALUE_EXPRESSION,
                           REPR_EXPRESSION, GENERATOR_EXPRESSION, CONDITIONAL_EXPRESSION, YIELD_EXPRESSION,
                           TARGET_EXPRESSION, NONE_LITERAL_EXPRESSION, ELLIPSIS_LITERAL_EXPRESSION, BOOL_LITERAL_EXPRESSION,
                           SET_LITERAL_EXPRESSION, SET_COMP_EXPRESSION, DICT_COMP_EXPRESSION, STAR_EXPRESSION, DOUBLE_STAR_EXPRESSION,
                           ASSIGNMENT_EXPRESSION);
  }

  @Override
  public @NotNull TokenSet getKeywordTokens() {
    return TokenSet.create(
      AND_KEYWORD, AS_KEYWORD, ASSERT_KEYWORD, BREAK_KEYWORD, CLASS_KEYWORD,
      CONTINUE_KEYWORD, DEF_KEYWORD, DEL_KEYWORD, ELIF_KEYWORD, ELSE_KEYWORD,
      EXCEPT_KEYWORD, EXEC_KEYWORD, FINALLY_KEYWORD, FOR_KEYWORD,
      FROM_KEYWORD,
      GLOBAL_KEYWORD, IF_KEYWORD, IMPORT_KEYWORD, IN_KEYWORD, IS_KEYWORD,
      LAMBDA_KEYWORD, NOT_KEYWORD, OR_KEYWORD, PASS_KEYWORD, PRINT_KEYWORD,
      RAISE_KEYWORD, RETURN_KEYWORD, TRY_KEYWORD, WITH_KEYWORD, WHILE_KEYWORD,
      YIELD_KEYWORD,
      MATCH_KEYWORD, CASE_KEYWORD, LAZY_KEYWORD,
      NONE_KEYWORD, ELLIPSIS_LITERAL, TRUE_KEYWORD, FALSE_KEYWORD, NONLOCAL_KEYWORD, DEBUG_KEYWORD, ASYNC_KEYWORD, AWAIT_KEYWORD, TYPE_KEYWORD);
  }

  @Override
  public @NotNull TokenSet getParameterTokens() {
    return TokenSet.create(NAMED_PARAMETER, TUPLE_PARAMETER, SINGLE_STAR_PARAMETER, SLASH_PARAMETER);
  }

  @Override
  public @NotNull TokenSet getFunctionDeclarationTokens() {
    return TokenSet.create(FUNCTION_DECLARATION);
  }

  @Override
  public @NotNull TokenSet getUnbalancedBracesRecoveryTokens() {
    // Sync these tokens with Python.flex under the <FSTRING_FRAGMENT> state
    return TokenSet.create(DEF_KEYWORD, CLASS_KEYWORD, RETURN_KEYWORD, WITH_KEYWORD, WHILE_KEYWORD, BREAK_KEYWORD, CONTINUE_KEYWORD,
                           RAISE_KEYWORD, TRY_KEYWORD, EXCEPT_KEYWORD, FINALLY_KEYWORD);
  }

  @Override
  public @NotNull TokenSet getReferenceExpressionTokens() {
    return TokenSet.create(REFERENCE_EXPRESSION);
  }
}
