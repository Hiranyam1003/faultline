package edu.atlas.pwaudit.index;

import edu.atlas.pwaudit.algo.BloomFilter;
import edu.atlas.pwaudit.algo.Trie;
import edu.atlas.pwaudit.store.SortedHashStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Everything the auditor looks passwords up in.
 *
 * @param bloom         in-memory precheck over SHA-1 hashes of all leaked passwords
 * @param store         authoritative sorted hash file on disk
 * @param trie          weak-pattern dictionary (keyboard walks, years, common passwords, names, words)
 * @param categorySizes number of trie words per category, used for guess-count entropy
 * @param leakedCount   number of leaked passwords loaded (before de-duplication)
 */
public record PasswordIndex(BloomFilter bloom, SortedHashStore store, Trie trie,
                            Map<String, Integer> categorySizes, int leakedCount) {

    public static byte[] sha1(String password) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
