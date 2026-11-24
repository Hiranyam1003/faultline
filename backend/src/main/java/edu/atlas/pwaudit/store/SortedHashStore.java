package edu.atlas.pwaudit.store;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Authoritative, disk-backed set of SHA-1 password hashes.
 *
 * The file is a sorted array of fixed 24-byte records (20-byte SHA-1 + 4-byte frequency rank),
 * so membership is a binary search costing ceil(log2 n) positional disk reads. This is the
 * exact check that confirms or rejects every Bloom-filter hit.
 */
public final class SortedHashStore implements Closeable {

    public static final int HASH_BYTES = 20;
    public static final int RECORD_BYTES = HASH_BYTES + Integer.BYTES;

    /** A leaked password's hash and its 1-based position in the frequency-ordered source list. */
    public record Entry(byte[] hash, int rank) {}

    /** found = exact membership; rank is -1 when not found; probes = records read from disk. */
    public record Lookup(boolean found, int rank, int probes) {}

    private final FileChannel channel;
    private final long size;

    private SortedHashStore(FileChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    /** Sorts entries, drops duplicate hashes (keeping the best rank) and writes the store file. */
    public static void build(List<Entry> entries, Path file) throws IOException {
        Entry[] sorted = entries.toArray(new Entry[0]);
        Arrays.sort(sorted, Comparator.<Entry, byte[]>comparing(Entry::hash, Arrays::compareUnsigned)
                .thenComparingInt(Entry::rank));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            byte[] previous = null;
            for (Entry e : sorted) {
                if (e.hash().length != HASH_BYTES) throw new IllegalArgumentException("hash must be 20 bytes");
                if (previous != null && Arrays.equals(previous, e.hash())) continue;
                out.write(e.hash());
                out.writeInt(e.rank());
                previous = e.hash();
            }
        }
    }

    public static SortedHashStore open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        long bytes = channel.size();
        if (bytes % RECORD_BYTES != 0) {
            channel.close();
            throw new IOException("corrupt store file: size is not a multiple of " + RECORD_BYTES);
        }
        return new SortedHashStore(channel, bytes / RECORD_BYTES);
    }

    public Lookup lookup(byte[] hash) {
        ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES);
        byte[] candidate = new byte[HASH_BYTES];
        long lo = 0, hi = size - 1;
        int probes = 0;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            readRecord(mid, record);
            probes++;
            record.get(candidate);
            int cmp = Arrays.compareUnsigned(candidate, hash);
            if (cmp == 0) return new Lookup(true, record.getInt(), probes);
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return new Lookup(false, -1, probes);
    }

    private void readRecord(long index, ByteBuffer record) {
        record.clear();
        try {
            long position = index * RECORD_BYTES;
            while (record.hasRemaining()) {
                if (channel.read(record, position + record.position()) < 0) throw new IOException("unexpected end of store");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record.flip();
    }

    public long size() { return size; }

    @Override
    public void close() throws IOException { channel.close(); }
}
