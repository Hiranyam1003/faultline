package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.index.IndexBuilder;
import edu.atlas.pwaudit.index.Wordlists;
import edu.atlas.pwaudit.service.OrgSimulationService.Account;
import edu.atlas.pwaudit.service.OrgSimulationService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OrgSimulationServiceTest {

    private static final long DAY = 86_400;
    private static final String STRONG = "tK9#vQ2!mZ8$pL4x";

    @TempDir
    Path dir;

    private OrgSimulationService service;

    @BeforeEach
    void setUp() throws IOException {
        List<String> leaked = List.of("123456", "password", "qwerty", "dragon", "password123");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("keyboard", List.of("qwerty", "asdfgh"));
        raw.put("year", List.of("2003", "2024"));
        raw.put("name", List.of("alice", "carol", "priya"));
        raw.put("english", List.of("summer", "dragon"));
        Map<String, List<String>> dictionaries = new LinkedHashMap<>(raw);
        dictionaries.put("leaked", leaked);
        AuditService audit = new AuditService(IndexBuilder.build(leaked, dictionaries, dir.resolve("store.bin"), 0.01, true));
        service = new OrgSimulationService(audit, new Wordlists(leaked, raw), "../data");
    }

    private Result run(String users, String access, String attacker, long budget, String target, String terms) {
        return service.simulate(bytes(users), access == null ? null : bytes(access), attacker, budget, target, terms);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Account> byName(Result r) {
        return r.accounts().stream().collect(Collectors.toMap(Account::username, a -> a));
    }

    @Test
    void cutsEveryCompromisedPathToTheTarget() {
        Result r = run("""
                username,role,department,password
                alice,staff,HR,password
                bob,staff,HR,%s
                carol,admin,IT,Carol2003!
                """.formatted(STRONG), """
                from,to
                alice,HR portal
                bob,HR portal
                HR portal,Payroll DB
                carol,Domain Admin
                Domain Admin,Payroll DB
                """, "online", DAY, "Payroll DB", null);

        Map<String, Account> a = byName(r);
        assertTrue(a.get("alice").compromised());
        assertTrue(a.get("carol").compromised());
        assertFalse(a.get("bob").compromised());
        assertTrue(a.get("bob").estimated());
        assertEquals("first name, Capitalised + year + \"!\"", a.get("carol").crackedBy());
        assertEquals(2, r.cut().accountsToFix());
        assertEquals(Set.of("alice", "carol"), Set.copyOf(r.cut().fix()));
        assertEquals(a.get("alice").crackSeconds(), r.cut().secondsToTargetBefore());
        assertEquals(a.get("bob").crackSeconds(), r.cut().secondsToTargetAfter());
    }

    @Test
    void securesOneChokePointInsteadOfEveryWeakAccount() {
        Result r = run("""
                username,role,department,password
                u1,staff,Eng,123456
                u2,staff,Eng,qwerty
                u3,staff,Eng,dragon
                admin,admin,IT,%s
                """.formatted(STRONG), """
                from,to
                u1,Dev server
                u2,Dev server
                u3,Dev server
                Dev server,admin
                admin,Payroll DB
                """, "sha1", DAY, "Payroll DB", null);
        assertEquals(3, r.race().compromised());
        assertEquals(1, r.cut().accountsToFix());
        assertEquals(List.of("admin"), r.cut().fix());
        assertEquals(3, r.cut().compromisedWithAccess());
    }

    @Test
    void targetOutOfReachNeedsNoFixes() {
        Result r = run("""
                username,password
                alice,%s
                weak,123456
                """.formatted(STRONG), """
                from,to
                alice,Payroll DB
                weak,Email
                """, "online", DAY, "Payroll DB", null);
        assertEquals(0, r.cut().accountsToFix());
        assertTrue(r.cut().secondsToTargetBefore() > DAY);
        assertEquals(List.of("Payroll DB", "Email"), r.targets());
    }

    @Test
    void organisationTermsAreGuessedOnlyWhenGiven() {
        String users = "username,password\nana,kestrel123\n";
        assertFalse(run(users, null, "online", DAY, null, null).accounts().get(0).compromised());
        Result withTerms = run(users, null, "online", DAY, null, "Kestrel, Pune");
        assertTrue(withTerms.accounts().get(0).compromised());
        assertEquals("company term, + \"123\"", withTerms.accounts().get(0).crackedBy());
        assertEquals(List.of("kestrel", "pune"), withTerms.orgTerms());
        assertNull(withTerms.cut());
    }

    @Test
    void longerBudgetNeverCompromisesFewerAccounts() {
        String users = "username,password\na,123456\nb,Priya2024\nc,summer77\nd," + STRONG + "\n";
        int previous = 0;
        for (long budget : new long[]{1, 60, 3_600, DAY, 365 * DAY}) {
            int compromised = run(users, null, "online", budget, null, null).race().compromised();
            assertTrue(compromised >= previous, "budget " + budget);
            previous = compromised;
        }
    }

    @Test
    void warnsAboutBadRowsAndKeepsGoodOnes() {
        Result r = run("""
                username,password
                alice,password
                alice,dragon
                ,qwerty
                bob
                """, """
                from,to
                alice,alice
                alice,Email
                alice,Email
                broken
                """, "online", DAY, "Email", null);
        assertEquals(1, r.users());
        assertEquals(List.of(2, 5, 3, 4, 5), r.warnings().stream().map(OrgSimulationService.Warning::line).toList());
        assertEquals(1, r.cut().accountsToFix());
    }

    @Test
    void rejectsInvalidInput() {
        String users = "username,password\nalice,password\n";
        String access = "from,to\nalice,Email\n";
        assertThrows(InvalidInputException.class, () -> run("", null, "online", DAY, null, null));
        assertThrows(InvalidInputException.class, () -> run("user,pass\na,b\n", null, "online", DAY, null, null));
        assertThrows(InvalidInputException.class, () -> run("username,password\n,\n", null, "online", DAY, null, null));
        assertThrows(InvalidInputException.class, () -> run(users, null, "quantum", DAY, null, null));
        assertThrows(InvalidInputException.class, () -> run(users, null, "online", 0, null, null));
        assertThrows(InvalidInputException.class, () -> run(users, null, "online", DAY, null, "k"));
        assertThrows(InvalidInputException.class, () -> run(users, "a;b\n", "online", DAY, null, null));
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> run(users, access, "online", DAY, "Mainframe", null));
        assertEquals("Target 'Mainframe' is not in the access file.", e.getMessage());
    }
}
