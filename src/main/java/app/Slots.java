package app;

/**
 * Таблица слотов соединений фиксированного размера. Один поток event loop.
 * Голова живого списка — самое давно молчащее соединение.
 */
final class Slots {
    private final long[] touched;
    private final int[] prev;
    private final int[] next;
    private final boolean[] closing;
    private int freeHead;
    private int liveHead = -1;
    private int liveTail = -1;
    private int count;

    /** Свободные слоты сразу собраны в односвязный список. */
    Slots(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        touched = new long[capacity];
        prev = new int[capacity];
        next = new int[capacity];
        closing = new boolean[capacity];
        for (int i = 0; i < capacity - 1; i++) {
            next[i] = i + 1;
        }
        next[capacity - 1] = -1;
        freeHead = 0;
    }

    /** Занимает свободный слот и ставит его в хвост живого списка. -1, если мест нет. */
    int acquire() {
        int slot = freeHead;
        if (slot < 0) {
            return -1;
        }
        freeHead = next[slot];
        touched[slot] = 0;
        closing[slot] = false;
        linkTail(slot);
        count++;
        return slot;
    }

    /** Отмечает активность и переносит слот в хвост: голова остаётся самой старой. */
    void touch(int slot, long now) {
        touched[slot] = now;
        unlink(slot);
        linkTail(slot);
    }

    /** Возвращает слот в свободный список и снимает пометку закрытия. */
    void release(int slot) {
        unlink(slot);
        closing[slot] = false;
        next[slot] = freeHead;
        freeHead = slot;
        count--;
    }

    /** Число занятых слотов. */
    int count() {
        return count;
    }

    /**
     * Самые давно молчащие слоты, которые ещё не выбраны.
     * Соединение само не закрывает: вызывающий решает, что делать со слотом.
     */
    int collectIdle(long now, long idleFor, int max, int[] out) {
        int n = 0;
        for (int slot = liveHead; slot >= 0 && n < max; slot = next[slot]) {
            if (closing[slot]) {
                continue;
            }
            if (now - touched[slot] < idleFor) {
                break;
            }
            out[n++] = slot;
            closing[slot] = true;
        }
        return n;
    }

    /** Вставляет слот в хвост двусвязного списка живых соединений. */
    private void linkTail(int slot) {
        prev[slot] = liveTail;
        next[slot] = -1;
        if (liveTail >= 0) {
            next[liveTail] = slot;
        } else {
            liveHead = slot;
        }
        liveTail = slot;
    }

    /** Вынимает слот из живого списка, не трогая свободный список. */
    private void unlink(int slot) {
        int previous = prev[slot];
        int following = next[slot];
        if (previous >= 0) {
            next[previous] = following;
        } else {
            liveHead = following;
        }
        if (following >= 0) {
            prev[following] = previous;
        } else {
            liveTail = previous;
        }
    }
}
