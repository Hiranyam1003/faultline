package edu.atlas.pwaudit.algo;

import java.util.ArrayList;
import java.util.List;

/** Knuth–Morris–Pratt string matching and prefix-function based repetition detection. */
public final class Kmp {

    private Kmp() {}

    /** A prefix of the text that is {@code count} back-to-back copies of {@code block}. */
    public record Repetition(String block, int count, int length) {}

    /**
     * pi[i] = length of the longest proper prefix of s[0..i] that is also a suffix of s[0..i].
     * Amortised O(n): j increases by at most 1 per step and every fallback decreases it.
     */
    public static int[] prefixFunction(String s) {
        int[] pi = new int[s.length()];
        for (int i = 1; i < s.length(); i++) {
            int j = pi[i - 1];
            while (j > 0 && s.charAt(i) != s.charAt(j)) j = pi[j - 1];
            if (s.charAt(i) == s.charAt(j)) j++;
            pi[i] = j;
        }
        return pi;
    }

    /** Start indices of every (possibly overlapping) occurrence of pattern in text. O(n + m). */
    public static List<Integer> allMatches(String text, String pattern) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("pattern must be non-empty");
        List<Integer> result = new ArrayList<>();
        if (pattern.length() > text.length()) return result;
        int[] pi = prefixFunction(pattern);
        int j = 0;
        for (int i = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = pi[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == pattern.length()) {
                result.add(i - j + 1);
                j = pi[j - 1];
            }
        }
        return result;
    }

    /** First occurrence of pattern in text, or -1. */
    public static int indexOf(String text, String pattern) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("pattern must be non-empty");
        if (pattern.length() > text.length()) return -1;
        int[] pi = prefixFunction(pattern);
        int j = 0;
        for (int i = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = pi[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == pattern.length()) return i - j + 1;
        }
        return -1;
    }

    /** Length of the smallest block whose repetition forms s exactly (s.length() if none). */
    public static int smallestPeriod(String s) {
        if (s.isEmpty()) return 0;
        int n = s.length();
        int p = n - prefixFunction(s)[n - 1];
        return n % p == 0 ? p : n;
    }

    /**
     * Longest prefix of s made of at least two copies of one block, e.g. "abcabc1" → ("abc", 2).
     * Uses one prefix-function pass: prefix length L has period L - pi[L-1] when that divides L.
     * Returns null when no prefix repeats.
     */
    public static Repetition longestRepeatedPrefix(String s) {
        int[] pi = prefixFunction(s);
        for (int len = s.length(); len >= 2; len--) {
            int period = len - pi[len - 1];
            if (period < len && len % period == 0) {
                return new Repetition(s.substring(0, period), len / period, len);
            }
        }
        return null;
    }
}
