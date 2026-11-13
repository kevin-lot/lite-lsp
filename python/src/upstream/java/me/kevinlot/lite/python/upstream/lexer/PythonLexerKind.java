// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
package me.kevinlot.lite.python.upstream.lexer;

/**
 * Python lexer kind determines the types of lexical rules allowed for a specific Python dialect.
 * <br/>
 * So far only Python Console and Cython require special lexing rules (and we apply Cython rules by default anyway).
 */
public enum PythonLexerKind {
  REGULAR,  // Regular Python files
  CONSOLE,  // Code fragments for the Python Console
  LAZY      // Lazy-parseable elements

  // We can add Cython or other dialects here if we ever need to customize the lexer extensively
  // If there are many cases like this, we might consider turning it into an extension point
}
