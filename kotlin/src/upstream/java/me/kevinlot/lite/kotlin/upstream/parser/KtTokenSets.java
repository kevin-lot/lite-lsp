// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).
package me.kevinlot.lite.kotlin.upstream.parser;

import com.intellij.psi.tree.TokenSet;
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens;

/** The two token sets of the compiler's KtTokenSets that the parser needs. */
public interface KtTokenSets {
    TokenSet POSTFIX_OPERATIONS = TokenSet.create(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS, KtTokens.EXCLEXCL, KtTokens.DOT,
        KtTokens.SAFE_ACCESS, KtTokens.ERROR_SAFE_ACCESS);
    TokenSet PREFIX_OPERATIONS = TokenSet.create(KtTokens.MINUS, KtTokens.PLUS, KtTokens.MINUSMINUS, KtTokens.PLUSPLUS, KtTokens.EXCL);
}
