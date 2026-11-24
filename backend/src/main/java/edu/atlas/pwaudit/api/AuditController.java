package edu.atlas.pwaudit.api;

import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.service.AuditResult;
import edu.atlas.pwaudit.service.AuditService;
import edu.atlas.pwaudit.service.BatchAuditService;
import edu.atlas.pwaudit.service.BenchmarkService;
import edu.atlas.pwaudit.service.InvalidInputException;
import edu.atlas.pwaudit.service.OrgSimulationService;
import edu.atlas.pwaudit.service.SecretRedactionService;
import edu.atlas.pwaudit.store.SortedHashStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuditController {

    public record AuditRequest(String password, String username) {}

    public record RedactRequest(String text) {}

    public record Stats(int leakedPasswords, long uniqueLeakedHashes, long bloomBits, int bloomHashes,
                        long bloomBytes, double bloomTheoreticalFpr, long storeBytes, int trieWords, int trieNodes,
                        Map<String, Integer> categorySizes) {}

    private final AuditService auditService;
    private final BatchAuditService batchAuditService;
    private final BenchmarkService benchmarkService;
    private final OrgSimulationService orgSimulationService;
    private final SecretRedactionService redactionService;
    private final PasswordIndex index;
    private final Path dataDir;

    public AuditController(AuditService auditService, BatchAuditService batchAuditService,
                           BenchmarkService benchmarkService, OrgSimulationService orgSimulationService,
                           SecretRedactionService redactionService, PasswordIndex index,
                           @Value("${pwaudit.data-dir}") String dataDir) {
        this.auditService = auditService;
        this.batchAuditService = batchAuditService;
        this.benchmarkService = benchmarkService;
        this.orgSimulationService = orgSimulationService;
        this.redactionService = redactionService;
        this.dataDir = Path.of(dataDir);
        this.index = index;
    }

    @PostMapping("/audit")
    public AuditResult audit(@RequestBody AuditRequest request) {
        if (request == null) throw new InvalidInputException("Request body is required.");
        return auditService.audit(request.password(), request.username());
    }

    @PostMapping("/audit/batch")
    public BatchAuditService.Report auditBatch(@RequestParam("file") MultipartFile file) throws IOException {
        return batchAuditService.audit(file.getBytes());
    }

    @PostMapping("/org/simulate")
    public OrgSimulationService.Result simulate(@RequestParam("users") MultipartFile users,
                                                @RequestParam(value = "access", required = false) MultipartFile access,
                                                @RequestParam(defaultValue = "sha1") String attacker,
                                                @RequestParam(defaultValue = "86400") long budgetSeconds,
                                                @RequestParam(required = false) String target,
                                                @RequestParam(required = false) String terms) throws IOException {
        return orgSimulationService.simulate(users.getBytes(), access == null ? null : access.getBytes(),
                attacker, budgetSeconds, target, terms);
    }

    @GetMapping("/org/demo")
    public OrgSimulationService.Demo demo() {
        return orgSimulationService.demo();
    }

    @PostMapping("/redact")
    public SecretRedactionService.Result redact(@RequestBody RedactRequest request) {
        if (request == null) throw new InvalidInputException("Request body is required.");
        return redactionService.redact(request.text());
    }

    @GetMapping(value = "/redact/sample", produces = "text/plain;charset=UTF-8")
    public String redactSample() throws IOException {
        return Files.readString(dataDir.resolve("redact-sample.txt"));
    }

    @GetMapping("/stats")
    public Stats stats() {
        return new Stats(index.leakedCount(), index.store().size(), index.bloom().bitCount(),
                index.bloom().hashCount(), index.bloom().memoryBytes(),
                Math.round(index.bloom().theoreticalFpr() * 1e6) / 1e6,
                index.store().size() * SortedHashStore.RECORD_BYTES,
                index.trie().size(), index.trie().nodeCount(), index.categorySizes());
    }

    @PostMapping("/benchmark")
    public BenchmarkService.Report benchmark() {
        return benchmarkService.run();
    }
}
