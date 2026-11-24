package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.BloomFilter;
import edu.atlas.pwaudit.index.IndexBuilder;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.service.AuditResult.BreachStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    @TempDir
    Path dir;

    private PasswordIndex index;
    private AuditService service;

    @BeforeEach
    void setUp() throws IOException {
        List<String> leaked = List.of("123456", "password", "qwerty", "dragon", "password123");
        Map<String, List<String>> dictionaries = new LinkedHashMap<>();
        dictionaries.put("keyboard", List.of("qwerty", "123"));
        dictionaries.put("year", List.of("2019", "2004"));
        dictionaries.put("leaked", leaked);
        dictionaries.put("name", List.of("alice", "michael"));
        dictionaries.put("english", List.of("monkey", "sunshine"));
        // Tiny target FPR so the "not present" assertions are deterministic; the false-positive
        // path is exercised explicitly below with a saturated filter.
        index = IndexBuilder.build(leaked, dictionaries, dir.resolve("store.bin"), 1e-6, true);
        service = new AuditService(index);
    }

    @Test
    void leakedPasswordIsConfirmedWithRank() {
        AuditResult r = service.audit("password123", null);
        assertEquals(BreachStatus.CONFIRMED, r.breachStatus());
        assertTrue(r.bloomHit());
        assertEquals(5, r.leakRank());
        assertEquals(0, r.score());
        assertTrue(r.diskProbes() > 0);
    }

    @Test
    void unseenStrongPasswordSkipsDisk() {
        AuditResult r = service.audit("tK9#vQ2!mZ8$pL4x", null);
        assertEquals(BreachStatus.NOT_PRESENT, r.breachStatus());
        assertEquals(0, r.diskProbes(), "a Bloom miss must not touch the disk store");
        assertTrue(r.patterns().isEmpty());
        assertEquals(4, r.score());
    }

    @Test
    void bloomFalsePositiveIsCaughtByExactCheck() throws IOException {
        BloomFilter saturated = new BloomFilter(1, 1);
        saturated.add(new byte[]{1});
        PasswordIndex fpIndex = new PasswordIndex(saturated, index.store(), index.trie(), index.categorySizes(), 5);
        AuditResult r = new AuditService(fpIndex).audit("never-leaked-xyz", null);
        assertTrue(r.bloomHit());
        assertEquals(BreachStatus.BLOOM_FALSE_POSITIVE, r.breachStatus());
        assertNull(r.leakRank());
    }

    @Test
    void detectsLeetWordsYearsUsernameAndRepetition() {
        AuditResult leet = service.audit("Dr@g0n2019", null);
        assertEquals(List.of("dragon:leaked:true", "2019:year:false"),
                leet.patterns().stream().map(p -> p.token() + ":" + p.category() + ":" + p.leet()).toList());

        AuditResult user = service.audit("Alice2004!", "alice@example.com");
        assertTrue(user.containsUsername());
        assertEquals("username", user.patterns().get(0).category());

        AuditResult rep = service.audit("abcabcabc", null);
        assertEquals(new AuditResult.Repeat(0, "abc", 3), rep.repetition());
        assertEquals(new AuditResult.Repeat(11, "abc", 2), service.audit("Michael1990abcabc", null).repetition());
        assertNull(AuditService.longestRepeat("abcdefg"));
        assertNull(AuditService.longestRepeat("xaa"), "runs shorter than 4 characters are ignored");
    }

    @Test
    void usernamePartsAreMatched() {
        assertEquals(List.of("priya.nair", "priya", "nair"), AuditService.usernameTokens("Priya.Nair@college.edu"));
        assertTrue(service.audit("Nair#7788", "priya.nair").containsUsername());
        assertTrue(AuditService.usernameTokens("  ").isEmpty());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(InvalidInputException.class, () -> service.audit(null, null));
        assertThrows(InvalidInputException.class, () -> service.audit("", null));
        assertThrows(InvalidInputException.class, () -> service.audit("   ", null));
        assertThrows(InvalidInputException.class, () -> service.audit("a".repeat(129), null));
        assertThrows(InvalidInputException.class, () -> service.audit("abc\u0000def", null));
        assertThrows(InvalidInputException.class, () -> service.audit("fine-password", "u".repeat(65)));
    }

    @Test
    void boundaryLengthsAndShortUsernameIgnored() {
        assertEquals(1, service.audit("x", null).length());
        assertEquals(128, service.audit("a".repeat(128), null).length());
        assertFalse(service.audit("abpassword", "ab").containsUsername(), "usernames under 3 chars are ignored");
    }

    @Test
    void coverPrefersCheapestCombinationOverLongestMatch() {
        // Greedy longest-first would take "password2" and lose the year; the DP keeps both cheap pieces.
        List<AuditResult.Pattern> chosen = StrengthScorer.cheapestCover("password2019", List.of(
                new AuditResult.Pattern(0, 4, "pass", "english", false),
                new AuditResult.Pattern(0, 8, "password", "leaked", false),
                new AuditResult.Pattern(0, 9, "password2", "leaked", false),
                new AuditResult.Pattern(4, 8, "word", "english", false),
                new AuditResult.Pattern(8, 12, "2019", "year", false)),
                Map.of("leaked", 10_000, "english", 10_000, "year", 80));
        assertEquals(List.of("password", "2019"), chosen.stream().map(AuditResult.Pattern::token).toList());
    }
}
