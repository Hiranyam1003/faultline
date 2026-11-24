package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.GuessGenerator;
import edu.atlas.pwaudit.algo.GuessGenerator.Guess;
import edu.atlas.pwaudit.algo.GuessGenerator.Rule;
import edu.atlas.pwaudit.algo.MaxFlow;
import edu.atlas.pwaudit.index.Wordlists;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Attack simulation for an organisation, in two chained stages.
 *
 * 1. Cracking race: the attacker sees only SHA-1 hashes of the uploaded passwords and works through
 *    guesses in decreasing probability ({@link GuessGenerator}). Each account's crack time is its guess
 *    number divided by the attacker's guess rate. Accounts the race does not reach get the audit's
 *    entropy estimate (2^bits guesses), always later than the point the race already ruled out.
 * 2. Attack graph: accounts cracked within the time budget are entry points. Access edges (user →
 *    system, system → system, system → user) say where an attacker can move next. Each account is a
 *    vertex split into in → out with capacity 1, the cost of securing it (reset + MFA + revoke
 *    sessions); systems and edges are uncuttable. Edmonds–Karp max-flow from a super-source joined
 *    to every entry account, to the target, gives the minimum number of accounts to secure, and the
 *    residual graph's min cut names them.
 */
@Service
public class OrgSimulationService {

    static final int MAX_USERS = 5_000;
    static final int MAX_EDGES = 50_000;
    static final int MAX_FILE_BYTES = 1_000_000;
    static final long MAX_GUESSES = 5_000_000;
    static final int MAX_TERMS = 20;
    static final long MAX_BUDGET_SECONDS = 10L * 365 * 24 * 3600;
    static final double REPEAT_FILTER_FPR = 0.001;
    static final Map<String, Double> ATTACKERS = Map.of("online", 10.0, "bcrypt", 1e4, "sha1", 1e10);
    private static final List<Rule> RULES = GuessModel.rules();

    public record Account(String username, String role, String department, boolean admin, String masked,
                          double crackSeconds, boolean estimated, String crackedBy, String breachStatus,
                          boolean compromised, boolean fix) {}

    public record Race(String attacker, double guessesPerSecond, long budgetSeconds, long guessesTried,
                       long repeatsSkipped, long repeatFilterBytes, double millis, int crackedByRace,
                       int compromised) {}

    public record Node(String id, boolean user, boolean entry, boolean fix, boolean target, Double crackSeconds) {}

    public record Edge(String from, String to) {}

    public record Cut(String target, int accountsToFix, List<String> fix, int compromisedWithAccess,
                      int accountsWithAccess, Double secondsToTargetBefore, Double secondsToTargetAfter,
                      int augmentingPaths, double flowMillis, List<Node> nodes, List<Edge> edges) {}

    public record Warning(String file, int line, String reason) {}

    public record Result(int users, List<String> orgTerms, Race race, List<Account> accounts, List<String> targets,
                         Cut cut, List<Warning> warnings) {}

    public record Demo(String users, String access, String orgTerms, String target) {}

    private record User(int line, String username, String role, String department, String password) {}

    private final AuditService auditService;
    private final GuessModel model;
    private final Path dataDir;

    public OrgSimulationService(AuditService auditService, Wordlists wordlists,
                                @Value("${pwaudit.data-dir}") String dataDir) {
        this.auditService = auditService;
        this.model = new GuessModel(wordlists);
        this.dataDir = Path.of(dataDir);
    }

    public Demo demo() {
        try {
            Path dir = dataDir.resolve("org-demo");
            return new Demo(Files.readString(dir.resolve("users.csv")), Files.readString(dir.resolve("access.csv")),
                    "kestrel, pune", "Payroll DB");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Result simulate(byte[] usersCsv, byte[] accessCsv, String attacker, long budgetSeconds,
                           String target, String orgTerms) {
        Double rate = attacker == null ? null : ATTACKERS.get(attacker);
        if (rate == null) throw new InvalidInputException("Attacker must be one of online, bcrypt or sha1.");
        if (budgetSeconds < 1 || budgetSeconds > MAX_BUDGET_SECONDS) {
            throw new InvalidInputException("Time budget must be between 1 second and 10 years.");
        }
        List<String> terms = parseTerms(orgTerms);
        List<Warning> warnings = new ArrayList<>();
        List<User> users = parseUsers(usersCsv, warnings);

        int n = users.size();
        double[] entropy = new double[n];
        String[] breach = new String[n];
        for (int i = 0; i < n; i++) {
            AuditResult r = auditService.audit(users.get(i).password(), users.get(i).username());
            entropy[i] = r.entropyBits();
            breach[i] = r.breachStatus().name();
        }

        long started = System.nanoTime();
        long guessBudget = (long) Math.min(MAX_GUESSES, Math.ceil(budgetSeconds * rate));
        long[] crackedAt = new long[n];
        Guess[] crackedBy = new Guess[n];
        GuessGenerator generator = race(users, terms, guessBudget, crackedAt, crackedBy);
        double raceMillis = (System.nanoTime() - started) / 1e6;
        double raceEndSeconds = generator.produced() / rate;

        double[] crackSeconds = new double[n];
        int crackedByRace = 0, compromised = 0;
        for (int i = 0; i < n; i++) {
            if (crackedBy[i] != null) {
                crackSeconds[i] = crackedAt[i] / rate;
                crackedByRace++;
            } else {
                crackSeconds[i] = Math.max(Math.pow(2, entropy[i]) / rate, Math.nextUp(raceEndSeconds));
            }
            if (crackSeconds[i] <= budgetSeconds) compromised++;
        }

        Set<Integer> fix = new HashSet<>();
        List<String> targets = List.of();
        Cut cut = null;
        List<String[]> edges = parseAccess(accessCsv, warnings);
        if (!edges.isEmpty()) {
            Graph graph = new Graph(users.stream().map(User::username).toList(), edges);
            targets = graph.targets();
            cut = attackGraph(graph, target, crackSeconds, budgetSeconds, users, fix);
        }

        List<Account> accounts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            User u = users.get(i);
            accounts.add(new Account(u.username(), u.role(), u.department(), isAdmin(u.role()),
                    "•".repeat(Math.min(u.password().length(), 16)), crackSeconds[i], crackedBy[i] == null,
                    describe(crackedBy[i]), breach[i], crackSeconds[i] <= budgetSeconds, fix.contains(i)));
        }
        accounts.sort(Comparator.comparingDouble(Account::crackSeconds));
        warnings.sort(Comparator.comparing(Warning::file).thenComparingInt(Warning::line));

        Race race = new Race(attacker, rate, budgetSeconds, generator.produced(), generator.skippedRepeats(),
                generator.filterBytes(), Math.round(raceMillis * 10) / 10.0, crackedByRace, compromised);
        return new Result(n, terms, race, accounts, targets, cut, warnings);
    }

    /** Runs guesses until the budget is spent or every account has fallen; fills crackedAt/crackedBy. */
    private GuessGenerator race(List<User> users, List<String> terms, long guessBudget,
                                long[] crackedAt, Guess[] crackedBy) {
        MessageDigest sha1 = sha1();
        Map<Long, List<Integer>> byPrefix = new HashMap<>();
        byte[][] hashes = new byte[users.size()][];
        for (int i = 0; i < users.size(); i++) {
            hashes[i] = sha1.digest(users.get(i).password().getBytes(StandardCharsets.UTF_8));
            byPrefix.computeIfAbsent(prefix(hashes[i]), k -> new ArrayList<>()).add(i);
        }
        GuessGenerator generator = new GuessGenerator(model.bases(terms), RULES, guessBudget, REPEAT_FILTER_FPR);
        int remaining = users.size();
        while (remaining > 0 && generator.produced() < guessBudget) {
            Guess guess = generator.next();
            if (guess == null) break;
            byte[] hash = sha1.digest(guess.text().getBytes(StandardCharsets.UTF_8));
            List<Integer> candidates = byPrefix.get(prefix(hash));
            if (candidates == null) continue;
            for (int i : candidates) {
                if (crackedBy[i] == null && Arrays.equals(hashes[i], hash)) {
                    crackedBy[i] = guess;
                    crackedAt[i] = generator.produced();
                    remaining--;
                }
            }
        }
        return generator;
    }

    private Cut attackGraph(Graph graph, String targetName, double[] crackSeconds, long budgetSeconds,
                            List<User> users, Set<Integer> fixOut) {
        int target = graph.find(targetName);
        int nodes = graph.names.size();

        boolean[] entry = new boolean[nodes];
        for (int v = 0; v < nodes; v++) entry[v] = graph.userOf[v] >= 0 && crackSeconds[graph.userOf[v]] <= budgetSeconds;
        MaxFlow flow = flowNetwork(graph, entry);
        int sink = graph.sink(target);

        long started = System.nanoTime();
        long accountsToFix = flow.edmondsKarp(0, sink);
        double flowMillis = (System.nanoTime() - started) / 1e6;
        boolean[] sourceSide = flow.minCutSourceSide(0);

        List<String> fix = new ArrayList<>();
        Set<Integer> fixNodes = new HashSet<>();
        for (int v = 0; v < nodes; v++) {
            if (graph.userOf[v] >= 0 && sourceSide[in(v)] && !sourceSide[out(v)]) {
                fix.add(graph.names.get(v));
                fixNodes.add(v);
                fixOut.add(graph.userOf[v]);
            }
        }

        boolean[] canReachTarget = graph.reach(List.of(target), graph.reverse, Set.of());
        boolean[] canReachAfterFix = graph.reach(List.of(target), graph.reverse, fixNodes);
        List<Integer> entries = new ArrayList<>();
        for (int v = 0; v < nodes; v++) if (entry[v]) entries.add(v);
        boolean[] reachedByAttacker = graph.reach(entries, graph.forward, Set.of());

        int compromisedWithAccess = 0, withAccess = 0;
        Double before = null, after = null;
        for (int v = 0; v < nodes; v++) {
            int user = graph.userOf[v];
            if (user < 0 || !canReachTarget[v]) continue;
            withAccess++;
            if (entry[v]) compromisedWithAccess++;
            double t = crackSeconds[user];
            if (before == null || t < before) before = t;
            if (canReachAfterFix[v] && !fixNodes.contains(v) && (after == null || t < after)) after = t;
        }

        List<Node> shownNodes = new ArrayList<>();
        Set<Integer> shown = new HashSet<>();
        for (int v = 0; v < nodes; v++) {
            if (!(canReachTarget[v] && reachedByAttacker[v]) && v != target) continue;
            shown.add(v);
            int user = graph.userOf[v];
            shownNodes.add(new Node(graph.names.get(v), user >= 0, entry[v], fixNodes.contains(v), v == target,
                    user >= 0 ? crackSeconds[user] : null));
        }
        List<Edge> shownEdges = new ArrayList<>();
        for (int v : shown) {
            for (int w : graph.forward.get(v)) {
                if (shown.contains(w)) shownEdges.add(new Edge(graph.names.get(v), graph.names.get(w)));
            }
        }
        return new Cut(graph.names.get(target), (int) accountsToFix, fix, compromisedWithAccess, withAccess,
                before, after, flow.augmentingPaths(), Math.round(flowMillis * 1000) / 1000.0, shownNodes, shownEdges);
    }

    /**
     * Vertex 0 is the super-source; node v becomes in = 1 + 2v and out = 2 + 2v, joined by capacity 1
     * for an account and ∞ for a system. The source feeds every entry account; access edges are ∞.
     */
    static MaxFlow flowNetwork(Graph graph, boolean[] entry) {
        int nodes = graph.names.size();
        MaxFlow flow = new MaxFlow(1 + 2 * nodes);
        for (int v = 0; v < nodes; v++) {
            flow.addEdge(in(v), out(v), graph.userOf[v] >= 0 ? 1 : MaxFlow.INF);
            if (entry[v]) flow.addEdge(0, in(v), MaxFlow.INF);
        }
        for (int v = 0; v < nodes; v++) for (int w : graph.forward.get(v)) flow.addEdge(out(v), in(w), MaxFlow.INF);
        return flow;
    }

    private static int in(int v) { return 1 + 2 * v; }

    private static int out(int v) { return 2 + 2 * v; }

    /** Node-level access graph: users from users.csv, every other name in access.csv is a system. */
    static final class Graph {
        final List<String> names = new ArrayList<>();
        final List<List<Integer>> forward = new ArrayList<>();
        final List<List<Integer>> reverse = new ArrayList<>();
        int[] userOf;
        private final Map<String, Integer> ids = new HashMap<>();

        Graph(List<String> usernames, List<String[]> edges) {
            Map<String, Integer> userIndex = new HashMap<>();
            for (int i = 0; i < usernames.size(); i++) userIndex.put(key(usernames.get(i)), i);
            List<Integer> owner = new ArrayList<>();
            for (String[] e : edges) {
                for (String name : e) {
                    if (ids.containsKey(key(name))) continue;
                    Integer user = userIndex.get(key(name));
                    ids.put(key(name), names.size());
                    names.add(user != null ? usernames.get(user) : name);
                    owner.add(user != null ? user : -1);
                    forward.add(new ArrayList<>());
                    reverse.add(new ArrayList<>());
                }
                int from = ids.get(key(e[0])), to = ids.get(key(e[1]));
                forward.get(from).add(to);
                reverse.get(to).add(from);
            }
            userOf = owner.stream().mapToInt(Integer::intValue).toArray();
        }

        /** A target account is cut through its own in → out edge, so the sink is its out vertex. */
        int sink(int target) {
            return userOf[target] >= 0 ? out(target) : in(target);
        }

        /** Systems, those with no outgoing access (likely crown jewels) first. */
        List<String> targets() {
            List<String> sinks = new ArrayList<>(), others = new ArrayList<>();
            for (int v = 0; v < names.size(); v++) {
                if (userOf[v] >= 0) continue;
                (forward.get(v).isEmpty() ? sinks : others).add(names.get(v));
            }
            sinks.addAll(others);
            return sinks;
        }

        int find(String name) {
            if (name == null || name.isBlank()) {
                List<String> targets = targets();
                if (targets.isEmpty()) throw new InvalidInputException("The access file has no systems to protect.");
                return ids.get(key(targets.get(0)));
            }
            Integer id = ids.get(key(name));
            if (id == null) throw new InvalidInputException("Target '" + name.trim() + "' is not in the access file.");
            return id;
        }

        /** BFS from the starts; blocked nodes are neither entered nor passed through. */
        boolean[] reach(List<Integer> starts, List<List<Integer>> adjacency, Set<Integer> blocked) {
            boolean[] seen = new boolean[names.size()];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int s : starts) {
                seen[s] = true;
                queue.add(s);
            }
            while (!queue.isEmpty()) {
                for (int w : adjacency.get(queue.poll())) {
                    if (!seen[w] && !blocked.contains(w)) {
                        seen[w] = true;
                        queue.add(w);
                    }
                }
            }
            return seen;
        }

        private static String key(String name) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }

    private List<User> parseUsers(byte[] content, List<Warning> warnings) {
        if (content == null || content.length == 0) throw new InvalidInputException("The users file is empty.");
        if (content.length > MAX_FILE_BYTES) throw new InvalidInputException("The users file is larger than 1 MB.");
        String[] lines = stripBom(BatchAuditService.decodeUtf8(content)).split("\r?\n", -1);
        List<String> header = Arrays.stream(lines[0].split(",", -1)).map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        int nameCol = header.indexOf("username"), passCol = header.indexOf("password");
        int roleCol = header.indexOf("role"), deptCol = header.indexOf("department");
        if (nameCol < 0 || passCol < 0) {
            throw new InvalidInputException("The users file needs a header with username and password columns, e.g. username,role,department,password.");
        }

        List<User> users = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            int line = i + 1;
            String[] f = lines[i].split(",", header.size());
            if (f.length < header.size()) {
                warnings.add(new Warning("users", line, "Expected " + header.size() + " columns."));
                continue;
            }
            String username = f[nameCol].trim();
            String password = f[passCol];
            if (username.isEmpty()) {
                warnings.add(new Warning("users", line, "Username is empty."));
                continue;
            }
            if (!seen.add(username.toLowerCase(Locale.ROOT))) {
                warnings.add(new Warning("users", line, "Duplicate username '" + username + "'; the first row was kept."));
                continue;
            }
            try {
                AuditService.validatePassword(password);
                AuditService.usernameTokens(username);
            } catch (InvalidInputException e) {
                warnings.add(new Warning("users", line, e.getMessage()));
                continue;
            }
            if (users.size() == MAX_USERS) throw new InvalidInputException("The users file has more than " + MAX_USERS + " accounts.");
            users.add(new User(line, username, roleCol >= 0 ? f[roleCol].trim() : "",
                    deptCol >= 0 ? f[deptCol].trim() : "", password));
        }
        if (users.isEmpty()) throw new InvalidInputException("The users file contains no valid accounts.");
        return users;
    }

    private static List<String[]> parseAccess(byte[] content, List<Warning> warnings) {
        if (content == null || content.length == 0) return List.of();
        if (content.length > MAX_FILE_BYTES) throw new InvalidInputException("The access file is larger than 1 MB.");
        String[] lines = stripBom(BatchAuditService.decodeUtf8(content)).split("\r?\n", -1);
        if (!lines[0].trim().equalsIgnoreCase("from,to")) {
            throw new InvalidInputException("The access file must start with the header from,to.");
        }
        Map<String, String[]> edges = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            int line = i + 1;
            String[] f = lines[i].split(",", -1);
            if (f.length != 2 || f[0].isBlank() || f[1].isBlank()) {
                warnings.add(new Warning("access", line, "Expected 'from,to' with two names."));
                continue;
            }
            String from = f[0].trim(), to = f[1].trim();
            if (from.equalsIgnoreCase(to)) {
                warnings.add(new Warning("access", line, "'" + from + "' points to itself; skipped."));
                continue;
            }
            edges.putIfAbsent((from + "\u0000" + to).toLowerCase(Locale.ROOT), new String[]{from, to});
            if (edges.size() > MAX_EDGES) throw new InvalidInputException("The access file has more than " + MAX_EDGES + " edges.");
        }
        return new ArrayList<>(edges.values());
    }

    static List<String> parseTerms(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.length() < 2 || t.length() > 32 || !t.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
                throw new InvalidInputException("Organisation terms must be 2–32 visible ASCII characters, separated by commas.");
            }
            terms.add(t);
        }
        if (terms.size() > MAX_TERMS) throw new InvalidInputException("Use at most " + MAX_TERMS + " organisation terms.");
        return List.copyOf(terms);
    }

    private static String describe(Guess guess) {
        if (guess == null) return null;
        GuessGenerator.Base base = guess.base();
        String source = switch (base.source()) {
            case "leaked" -> "leaked password #" + base.rank();
            case "org" -> "company term";
            case "name" -> "first name";
            case "english" -> "English word";
            case "keyboard" -> "keyboard walk";
            case "year" -> "year";
            default -> base.source();
        };
        String rule = guess.rule().name();
        return rule.equals("as is") ? source : source + ", " + rule;
    }

    static boolean isAdmin(String role) {
        return role != null && role.toLowerCase(Locale.ROOT).contains("admin");
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static long prefix(byte[] hash) {
        long p = 0;
        for (int i = 0; i < 8; i++) p = (p << 8) | (hash[i] & 0xFF);
        return p;
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
