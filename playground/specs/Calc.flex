// Ctrl+Click: `{DIGIT}` → its definition, `STRING` in `<STRING>` or `yybegin(STRING)` → `%state STRING`.
package playground;

%%

%class CalcLexer
%unicode
%state STRING

DIGIT = [0-9]
NUMBER = {DIGIT}+ ("." {DIGIT}+)?
IDENTIFIER = [A-Za-z_] ([A-Za-z_] | {DIGIT})*

%%

<YYINITIAL> {
  {NUMBER}      { return Token.NUMBER; }
  {IDENTIFIER}  { return Token.IDENTIFIER; }
  \"            { yybegin(STRING); }
}
<STRING> \"     { yybegin(YYINITIAL); return Token.STRING; }
<STRING> [^\"]+ { /* text */ }
