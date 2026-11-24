package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.index.Wordlists;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Seeded synthetic organisation ("Kestrel Labs", Pune): users with roles and departments, a realistic
 * mix of weak and strong passwords, and an access graph from people to systems. It produces the demo
 * files in data/org-demo/ and the scaled inputs for the max-flow benchmark.
 *
 * The weak spot is built in on purpose: the first IT admin's SSH key sits on the dev server, so every
 * engineer reaches Domain Admin, and from there Payroll DB, through that one account.
 */
public final class OrgDemoGenerator {

    public record Org(List<String[]> users, List<String[]> access) {
        public String usersCsv() {
            StringBuilder sb = new StringBuilder("username,role,department,password\n");
            for (String[] u : users) sb.append(String.join(",", u)).append('\n');
            return sb.toString();
        }

        public String accessCsv() {
            StringBuilder sb = new StringBuilder("from,to\n");
            for (String[] e : access) sb.append(e[0]).append(',').append(e[1]).append('\n');
            return sb.toString();
        }
    }

    private static final String[] SURNAMES = {"sharma", "nair", "mehta", "iyer", "rao", "patel", "reddy", "khan",
            "gupta", "das", "joshi", "kulkarni", "menon", "singh", "bose", "pillai", "desai", "chopra", "verma", "shetty"};

    /** Department → share of headcount and the systems everyone in it can reach. */
    private static final Map<String, Object[]> DEPARTMENTS = new LinkedHashMap<>();
    static {
        DEPARTMENTS.put("Engineering", new Object[]{0.36, List.of("Email", "VPN", "Git", "Dev server")});
        DEPARTMENTS.put("Sales", new Object[]{0.18, List.of("Email", "CRM")});
        DEPARTMENTS.put("Support", new Object[]{0.18, List.of("Email", "CRM", "Helpdesk")});
        DEPARTMENTS.put("HR", new Object[]{0.07, List.of("Email", "HR portal")});
        DEPARTMENTS.put("Finance", new Object[]{0.07, List.of("Email", "Finance app")});
        DEPARTMENTS.put("Leadership", new Object[]{0.05, List.of("Email", "VPN", "HR portal", "Finance app")});
        DEPARTMENTS.put("IT", new Object[]{0.09, List.of("Email", "VPN", "Helpdesk", "Domain Admin")});
    }

    /** Departments that had security training, so more of their passwords are strong. */
    private static final Set<String> SENSITIVE = Set.of("HR", "Finance", "Leadership");

    private static final String[][] SYSTEM_LINKS = {
            {"VPN", "Dev server"}, {"Git", "CI pipeline"}, {"CI pipeline", "Prod database"},
            {"HR portal", "Payroll DB"}, {"Finance app", "Payroll DB"}, {"Domain Admin", "Payroll DB"},
            {"Domain Admin", "Prod database"}};

    private OrgDemoGenerator() {}

    public static Org generate(int size, long seed, Wordlists wordlists) {
        Random random = new Random(seed);
        List<String> names = wordlists.dictionaries().get("name").stream()
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .filter(n -> n.matches("[a-z]{3,9}"))
                .toList();
        List<String> english = wordlists.dictionaries().get("english").stream()
                .map(w -> w.trim().toLowerCase(Locale.ROOT)).filter(w -> w.matches("[a-z]{4,8}")).toList();
        List<String> keyboard = wordlists.dictionaries().get("keyboard").stream()
                .map(String::trim).filter(w -> w.length() >= 6 && !w.contains(",")).toList();
        List<String> leaked = wordlists.leaked().subList(0, Math.min(20_000, wordlists.leaked().size())).stream()
                .filter(p -> !p.contains(",") && p.length() >= 6 && p.chars().allMatch(c -> c > 0x20 && c < 0x7F))
                .toList();

        List<String[]> users = new ArrayList<>();
        List<String[]> access = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        String firstAdmin = null;
        for (Map.Entry<String, Object[]> dept : DEPARTMENTS.entrySet()) {
            int headcount = Math.max(2, (int) Math.round(size * (double) dept.getValue()[0]));
            @SuppressWarnings("unchecked")
            List<String> systems = (List<String>) dept.getValue()[1];
            for (int i = 0; i < headcount; i++) {
                String first = names.get(random.nextInt(names.size()));
                String username = first + "." + SURNAMES[random.nextInt(SURNAMES.length)];
                for (int k = 2; !taken.add(username); k++) username = first + "." + SURNAMES[random.nextInt(SURNAMES.length)] + k;

                String role = switch (dept.getKey()) {
                    case "IT" -> "admin";
                    case "Leadership" -> "manager";
                    default -> i == 0 ? "manager" : "staff";
                };
                String password;
                if (dept.getKey().equals("IT")) {
                    if (firstAdmin == null) {
                        firstAdmin = username;
                        password = capitalise(first) + "@" + (1985 + random.nextInt(15));
                    } else {
                        password = strong(random, english);
                    }
                } else if (SENSITIVE.contains(dept.getKey()) && random.nextDouble() < 0.6) {
                    password = strong(random, english);
                } else {
                    password = weakOrStrong(random, first, leaked, english, keyboard);
                }
                users.add(new String[]{username, role, dept.getKey(), password});
                for (String system : systems) access.add(new String[]{username, system});
            }
        }
        for (String[] link : SYSTEM_LINKS) access.add(link);
        access.add(new String[]{"Dev server", firstAdmin});
        return new Org(users, access);
    }

    private static String weakOrStrong(Random random, String first, List<String> leaked, List<String> english,
                                       List<String> keyboard) {
        double roll = random.nextDouble();
        if (roll < 0.16) return leaked.get(random.nextInt(leaked.size()));
        if (roll < 0.34) {
            String year = String.valueOf(1980 + random.nextInt(25));
            return capitalise(first) + (random.nextBoolean() ? year : year + "!");
        }
        if (roll < 0.40) return random.nextBoolean() ? "Kestrel@" + (100 + random.nextInt(900)) : "kestrel" + random.nextInt(100);
        if (roll < 0.43) return "Pune" + (2015 + random.nextInt(12)) + "!";
        if (roll < 0.52) return capitalise(english.get(random.nextInt(english.size()))) + String.format("%02d", random.nextInt(100));
        if (roll < 0.56) return keyboard.get(random.nextInt(keyboard.size()));
        return strong(random, english);
    }

    private static String strong(Random random, List<String> english) {
        if (random.nextBoolean()) {
            String charset = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#%&*?";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 14; i++) sb.append(charset.charAt(random.nextInt(charset.length())));
            return sb.toString();
        }
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 4; i++) words.add(english.get(random.nextInt(english.size())));
        return String.join("-", words);
    }

    private static String capitalise(String w) {
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Writes data/org-demo/users.csv and access.csv. Usage: OrgDemoGenerator &lt;data-dir&gt;. */
    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "../data");
        Map<String, List<String>> dictionaries = new LinkedHashMap<>();
        for (String[] d : new String[][]{{"name", "names.txt"}, {"english", "english.txt"}, {"keyboard", "keyboard.txt"}}) {
            dictionaries.put(d[0], Files.readAllLines(dataDir.resolve("dictionary").resolve(d[1]), StandardCharsets.UTF_8));
        }
        List<String> leaked = Files.readAllLines(dataDir.resolve("leaked-1M.txt"), StandardCharsets.UTF_8)
                .stream().filter(l -> !l.isEmpty()).toList();
        Org org = generate(240, 2026, new Wordlists(leaked, dictionaries));
        Path out = Files.createDirectories(dataDir.resolve("org-demo"));
        Files.writeString(out.resolve("users.csv"), org.usersCsv());
        Files.writeString(out.resolve("access.csv"), org.accessCsv());
        System.out.println("Wrote " + org.users().size() + " users and " + org.access().size() + " access edges to " + out);
    }
}
