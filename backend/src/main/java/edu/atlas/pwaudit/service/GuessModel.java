package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.GuessGenerator.Base;
import edu.atlas.pwaudit.algo.GuessGenerator.Rule;
import edu.atlas.pwaudit.index.Wordlists;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The attacker's model: which base words to try and how to mangle them, with rough probabilities.
 *
 * Base sources share the probability mass: leaked passwords 55% (Zipf by frequency rank, so rank r
 * gets weight 1/r), the organisation's own terms 10%, first names 15%, English words 12%, keyboard
 * walks 5% and years 3%; words are uniform inside the other sources. Rule weights are hand-set to
 * follow the shapes common in leaked data (append 1/123/!, Capitalise, append a year). This is a
 * simplified PCFG-style model for a teaching tool, not a tuned cracker.
 */
final class GuessModel {

    private static final int FIRST_YEAR = 1970;
    private static final int LAST_YEAR = 2026;

    private final List<Base> standardBases;

    GuessModel(Wordlists wordlists) {
        List<Base> bases = new ArrayList<>();
        List<String> leaked = wordlists.leaked();
        double harmonic = 0;
        for (int r = 1; r <= leaked.size(); r++) harmonic += 1.0 / r;
        for (int r = 1; r <= leaked.size(); r++) bases.add(new Base(leaked.get(r - 1), 0.55 / (r * harmonic), "leaked", r));
        addUniform(bases, wordlists.dictionaries().get("name"), 0.15, "name");
        addUniform(bases, wordlists.dictionaries().get("english"), 0.12, "english");
        addUniform(bases, wordlists.dictionaries().get("keyboard"), 0.05, "keyboard");
        addUniform(bases, wordlists.dictionaries().get("year"), 0.03, "year");
        standardBases = List.copyOf(bases);
    }

    /** The standard bases plus the organisation's own terms (company name, city, product names). */
    List<Base> bases(List<String> orgTerms) {
        List<Base> all = new ArrayList<>(standardBases.size() + orgTerms.size());
        addUniform(all, orgTerms, 0.10, "org");
        all.addAll(standardBases);
        return all;
    }

    static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("as is", 30, w -> w));
        rules.add(new Rule("Capitalised", 8, GuessModel::capitalise));
        rules.add(new Rule("upper case", 1, w -> w.toUpperCase(Locale.ROOT)));
        rules.add(new Rule("leetspeak", 2, GuessModel::leet));
        rules.add(new Rule("Capitalised + leetspeak", 1, w -> leet(capitalise(w))));
        String[][] suffixes = {{"1", "6"}, {"123", "5"}, {"!", "3"}, {"12", "2"}, {"@123", "2"}, {"1234", "1"},
                {"#", "0.5"}, {"@", "0.8"}, {"007", "0.5"}, {"!!", "0.5"}};
        for (String[] s : suffixes) {
            double weight = Double.parseDouble(s[1]);
            rules.add(suffix("+ \"" + s[0] + "\"", weight, s[0], false));
            rules.add(suffix("Capitalised + \"" + s[0] + "\"", weight * 0.6, s[0], true));
        }
        for (int d = 0; d <= 99; d++) {
            String digits = d < 10 ? String.valueOf(d) : String.format("%02d", d);
            if (digits.equals("1") || digits.equals("12")) continue;
            rules.add(suffix("+ 2 digits", d < 10 ? 0.6 : 0.15, digits, false));
            rules.add(suffix("Capitalised + 2 digits", d < 10 ? 0.3 : 0.08, digits, true));
        }
        for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
            double recent = y >= 1985 ? 1.0 : 0.5;
            String year = String.valueOf(y);
            rules.add(suffix("+ year", 0.2 * recent, year, false));
            rules.add(suffix("Capitalised + year", 0.15 * recent, year, true));
            rules.add(suffix("Capitalised + year + \"!\"", 0.08 * recent, year + "!", true));
            rules.add(suffix("Capitalised + \"@\" + year", 0.08 * recent, "@" + year, true));
        }
        return rules;
    }

    private static Rule suffix(String name, double weight, String suffix, boolean capitalise) {
        UnaryOperator<String> transform = !capitalise ? w -> w + suffix : w -> {
            String c = capitalise(w);
            return c == null ? null : c + suffix;
        };
        return new Rule(name, weight, transform);
    }

    private static void addUniform(List<Base> bases, List<String> words, double mass, String source) {
        List<String> clean = new ArrayList<>();
        for (String raw : words) {
            String w = raw.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty()) clean.add(w);
        }
        for (int i = 0; i < clean.size(); i++) bases.add(new Base(clean.get(i), mass / clean.size(), source, i + 1));
    }

    static String capitalise(String w) {
        if (w.isEmpty() || !Character.isLowerCase(w.charAt(0))) return null;
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    static String leet(String w) {
        if (w == null) return null;
        char[] out = w.toCharArray();
        boolean changed = false;
        for (int i = 0; i < out.length; i++) {
            char c = switch (out[i]) {
                case 'a' -> '@';
                case 'o' -> '0';
                case 'e' -> '3';
                case 's' -> '$';
                case 'i' -> '1';
                default -> out[i];
            };
            changed |= c != out[i];
            out[i] = c;
        }
        return changed ? new String(out) : null;
    }
}
