package edu.atlas.pwaudit.algo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Character trie that finds every dictionary token occurring anywhere inside a text.
 *
 * findAll walks the trie once from each start position, so a text of length n against a
 * dictionary whose longest word has length L costs O(n * L), independent of dictionary size.
 */
public final class Trie {

    public record Match(int start, int end, String token, String category) {
        public int length() { return end - start; }
    }

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>(4);
        String token;
        String category;
    }

    private final Node root = new Node();
    private int size;
    private int nodeCount = 1;

    /** Inserts a word; if it already exists, the first category it was inserted with is kept. */
    public void insert(String word, String category) {
        if (word == null || word.isEmpty()) throw new IllegalArgumentException("word must be non-empty");
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            Node next = node.children.get(word.charAt(i));
            if (next == null) {
                next = new Node();
                node.children.put(word.charAt(i), next);
                nodeCount++;
            }
            node = next;
        }
        if (node.token == null) {
            node.token = word;
            node.category = category;
            size++;
        }
    }

    public boolean contains(String word) {
        Node node = root;
        for (int i = 0; i < word.length() && node != null; i++) node = node.children.get(word.charAt(i));
        return node != null && node.token != null;
    }

    /** All (possibly overlapping) dictionary words occurring in text, ordered by start then length. */
    public List<Match> findAll(String text) {
        List<Match> matches = new ArrayList<>();
        for (int start = 0; start < text.length(); start++) {
            Node node = root;
            for (int i = start; i < text.length(); i++) {
                node = node.children.get(text.charAt(i));
                if (node == null) break;
                if (node.token != null) matches.add(new Match(start, i + 1, node.token, node.category));
            }
        }
        return matches;
    }

    public int size() { return size; }
    public int nodeCount() { return nodeCount; }
}
