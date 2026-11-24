package edu.atlas.pwaudit.store;

import edu.atlas.pwaudit.index.PasswordIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortedHashStoreTest {

    @TempDir
    Path dir;

    private SortedHashStore storeOf(String... passwords) throws IOException {
        List<SortedHashStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < passwords.length; i++) entries.add(new SortedHashStore.Entry(PasswordIndex.sha1(passwords[i]), i + 1));
        Path file = dir.resolve("store.bin");
        SortedHashStore.build(entries, file);
        return SortedHashStore.open(file);
    }

    @Test
    void findsPresentAndRejectsAbsent() throws IOException {
        try (SortedHashStore store = storeOf("123456", "password", "qwerty")) {
            SortedHashStore.Lookup hit = store.lookup(PasswordIndex.sha1("password"));
            assertTrue(hit.found());
            assertEquals(2, hit.rank());
            assertFalse(store.lookup(PasswordIndex.sha1("not-leaked")).found());
        }
    }

    @Test
    void everyRecordIncludingFirstAndLastIsFound() throws IOException {
        String[] passwords = new String[1_000];
        for (int i = 0; i < passwords.length; i++) passwords[i] = "pw" + i;
        try (SortedHashStore store = storeOf(passwords)) {
            for (int i = 0; i < passwords.length; i++) {
                assertEquals(i + 1, store.lookup(PasswordIndex.sha1(passwords[i])).rank());
            }
        }
    }

    @Test
    void probesAreLogarithmic() throws IOException {
        String[] passwords = new String[1 << 12];
        for (int i = 0; i < passwords.length; i++) passwords[i] = "p" + i;
        try (SortedHashStore store = storeOf(passwords)) {
            assertTrue(store.lookup(PasswordIndex.sha1("missing")).probes() <= 13);
        }
    }

    @Test
    void boundaryEmptyStore() throws IOException {
        try (SortedHashStore store = storeOf()) {
            assertEquals(0, store.size());
            SortedHashStore.Lookup miss = store.lookup(PasswordIndex.sha1("anything"));
            assertFalse(miss.found());
            assertEquals(0, miss.probes());
        }
    }

    @Test
    void duplicatesAreStoredOnceWithBestRank() throws IOException {
        try (SortedHashStore store = storeOf("dragon", "monkey", "dragon")) {
            assertEquals(2, store.size());
            assertEquals(1, store.lookup(PasswordIndex.sha1("dragon")).rank());
        }
    }

    @Test
    void corruptFileIsRejected() throws IOException {
        Path file = dir.resolve("bad.bin");
        Files.write(file, new byte[SortedHashStore.RECORD_BYTES + 3]);
        assertThrows(IOException.class, () -> SortedHashStore.open(file));
    }
}
