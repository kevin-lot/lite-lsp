// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package me.kevinlot.lite.python.upstream.parsing.console;

import com.intellij.psi.tree.IElementType;
import me.kevinlot.lite.python.upstream.PyTokenTypes;
import me.kevinlot.lite.python.upstream.lexer.PythonHighlightingLexer;
import me.kevinlot.lite.python.upstream.lexer.PythonLexerKind;
import me.kevinlot.lite.python.upstream.psi.LanguageLevel;

public class PyConsoleHighlightingLexer extends PythonHighlightingLexer {
  public PyConsoleHighlightingLexer(LanguageLevel languageLevel) {
    super(languageLevel, PythonLexerKind.CONSOLE);
  }

  /**
   * Treats special symbols used in IPython console
   */
  @Override
  public IElementType getTokenType() {
    IElementType type = super.getTokenType();
    if (type == PyTokenTypes.BAD_CHARACTER && PythonConsoleLexer.isSpecialSymbols(getTokenText())) {
      type = PythonConsoleLexer.getElementType(getTokenText());
    }

    return type;
  }
}
