package app;

/** Кольцо индексов готовой работы. Один поток. Ёмкость — степень двойки. */
final class Ring {
    private final int[] data;
    private final int mask;
    private int head;
    private int tail;
    private int size;

    /** Отказывается от ёмкости, которая не является степенью двойки: маска тогда не работает. */
    Ring(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        data = new int[capacity];
        mask = capacity - 1;
    }

    /** Кладёт индекс в хвост. false, если кольцо заполнено. */
    boolean offer(int value) {
        if (size == data.length) {
            return false;
        }
        data[tail] = value;
        tail = (tail + 1) & mask;
        size++;
        return true;
    }

    /** Следующий индекс в порядке поступления, или -1, если кольцо пусто. */
    int poll() {
        if (size == 0) {
            return -1;
        }
        int value = data[head];
        head = (head + 1) & mask;
        size--;
        return value;
    }

    /** Сколько индексов сейчас ждут обработки. */
    int size() {
        return size;
    }
}
