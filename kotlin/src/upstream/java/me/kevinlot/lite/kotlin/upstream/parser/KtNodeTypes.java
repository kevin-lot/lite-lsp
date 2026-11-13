// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).
/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package me.kevinlot.lite.kotlin.upstream.parser;

import me.kevinlot.lite.kotlin.KtLanguage;
import me.kevinlot.lite.kotlin.parser.KtFileElementType;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import org.jetbrains.annotations.NotNull;

public interface KtNodeTypes {
    @NotNull IFileElementType FILE = KtFileElementType.INSTANCE;

    /**
     * @deprecated Use {@code FILE} instead.
     */
    @Deprecated @NotNull IFileElementType KT_FILE = FILE;

    @NotNull IElementType CLASS = new KtNodeType("CLASS");
    @NotNull IElementType FUN = new KtNodeType("FUN");
    @NotNull IElementType PROPERTY = new KtNodeType("PROPERTY");
    @NotNull IElementType DESTRUCTURING_DECLARATION = new KtNodeType("DESTRUCTURING_DECLARATION");
    @NotNull IElementType DESTRUCTURING_DECLARATION_ENTRY = new KtNodeType("DESTRUCTURING_DECLARATION_ENTRY");

    @NotNull IElementType OBJECT_DECLARATION = new KtNodeType("OBJECT_DECLARATION");
    @NotNull IElementType TYPEALIAS = new KtNodeType("TYPEALIAS");
    @NotNull IElementType COMPANION_BLOCK = new KtNodeType("COMPANION_BLOCK");

    @NotNull IElementType ENUM_ENTRY = new KtNodeType("ENUM_ENTRY");
    @NotNull IElementType CLASS_INITIALIZER = new KtNodeType("CLASS_INITIALIZER");
    @NotNull IElementType SCRIPT_INITIALIZER = new KtNodeType("SCRIPT_INITIALIZER");
    @NotNull IElementType SECONDARY_CONSTRUCTOR = new KtNodeType("SECONDARY_CONSTRUCTOR");
    @NotNull IElementType PRIMARY_CONSTRUCTOR = new KtNodeType("PRIMARY_CONSTRUCTOR");
    @NotNull IElementType CONTEXT_RECEIVER = new KtNodeType("CONTEXT_RECEIVER");
    @NotNull IElementType CONTEXT_PARAMETER_LIST = new KtNodeType("CONTEXT_PARAMETER_LIST");

    /**
     * @deprecated Use {@link #CONTEXT_PARAMETER_LIST} instead.
     * This constant has been renamed to align the name with the context parameters feature.
     */
    @Deprecated
    @NotNull IElementType CONTEXT_RECEIVER_LIST = CONTEXT_PARAMETER_LIST;

    @NotNull IElementType TYPE_PARAMETER_LIST = new KtNodeType("TYPE_PARAMETER_LIST");
    @NotNull IElementType TYPE_PARAMETER = new KtNodeType("TYPE_PARAMETER");
    @NotNull IElementType SUPER_TYPE_LIST = new KtNodeType("SUPER_TYPE_LIST");
    @NotNull IElementType DELEGATED_SUPER_TYPE_ENTRY = new KtNodeType("DELEGATED_SUPER_TYPE_ENTRY");
    @NotNull IElementType SUPER_TYPE_CALL_ENTRY = new KtNodeType("SUPER_TYPE_CALL_ENTRY");
    @NotNull IElementType SUPER_TYPE_ENTRY = new KtNodeType("SUPER_TYPE_ENTRY");
    @NotNull IElementType PROPERTY_DELEGATE = new KtNodeType("PROPERTY_DELEGATE");
    @NotNull IElementType CONSTRUCTOR_CALLEE = new KtNodeType("CONSTRUCTOR_CALLEE");
    @NotNull IElementType VALUE_PARAMETER_LIST = new KtNodeType("VALUE_PARAMETER_LIST");
    @NotNull IElementType VALUE_PARAMETER = new KtNodeType("VALUE_PARAMETER");

    @NotNull IElementType CLASS_BODY = new KtNodeType("CLASS_BODY");
    @NotNull IElementType IMPORT_LIST = new KtNodeType("IMPORT_LIST");
    @NotNull IElementType FILE_ANNOTATION_LIST = new KtNodeType("FILE_ANNOTATION_LIST");
    @NotNull IElementType IMPORT_DIRECTIVE = new KtNodeType("IMPORT_DIRECTIVE");
    @NotNull IElementType IMPORT_ALIAS = new KtNodeType("IMPORT_ALIAS");
    @NotNull IElementType MODIFIER_LIST = new KtNodeType("MODIFIER_LIST");
    @NotNull IElementType ANNOTATION = new KtNodeType("ANNOTATION");
    @NotNull IElementType ANNOTATION_ENTRY = new KtNodeType("ANNOTATION_ENTRY");
    @NotNull IElementType ANNOTATION_TARGET = new KtNodeType("ANNOTATION_TARGET");

    @NotNull IElementType TYPE_ARGUMENT_LIST = new KtNodeType("TYPE_ARGUMENT_LIST");
    @NotNull IElementType VALUE_ARGUMENT_LIST = new KtNodeType("VALUE_ARGUMENT_LIST");
    @NotNull IElementType VALUE_ARGUMENT = new KtNodeType("VALUE_ARGUMENT");
    @NotNull IElementType CONTRACT_EFFECT_LIST = new KtNodeType("CONTRACT_EFFECT_LIST");
    @NotNull IElementType CONTRACT_EFFECT = new KtNodeType("CONTRACT_EFFECT");
    @NotNull IElementType LAMBDA_ARGUMENT = new KtNodeType("LAMBDA_ARGUMENT");
    @NotNull IElementType VALUE_ARGUMENT_NAME = new KtNodeType("VALUE_ARGUMENT_NAME");
    @NotNull IElementType TYPE_REFERENCE = new KtNodeType("TYPE_REFERENCE");

    @NotNull IElementType USER_TYPE = new KtNodeType("USER_TYPE");
    @NotNull IElementType DYNAMIC_TYPE = new KtNodeType("DYNAMIC_TYPE");
    @NotNull IElementType FUNCTION_TYPE = new KtNodeType("FUNCTION_TYPE");
    @NotNull IElementType FUNCTION_TYPE_RECEIVER = new KtNodeType("FUNCTION_TYPE_RECEIVER");
    @NotNull IElementType NULLABLE_TYPE = new KtNodeType("NULLABLE_TYPE");
    @NotNull IElementType INTERSECTION_TYPE = new KtNodeType("INTERSECTION_TYPE");
    @NotNull IElementType UNION_TYPE = new KtNodeType("UNION_TYPE");
    @NotNull IElementType TYPE_PROJECTION = new KtNodeType("TYPE_PROJECTION");

    @NotNull IElementType PROPERTY_ACCESSOR = new KtNodeType("PROPERTY_ACCESSOR");
    @NotNull IElementType BACKING_FIELD = new KtNodeType("BACKING_FIELD");
    @NotNull IElementType INITIALIZER_LIST = new KtNodeType("INITIALIZER_LIST");
    @NotNull IElementType TYPE_CONSTRAINT_LIST = new KtNodeType("TYPE_CONSTRAINT_LIST");
    @NotNull IElementType TYPE_CONSTRAINT = new KtNodeType("TYPE_CONSTRAINT");

    @NotNull IElementType CONSTRUCTOR_DELEGATION_CALL = new KtNodeType.KtLeftBoundNodeType("CONSTRUCTOR_DELEGATION_CALL");
    @NotNull IElementType CONSTRUCTOR_DELEGATION_REFERENCE = new KtNodeType.KtLeftBoundNodeType("CONSTRUCTOR_DELEGATION_REFERENCE");

    @NotNull IElementType NULL = new KtNodeType("NULL");
    @NotNull IElementType BOOLEAN_CONSTANT = new KtNodeType("BOOLEAN_CONSTANT");
    @NotNull IElementType FLOAT_CONSTANT = new KtNodeType("FLOAT_CONSTANT");
    @NotNull IElementType CHARACTER_CONSTANT = new KtNodeType("CHARACTER_CONSTANT");
    @NotNull IElementType INTEGER_CONSTANT = new KtNodeType("INTEGER_CONSTANT");

    @NotNull IElementType STRING_TEMPLATE = new KtNodeType("STRING_TEMPLATE");
    @NotNull IElementType LONG_STRING_TEMPLATE_ENTRY = new KtNodeType("LONG_STRING_TEMPLATE_ENTRY");
    @NotNull IElementType SHORT_STRING_TEMPLATE_ENTRY = new KtNodeType("SHORT_STRING_TEMPLATE_ENTRY");
    @NotNull IElementType LITERAL_STRING_TEMPLATE_ENTRY = new KtNodeType("LITERAL_STRING_TEMPLATE_ENTRY");
    @NotNull IElementType ESCAPE_STRING_TEMPLATE_ENTRY = new KtNodeType("ESCAPE_STRING_TEMPLATE_ENTRY");
    @NotNull IElementType STRING_INTERPOLATION_PREFIX = new KtNodeType("STRING_INTERPOLATION_PREFIX");

    @NotNull IElementType PARENTHESIZED = new KtNodeType("PARENTHESIZED");
    @NotNull IElementType RETURN = new KtNodeType("RETURN");
    @NotNull IElementType THROW = new KtNodeType("THROW");
    @NotNull IElementType CONTINUE = new KtNodeType("CONTINUE");
    @NotNull IElementType BREAK = new KtNodeType("BREAK");
    @NotNull IElementType IF = new KtNodeType("IF");
    @NotNull IElementType CONDITION = new KtNodeType("CONDITION");
    @NotNull IElementType THEN = new KtNodeType("THEN");
    @NotNull IElementType ELSE = new KtNodeType("ELSE");
    @NotNull IElementType TRY = new KtNodeType("TRY");
    @NotNull IElementType CATCH = new KtNodeType("CATCH");
    @NotNull IElementType FINALLY = new KtNodeType("FINALLY");
    @NotNull IElementType FOR = new KtNodeType("FOR");
    @NotNull IElementType WHILE = new KtNodeType("WHILE");
    @NotNull IElementType DO_WHILE = new KtNodeType("DO_WHILE");
    @NotNull IElementType LOOP_RANGE = new KtNodeType("LOOP_RANGE");
    @NotNull IElementType BODY = new KtNodeType("BODY");

    @NotNull IElementType BLOCK = new KtNodeType("BLOCK");

    @NotNull IElementType LAMBDA_EXPRESSION = new KtNodeType("LAMBDA_EXPRESSION");

    @NotNull IElementType FUNCTION_LITERAL = new KtNodeType("FUNCTION_LITERAL");
    @NotNull IElementType ANNOTATED_EXPRESSION = new KtNodeType("ANNOTATED_EXPRESSION");

    @NotNull IElementType REFERENCE_EXPRESSION = new KtNodeType("REFERENCE_EXPRESSION");
    @NotNull IElementType ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION = new KtNodeType("ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION");
    @NotNull IElementType OPERATION_REFERENCE = new KtNodeType("OPERATION_REFERENCE");
    @NotNull IElementType LABEL = new KtNodeType("LABEL");

    @NotNull IElementType LABEL_QUALIFIER = new KtNodeType("LABEL_QUALIFIER");

    @NotNull IElementType THIS_EXPRESSION = new KtNodeType("THIS_EXPRESSION");
    @NotNull IElementType SUPER_EXPRESSION = new KtNodeType("SUPER_EXPRESSION");
    @NotNull IElementType BINARY_EXPRESSION = new KtNodeType("BINARY_EXPRESSION");
    @NotNull IElementType BINARY_WITH_TYPE = new KtNodeType("BINARY_WITH_TYPE");
    @NotNull IElementType IS_EXPRESSION = new KtNodeType("IS_EXPRESSION");
    @NotNull IElementType PREFIX_EXPRESSION = new KtNodeType("PREFIX_EXPRESSION");
    @NotNull IElementType POSTFIX_EXPRESSION = new KtNodeType("POSTFIX_EXPRESSION");
    @NotNull IElementType LABELED_EXPRESSION = new KtNodeType("LABELED_EXPRESSION");
    @NotNull IElementType CALL_EXPRESSION = new KtNodeType("CALL_EXPRESSION");
    @NotNull IElementType ARRAY_ACCESS_EXPRESSION = new KtNodeType("ARRAY_ACCESS_EXPRESSION");
    @NotNull IElementType INDICES = new KtNodeType("INDICES");
    @NotNull IElementType DOT_QUALIFIED_EXPRESSION = new KtNodeType("DOT_QUALIFIED_EXPRESSION");
    @NotNull IElementType CALLABLE_REFERENCE_EXPRESSION = new KtNodeType("CALLABLE_REFERENCE_EXPRESSION");
    @NotNull IElementType CLASS_LITERAL_EXPRESSION = new KtNodeType("CLASS_LITERAL_EXPRESSION");
    @NotNull IElementType SAFE_ACCESS_EXPRESSION = new KtNodeType("SAFE_ACCESS_EXPRESSION");
    @NotNull IElementType ERROR_SAFE_ACCESS_EXPRESSION = new KtNodeType("ERROR_SAFE_ACCESS_EXPRESSION");

    @NotNull IElementType OBJECT_LITERAL = new KtNodeType("OBJECT_LITERAL");

    @NotNull IElementType WHEN = new KtNodeType("WHEN");
    @NotNull IElementType WHEN_ENTRY = new KtNodeType("WHEN_ENTRY");
    @NotNull IElementType WHEN_ENTRY_GUARD = new KtNodeType("WHEN_ENTRY_GUARD");

    @NotNull IElementType WHEN_CONDITION_IN_RANGE = new KtNodeType("WHEN_CONDITION_IN_RANGE");
    @NotNull IElementType WHEN_CONDITION_IS_PATTERN = new KtNodeType("WHEN_CONDITION_IS_PATTERN");
    @NotNull IElementType WHEN_CONDITION_EXPRESSION = new KtNodeType("WHEN_CONDITION_EXPRESSION");

    @NotNull IElementType COLLECTION_LITERAL_EXPRESSION = new KtNodeType("COLLECTION_LITERAL_EXPRESSION");

    @NotNull IElementType PACKAGE_DIRECTIVE = new KtNodeType("PACKAGE_DIRECTIVE");

    @NotNull IElementType SCRIPT = new KtNodeType("SCRIPT");

    @NotNull IFileElementType TYPE_CODE_FRAGMENT = KtFileElementType.INSTANCE;
    @NotNull IFileElementType EXPRESSION_CODE_FRAGMENT = KtFileElementType.INSTANCE;
    @NotNull IFileElementType BLOCK_CODE_FRAGMENT = KtFileElementType.INSTANCE;
}
