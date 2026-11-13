// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.IReparseableLeafElementType;
import org.jetbrains.annotations.NotNull;

public abstract class PyReparseableTokenType extends PyElementType implements IReparseableLeafElementType<ASTNode> {

  public PyReparseableTokenType(@NotNull String debugName) {
    super(debugName);
  }
}
