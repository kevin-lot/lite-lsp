// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.psi;

import com.intellij.psi.tree.IElementType;
import me.kevinlot.lite.python.upstream.PyTokenTypes;
import me.kevinlot.lite.python.upstream.lexer.PythonLexer;
import org.jetbrains.annotations.NotNull;

public class PyReparseableTripleQuotedStringTokenType extends PyReparseableTokenTypeWithSimpleCheck {

  public PyReparseableTripleQuotedStringTokenType(@NotNull String debugName) {
    super(debugName);
  }

  @Override
  public boolean isReparseable(@NotNull String newText) {
    if (!isTripleQuotedString(newText)) { // fail-fast
      return false;
    }
    PythonLexer lexer = new PythonLexer();
    lexer.start(newText);
    IElementType firstTokenType = lexer.getTokenType();
    lexer.advance();
    IElementType nextTokenType = lexer.getTokenType();
    return firstTokenType == PyTokenTypes.DOCSTRING && nextTokenType == null;
  }

  private static boolean isTripleQuotedString(@NotNull String newText) {
    return newText.length() >= 6
           && ((newText.startsWith("\"\"\"") && newText.endsWith("\"\"\""))
               || (newText.startsWith("'''") && newText.endsWith("'''")));
  }
}
