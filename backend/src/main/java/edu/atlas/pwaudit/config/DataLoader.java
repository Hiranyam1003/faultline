package edu.atlas.pwaudit.config;

import edu.atlas.pwaudit.index.IndexBuilder;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.index.Wordlists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the leaked-password list and dictionaries from the data directory once at startup. */
@Configuration
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);
    private static final int LEAKED_FRAGMENTS = 10_000;

    @Bean
    public Wordlists wordlists(@Value("${pwaudit.data-dir}") String dataDirSetting) throws IOException {
        Path dataDir = Path.of(dataDirSetting);
        Path leakedFile = dataDir.resolve("leaked-1M.txt");
        if (!Files.exists(leakedFile)) {
            throw new IllegalStateException("Leaked password list not found at " + leakedFile.toAbsolutePath()
                    + ". Run from the backend/ directory or set pwaudit.data-dir.");
        }
        List<String> leaked = Files.readAllLines(leakedFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isEmpty())
                .toList();
        Map<String, List<String>> dictionaries = new LinkedHashMap<>();
        dictionaries.put("keyboard", readDictionary(dataDir, "keyboard.txt"));
        dictionaries.put("year", readDictionary(dataDir, "years.txt"));
        dictionaries.put("name", readDictionary(dataDir, "names.txt"));
        dictionaries.put("english", readDictionary(dataDir, "english.txt"));
        return new Wordlists(leaked, dictionaries);
    }

    @Bean
    public PasswordIndex passwordIndex(Wordlists wordlists,
                                       @Value("${pwaudit.data-dir}") String dataDirSetting,
                                       @Value("${pwaudit.bloom-fpr}") double bloomFpr) throws IOException {
        long started = System.nanoTime();
        Path dataDir = Path.of(dataDirSetting);
        Path leakedFile = dataDir.resolve("leaked-1M.txt");
        List<String> leaked = wordlists.leaked();
        Map<String, List<String>> raw = wordlists.dictionaries();

        Map<String, List<String>> dictionaries = new LinkedHashMap<>();
        dictionaries.put("keyboard", raw.get("keyboard"));
        dictionaries.put("year", raw.get("year"));
        dictionaries.put("leaked", leaked.subList(0, Math.min(LEAKED_FRAGMENTS, leaked.size())));
        dictionaries.put("name", raw.get("name"));
        dictionaries.put("english", raw.get("english"));

        Path storeFile = dataDir.resolve("cache/leaked-store.bin");
        boolean rebuild = !Files.exists(storeFile)
                || Files.getLastModifiedTime(storeFile).compareTo(Files.getLastModifiedTime(leakedFile)) < 0;

        PasswordIndex index = IndexBuilder.build(leaked, dictionaries, storeFile, bloomFpr, rebuild);
        log.info("Loaded {} leaked passwords ({} unique in store), bloom m={} k={} ({} KB), trie {} words in {} ms",
                index.leakedCount(), index.store().size(), index.bloom().bitCount(), index.bloom().hashCount(),
                index.bloom().memoryBytes() / 1024, index.trie().size(), (System.nanoTime() - started) / 1_000_000);
        return index;
    }

    private static List<String> readDictionary(Path dataDir, String name) throws IOException {
        return Files.readAllLines(dataDir.resolve("dictionary").resolve(name), StandardCharsets.UTF_8);
    }
}
