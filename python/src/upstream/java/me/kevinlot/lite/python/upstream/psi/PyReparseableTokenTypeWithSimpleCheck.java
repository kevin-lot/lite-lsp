// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.psi;

import com.intellij.lang.ASTFactory;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class PyReparseableTokenTypeWithSimpleCheck extends PyReparseableTokenType {

  @SuppressWarnings("LoggerInitializedWithForeignClass")
  private static final Logger LOG = Logger.getInstance(PyReparseableTokenType.class);

  public PyReparseableTokenTypeWithSimpleCheck(@NotNull String debugName) {
    super(debugName);
  }

  @Override
  public @Nullable ASTNode reparseLeaf(@NotNull ASTNode leaf, @NotNull CharSequence newText) {
    if (!false) {
      return null;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Attempting to reparse leaf element of type" + this
                + "\nold text: \n" + leaf.getText()
                + "\n\nnew text: \n" + newText);
    }

    if (isReparseable(newText.toString())) {
      LOG.debug("Reparse is successful");
      return ASTFactory.leaf(this, newText);
    }
    LOG.debug("Reparse is declined");
    return null;
  }

  public abstract boolean isReparseable(@NotNull String newText);
}
