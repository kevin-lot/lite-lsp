// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.psi;

import org.jetbrains.annotations.NotNull;

public class PyReparseableEndOfLineCommentType extends PyReparseableTokenTypeWithSimpleCheck {

  public PyReparseableEndOfLineCommentType(@NotNull String debugName) {
    super(debugName);
  }

  @Override
  public boolean isReparseable(@NotNull String newText) {
    return newText.startsWith("#");
  }
}
