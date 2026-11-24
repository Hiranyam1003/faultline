package edu.atlas.pwaudit.algo;

/**
 * Lower-cases text and undoes common "leetspeak" substitutions (p@ssw0rd → password).
 * Every character maps to exactly one character, so match positions in the normalised
 * string line up with the original.
 */
public final class LeetNormalizer {

    private LeetNormalizer() {}

    public static String normalize(String text) {
        char[] out = new char[text.length()];
        for (int i = 0; i < text.length(); i++) out[i] = map(Character.toLowerCase(text.charAt(i)));
        return new String(out);
    }

    /** Per-character lower-casing; unlike String.toLowerCase it never changes the length. */
    public static String lowerCase(String text) {
        char[] out = new char[text.length()];
        for (int i = 0; i < text.length(); i++) out[i] = Character.toLowerCase(text.charAt(i));
        return new String(out);
    }

    private static char map(char c) {
        return switch (c) {
            case '@', '4' -> 'a';
            case '8' -> 'b';
            case '(' -> 'c';
            case '3' -> 'e';
            case '6', '9' -> 'g';
            case '1', '!', '|' -> 'i';
            case '0' -> 'o';
            case '$', '5' -> 's';
            case '7', '+' -> 't';
            default -> c;
        };
    }
}
