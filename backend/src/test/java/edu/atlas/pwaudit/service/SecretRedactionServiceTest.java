package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.index.IndexBuilder;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.service.SecretRedactionService.Finding;
import edu.atlas.pwaudit.service.SecretRedactionService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactionServiceTest {

    @TempDir
    Path dir;

    private SecretRedactionService service;

    @BeforeEach
    void setUp() throws IOException {
        List<String> leaked = List.of("123456", "password", "password123", "P@ssw0rd", "dragon");
        PasswordIndex index = IndexBuilder.build(leaked, Map.of("leaked", leaked), dir.resolve("store.bin"), 1e-6, true);
        service = new SecretRedactionService(index, new AuditService(index));
    }

    private List<String> kinds(Result r) {
        return r.findings().stream().map(Finding::kind).toList();
    }

    @Test
    void redactsValuesAfterCuesAndSeparators() {
        Result r = service.redact("VPN password for interns is Kestrel@2026\npwd: Summer2024!\nPIN = 4821\n");
        assertEquals("VPN password for interns is [PASSWORD-1]\npwd: [PASSWORD-2]\nPIN = [PIN-1]\n", r.redacted());
        assertEquals(List.of("PASSWORD", "PASSWORD", "PIN"), kinds(r));
        assertEquals(1, r.findings().get(1).line() - 1);
        assertEquals(6, r.findings().get(1).column());
        assertEquals("K•••••••••••", r.findings().get(0).masked());
    }

    @Test
    void sameSecretGetsTheSamePlaceholder() {
        Result r = service.redact("pwd: Summer2024!\nreplica uses the same pwd: Summer2024!");
        assertEquals(2, r.findings().size());
        assertEquals(r.findings().get(0).placeholder(), r.findings().get(1).placeholder());
        assertFalse(r.redacted().contains("Summer2024!"));
    }

    @Test
    void ignoresCueWordsThatCarryNoSecret() {
        String text = """
                The password reset link expired. The password policy is 12 characters.
                Our token expired yesterday. Secret santa closes Friday. Please pin the roadmap.
                Passwords must be rotated. Password: [REDACTED]. Pass the salt.
                """;
        Result r = service.redact(text);
        assertEquals(List.of(), r.findings());
        assertEquals(text, r.redacted());
    }

    @Test
    void redactsKnownKeyFormatsByPrefix() {
        Result r = service.redact("token ghp_K3str3lDemoT0kenNotReal0000000000aa and AKIAKESTRELDEMO00042 but not ghp_short");
        assertEquals(List.of("TOKEN", "API-KEY"), kinds(r));
        assertTrue(r.redacted().contains("ghp_short"));
    }

    @Test
    void flagsLeakedPasswordsWithoutACue() {
        Result r = service.redact("Has anyone tried P@ssw0rd or password123 on the printer? Not dragon though.");
        assertEquals(List.of("LEAKED-PASSWORD", "LEAKED-PASSWORD"), kinds(r));
        assertEquals("CONFIRMED", r.findings().get(1).breachStatus());
        assertTrue(r.redacted().contains("dragon"));
        assertEquals(2, r.stats().diskLookups());
    }

    @Test
    void leavesEmailsUrlsAndVersionNumbersAlone() {
        Result r = service.redact("Mail priya.nair@kestrel.example, see https://kestrel.example/reset2024 for v2.4.1 in Q3 2026.");
        assertEquals(List.of(), r.findings());
    }

    @Test
    void cueMustBeAWholeWord() {
        Result r = service.redact("The pinball spinner and passwords9 are fine; compass: north99");
        assertEquals(List.of(), r.findings());
    }

    @Test
    void rejectsEmptyAndOversizedInput() {
        assertThrows(InvalidInputException.class, () -> service.redact(null));
        assertThrows(InvalidInputException.class, () -> service.redact("   \n"));
        assertThrows(InvalidInputException.class, () -> service.redact("a".repeat(SecretRedactionService.MAX_CHARS + 1)));
    }
}
