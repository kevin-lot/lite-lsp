// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package me.kevinlot.lite.python.upstream.lexer;

import com.intellij.lexer.FlexAdapter;
import org.jetbrains.annotations.NotNull;


public class PythonLexer extends FlexAdapter {
  public PythonLexer() {
    this(PythonLexerKind.REGULAR);
  }

  public PythonLexer(@NotNull PythonLexerKind kind) {
    super(new _PythonLexer(null, kind));
  }

  @Override
  public _PythonLexer getFlex() {
    return (_PythonLexer)super.getFlex();
  }
}
