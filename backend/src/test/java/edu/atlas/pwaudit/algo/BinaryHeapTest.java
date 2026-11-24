package edu.atlas.pwaudit.algo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BinaryHeapTest {

    private static BinaryHeap<Integer> maxHeap() {
        return new BinaryHeap<>(Comparator.naturalOrder());
    }

    @Test
    void pollsLargestFirst() {
        BinaryHeap<Integer> heap = maxHeap();
        for (int x : new int[]{5, 1, 9, 3, 7}) heap.insert(x);
        assertEquals(9, heap.peek());
        List<Integer> out = new ArrayList<>();
        while (!heap.isEmpty()) out.add(heap.poll());
        assertEquals(List.of(9, 7, 5, 3, 1), out);
    }

    @Test
    void keepsDuplicates() {
        BinaryHeap<Integer> heap = maxHeap();
        for (int x : new int[]{4, 4, 2, 4}) heap.insert(x);
        assertEquals(4, heap.size());
        assertEquals(4, heap.poll());
        assertEquals(4, heap.poll());
        assertEquals(4, heap.poll());
        assertEquals(2, heap.poll());
    }

    @Test
    void emptyHeapThrows() {
        BinaryHeap<Integer> heap = maxHeap();
        assertThrows(NoSuchElementException.class, heap::poll);
        assertThrows(NoSuchElementException.class, heap::peek);
        heap.insert(1);
        heap.poll();
        assertThrows(NoSuchElementException.class, heap::poll);
    }

    @Test
    void growsPastInitialCapacity() {
        BinaryHeap<Integer> heap = maxHeap();
        for (int i = 0; i < 10_000; i++) heap.insert(i);
        assertEquals(10_000, heap.size());
        assertEquals(9_999, heap.poll());
    }

    @Test
    void matchesSortingOnRandomInput() {
        Random random = new Random(1);
        BinaryHeap<Integer> heap = maxHeap();
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int v = random.nextInt(1_000);
            values.add(v);
            heap.insert(v);
        }
        values.sort(Comparator.reverseOrder());
        for (int expected : values) assertEquals(expected, heap.poll());
    }

    @Test
    void interleavedInsertAndPoll() {
        BinaryHeap<Integer> heap = maxHeap();
        heap.insert(3);
        heap.insert(8);
        assertEquals(8, heap.poll());
        heap.insert(5);
        heap.insert(1);
        assertEquals(5, heap.poll());
        assertEquals(3, heap.poll());
        assertEquals(1, heap.poll());
    }
}
