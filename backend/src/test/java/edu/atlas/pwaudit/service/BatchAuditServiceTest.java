package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.index.IndexBuilder;
import edu.atlas.pwaudit.service.AuditResult.BreachStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchAuditServiceTest {

    @TempDir
    Path dir;

    private BatchAuditService batch;

    @BeforeEach
    void setUp() throws IOException {
        List<String> leaked = List.of("123456", "password", "qwerty");
        batch = new BatchAuditService(new AuditService(
                IndexBuilder.build(leaked, Map.of("leaked", leaked), dir.resolve("store.bin"), 0.01, true)));
    }

    private BatchAuditService.Report run(String content) {
        return batch.audit(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void plainListCountsBreachesAndReuse() {
        BatchAuditService.Report r = run("123456\npassword\nUnique#Strong#2026x\n123456\n");
        assertEquals(4, r.totalRows());
        assertEquals(4, r.audited());
        assertEquals(3, r.confirmedBreached());
        assertEquals(2, r.reusedRows());
        assertEquals(1, r.distinctReusedPasswords());
        assertEquals(BreachStatus.NOT_PRESENT, r.rows().get(2).breachStatus());
    }

    @Test
    void csvWithUsernamesAndMalformedRows() {
        BatchAuditService.Report r = run("username,password\r\nalice,alice123\nbob\ncarol,\ndave,qwerty\n");
        assertEquals(2, r.audited());
        assertEquals(List.of(3, 4), r.invalidRows().stream().map(BatchAuditService.InvalidRow::line).toList());
        assertEquals("alice", r.rows().get(0).username());
        assertTrue(r.rows().get(0).categories().contains("username"));
    }

    @Test
    void passwordsAreMaskedInOutput() {
        BatchAuditService.Report r = run("secretvalue\n");
        assertEquals("•••••••••••", r.rows().get(0).masked());
        assertFalse(r.toString().contains("secretvalue"));
    }

    @Test
    void boundaryEmptyAndBlankFilesRejected() {
        assertThrows(InvalidInputException.class, () -> batch.audit(new byte[0]));
        assertThrows(InvalidInputException.class, () -> run("\n\n\n"));
        assertThrows(InvalidInputException.class, () -> run("username,password\n"));
    }

    @Test
    void oversizedAndNonUtf8FilesRejected() {
        assertThrows(InvalidInputException.class, () -> run("x\n".repeat(10_001)));
        assertThrows(InvalidInputException.class, () -> batch.audit(new byte[1_000_001]));
        assertThrows(InvalidInputException.class, () -> batch.audit(new byte[]{(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    void invalidRowInsidePlainListIsReportedNotFatal() {
        BatchAuditService.Report r = run("good-one\n" + "a".repeat(200) + "\n   \n");
        assertEquals(1, r.audited());
        assertEquals(2, r.invalidRows().size());
        assertEquals(3, r.totalRows());
    }
}
