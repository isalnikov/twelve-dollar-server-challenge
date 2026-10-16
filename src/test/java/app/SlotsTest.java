package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Слоты держат 40 000 соединений, переиспользуются и сбрасывают самых молчащих. */
class SlotsTest {

    @Test
    /** 40 000 слотов помещаются в таблицу на 65 536. */
    void holdsFortyThousandUsersUnderTheProcessFdCeiling() {
        Slots slots = new Slots(65_536);
        for (int i = 0; i < 40_000; i++) {
            assertTrue(slots.acquire() >= 0, "rejected user " + i);
        }
        assertEquals(40_000, slots.count());
        assertTrue(slots.acquire() >= 0);
    }

    @Test
    /** После release тот же номер слота можно занять снова. */
    void reusesASlotAfterTheUserDisconnects() {
        Slots slots = new Slots(4);
        int[] held = new int[4];
        for (int i = 0; i < 4; i++) {
            held[i] = slots.acquire();
        }
        assertEquals(-1, slots.acquire());
        slots.release(held[1]);
        assertTrue(slots.acquire() >= 0);
        assertEquals(-1, slots.acquire());
        assertEquals(4, slots.count());
    }

    @Test
    /** Сброс выбирает того, кто молчит дольше порога, и не трогает активного. */
    void shedsTheLongestIdleUserAndKeepsTheActiveOne() {
        Slots slots = new Slots(8);
        int idle = slots.acquire();
        int active = slots.acquire();
        slots.touch(idle, 1_000);
        slots.touch(active, 9_800);
        int[] out = new int[4];
        int n = slots.collectIdle(10_000, 500, 1, out);
        assertEquals(1, n);
        assertEquals(idle, out[0]);
        slots.release(idle);
        assertEquals(1, slots.count());
        assertTrue(slots.acquire() >= 0);
    }

    @Test
    /** Уже выбранный молчащий слот не попадает во второй сбор. */
    void doesNotPickTheSameIdleUserTwice() {
        Slots slots = new Slots(8);
        int idle = slots.acquire();
        slots.touch(idle, 1_000);
        int[] out = new int[2];
        assertEquals(1, slots.collectIdle(10_000, 500, 2, out));
        assertEquals(0, slots.collectIdle(10_000, 500, 2, out));
    }
}
