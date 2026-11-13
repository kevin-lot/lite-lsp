// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).
package me.kevinlot.lite.kotlin.upstream.parser;

import com.intellij.psi.tree.IElementType;
import me.kevinlot.lite.kotlin.KtLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/** Composite element type of the Kotlin tree (PSI is created by KtPsiFactoryImpl from the type). */
public class KtNodeType extends IElementType {
    public KtNodeType(@NotNull @NonNls String debugName) {
        super(debugName, KtLanguage.INSTANCE);
    }

    /** Left-bound: whitespace before it is not included (used for delegation calls). */
    public static class KtLeftBoundNodeType extends KtNodeType {
        public KtLeftBoundNodeType(@NotNull @NonNls String debugName) {
            super(debugName);
        }

        @Override
        public boolean isLeftBound() {
            return true;
        }
    }
}
