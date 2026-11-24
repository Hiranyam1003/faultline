package edu.atlas.pwaudit.index;

import edu.atlas.pwaudit.algo.BloomFilter;
import edu.atlas.pwaudit.algo.Trie;
import edu.atlas.pwaudit.store.SortedHashStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IndexBuilder {

    private IndexBuilder() {}

    /**
     * @param leaked       leaked passwords in frequency order (line i has rank i + 1)
     * @param dictionaries category → words, in priority order (a word keeps its first category)
     * @param storeFile    where the sorted hash file lives
     * @param rebuildStore false reuses an existing store file (startup cache)
     */
    public static PasswordIndex build(List<String> leaked, Map<String, List<String>> dictionaries,
                                      Path storeFile, double targetFpr, boolean rebuildStore) throws IOException {
        BloomFilter bloom = BloomFilter.forCapacity(Math.max(1, leaked.size()), targetFpr);
        List<SortedHashStore.Entry> entries = new ArrayList<>(leaked.size());
        for (int i = 0; i < leaked.size(); i++) {
            byte[] hash = PasswordIndex.sha1(leaked.get(i));
            bloom.add(hash);
            entries.add(new SortedHashStore.Entry(hash, i + 1));
        }
        if (rebuildStore || !Files.exists(storeFile)) SortedHashStore.build(entries, storeFile);

        Trie trie = new Trie();
        Map<String, Integer> categorySizes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> dict : dictionaries.entrySet()) {
            String category = dict.getKey();
            int minLength = category.equals("keyboard") ? 3 : 4;
            int before = trie.size();
            for (String raw : dict.getValue()) {
                String word = raw.trim().toLowerCase(Locale.ROOT);
                if (word.length() >= minLength && isPrintableAscii(word)) trie.insert(word, category);
            }
            categorySizes.put(category, trie.size() - before);
        }
        return new PasswordIndex(bloom, SortedHashStore.open(storeFile), trie, categorySizes, leaked.size());
    }

    static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }
}
