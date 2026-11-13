// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).
package me.kevinlot.lite.kotlin.upstream.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/** Parses a whole `.kt` / `.kts` file eagerly (no lazy blocks or lambdas). */
public class KtParser implements PsiParser {
    private final boolean script;

    public KtParser(boolean script) {
        this.script = script;
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        KotlinParsing parsing = KotlinParsing.createForTopLevelNonLazy(new SemanticWhitespaceAwarePsiBuilderImpl(builder));
        if (script) parsing.parseScript(); else parsing.parseFile();
        return builder.getTreeBuilt();
    }
}
