// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.lexer;

import com.intellij.lexer.FlexLexer;

public interface FlexLexerEx extends FlexLexer {

  CharSequence yytext();

  int yylength();

  void yypushback(int number);
}
