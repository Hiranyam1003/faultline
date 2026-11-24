package edu.atlas.pwaudit.index;

import java.util.List;
import java.util.Map;

/**
 * Raw word lists read once at startup.
 *
 * @param leaked       leaked passwords in frequency order (line i has rank i + 1)
 * @param dictionaries "keyboard", "year", "name", "english" → words as they appear in the files
 */
public record Wordlists(List<String> leaked, Map<String, List<String>> dictionaries) {}
