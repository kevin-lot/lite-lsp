// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).
package me.kevinlot.lite.kotlin.upstream.parser;

final class ParserUtil {
    private ParserUtil() {}

    /** Text around [start, end) for error messages (from the compiler's StringsKt). */
    static String substringWithContext(CharSequence text, int start, int end, int context) {
        int from = Math.max(0, start - context);
        int to = Math.min(text.length(), end + context);
        return text.subSequence(from, to).toString();
    }
}
