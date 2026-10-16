package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Кольцо отдаёт работу в порядке поступления и не принимает сверх ёмкости. */
class RingTest {

  @Test
  /** Индексы выходят в том же порядке, в котором их положили. */
  void drainsWorkInTheOrderItWasPrepared() {
    Ring ring = new Ring(4);
    assertTrue(ring.offer(1));
    assertTrue(ring.offer(2));
    assertTrue(ring.offer(3));
    assertEquals(1, ring.poll());
    assertEquals(2, ring.poll());
    assertEquals(3, ring.poll());
    assertEquals(-1, ring.poll());
  }

  @Test
  /** Полное кольцо возвращает false и не затирает уже лежащие индексы. */
  void stopsAcceptingWorkWhenEverySlotIsBusy() {
    Ring ring = new Ring(2);
    assertTrue(ring.offer(10));
    assertTrue(ring.offer(11));
    assertFalse(ring.offer(12));
    assertEquals(2, ring.size());
    assertEquals(10, ring.poll());
    assertTrue(ring.offer(12));
    assertEquals(11, ring.poll());
    assertEquals(12, ring.poll());
    assertEquals(0, ring.size());
  }
}
