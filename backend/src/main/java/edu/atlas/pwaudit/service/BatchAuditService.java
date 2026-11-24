package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.service.AuditResult.BreachStatus;
import edu.atlas.pwaudit.service.AuditResult.Pattern;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Audits an uploaded file of passwords: either one password per line, or a CSV whose first line
 * is exactly "username,password". Passwords are never echoed back, only masked.
 */
@Service
public class BatchAuditService {

    static final int MAX_FILE_BYTES = 1_000_000;
    static final int MAX_ROWS = 10_000;
    private static final String CSV_HEADER = "username,password";

    public record Row(int line, String username, String masked, int length, BreachStatus breachStatus,
                      Integer leakRank, int score, String verdict, List<String> categories, boolean reused) {}

    public record InvalidRow(int line, String reason) {}

    public record Report(int totalRows, int audited, int confirmedBreached, int bloomHits, int bloomFalsePositives,
                         int reusedRows, int distinctReusedPasswords, double averageEntropyBits,
                         int[] scoreHistogram, Map<String, Integer> categoryCounts, long elapsedMillis,
                         List<Row> rows, List<InvalidRow> invalidRows) {}

    private record Parsed(int line, String username, String password) {}

    private final AuditService auditService;

    public BatchAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    public Report audit(byte[] content) {
        long started = System.nanoTime();
        if (content == null || content.length == 0) throw new InvalidInputException("The uploaded file is empty.");
        if (content.length > MAX_FILE_BYTES) throw new InvalidInputException("File is larger than 1 MB.");

        List<InvalidRow> invalid = new ArrayList<>();
        List<Parsed> parsed = parse(decodeUtf8(content), invalid);
        if (parsed.isEmpty() && invalid.isEmpty()) throw new InvalidInputException("The file contains no passwords.");
        int totalRows = parsed.size() + invalid.size();

        Map<String, Integer> occurrences = new HashMap<>();
        for (Parsed p : parsed) occurrences.merge(p.password(), 1, Integer::sum);

        List<Row> rows = new ArrayList<>();
        int breached = 0, bloomHits = 0, falsePositives = 0, reusedRows = 0;
        double entropySum = 0;
        int[] histogram = new int[5];
        Map<String, Integer> categoryCounts = new TreeMap<>();
        for (Parsed p : parsed) {
            AuditResult r;
            try {
                r = auditService.audit(p.password(), p.username());
            } catch (InvalidInputException e) {
                invalid.add(new InvalidRow(p.line(), e.getMessage()));
                continue;
            }
            LinkedHashSet<String> categories = new LinkedHashSet<>();
            for (Pattern pattern : r.patterns()) categories.add(pattern.category());
            if (r.repetition() != null) categories.add("repetition");
            for (String c : categories) categoryCounts.merge(c, 1, Integer::sum);

            boolean reused = occurrences.get(p.password()) > 1;
            if (reused) reusedRows++;
            if (r.bloomHit()) bloomHits++;
            if (r.breachStatus() == BreachStatus.CONFIRMED) breached++;
            if (r.breachStatus() == BreachStatus.BLOOM_FALSE_POSITIVE) falsePositives++;
            entropySum += r.entropyBits();
            histogram[r.score()]++;
            rows.add(new Row(p.line(), p.username(), "•".repeat(Math.min(r.length(), 16)), r.length(),
                    r.breachStatus(), r.leakRank(), r.score(), r.verdict(), List.copyOf(categories), reused));
        }
        invalid.sort((a, b) -> Integer.compare(a.line(), b.line()));
        int distinctReused = (int) occurrences.values().stream().filter(n -> n > 1).count();
        double avgEntropy = rows.isEmpty() ? 0 : Math.round(entropySum / rows.size() * 10) / 10.0;

        return new Report(totalRows, rows.size(), breached, bloomHits,
                falsePositives, reusedRows, distinctReused, avgEntropy, histogram, categoryCounts,
                (System.nanoTime() - started) / 1_000_000, rows, invalid);
    }

    private static List<Parsed> parse(String text, List<InvalidRow> invalid) {
        String[] lines = text.split("\r?\n", -1);
        int first = 0;
        if (lines.length > 0 && lines[0].startsWith("﻿")) lines[0] = lines[0].substring(1);
        boolean csv = lines.length > 0 && lines[0].trim().equalsIgnoreCase(CSV_HEADER);
        if (csv) first = 1;

        List<Parsed> result = new ArrayList<>();
        int nonEmpty = 0;
        for (int i = first; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (++nonEmpty > MAX_ROWS) throw new InvalidInputException("File has more than " + MAX_ROWS + " rows.");
            int lineNo = i + 1;
            if (!csv) {
                result.add(new Parsed(lineNo, null, line));
                continue;
            }
            int comma = line.indexOf(',');
            if (comma < 0) {
                invalid.add(new InvalidRow(lineNo, "Expected 'username,password'."));
                continue;
            }
            String username = line.substring(0, comma).trim();
            String password = line.substring(comma + 1);
            if (password.isEmpty()) {
                invalid.add(new InvalidRow(lineNo, "Password is empty."));
                continue;
            }
            result.add(new Parsed(lineNo, username.isEmpty() ? null : username, password));
        }
        return result;
    }

    static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidInputException("File must be UTF-8 text.");
        }
    }
}
