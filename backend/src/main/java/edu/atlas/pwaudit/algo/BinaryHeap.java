package edu.atlas.pwaudit.algo;

import java.util.Arrays;
import java.util.Comparator;
import java.util.NoSuchElementException;

/**
 * Array-backed binary heap. The element the comparator ranks highest is at the root, so
 * {@code new BinaryHeap<>(Comparator.naturalOrder())} is a max-heap.
 *
 * Children of index i live at 2i+1 and 2i+2. insert sifts up and poll sifts down, both O(log n);
 * peek is O(1). The array doubles when full, so n inserts cost O(n log n) in total.
 */
public final class BinaryHeap<T> {

    private final Comparator<? super T> order;
    private Object[] items = new Object[16];
    private int size;

    public BinaryHeap(Comparator<? super T> order) {
        this.order = order;
    }

    public void insert(T item) {
        if (size == items.length) items = Arrays.copyOf(items, size * 2);
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (order.compare(item, at(parent)) <= 0) break;
            items[i] = items[parent];
            i = parent;
        }
        items[i] = item;
    }

    public T peek() {
        if (size == 0) throw new NoSuchElementException("heap is empty");
        return at(0);
    }

    public T poll() {
        T top = peek();
        T last = at(--size);
        items[size] = null;
        if (size > 0) siftDown(last);
        return top;
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    private void siftDown(T item) {
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) break;
            if (child + 1 < size && order.compare(at(child + 1), at(child)) > 0) child++;
            if (order.compare(item, at(child)) >= 0) break;
            items[i] = items[child];
            i = child;
        }
        items[i] = item;
    }

    @SuppressWarnings("unchecked")
    private T at(int i) {
        return (T) items[i];
    }
}
