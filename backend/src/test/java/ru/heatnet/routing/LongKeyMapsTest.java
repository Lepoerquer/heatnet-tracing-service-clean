package ru.heatnet.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AUDIT-13: примитивные таблицы Дейкстры ведут себя как {@code HashMap<Long, …>} (кроме скорости). */
class LongKeyMapsTest {

    /** Ключ состояния как в {@link SharedVisibilityRouter}: ((prev + 1) << 32) | node. */
    private static long stateKey(int prev, int node) {
        return ((long) (prev + 1) << 32) | (node & 0xffffffffL);
    }

    @Test
    @DisplayName("LKM-1: long→double и long→long совпадают с HashMap, включая рост и «зеркальные» ключи")
    void matchesHashMap() {
        LongKeyMaps.LongDoubleMap dist = new LongKeyMaps.LongDoubleMap(4);
        LongKeyMaps.LongLongMap parent = new LongKeyMaps.LongLongMap(4);
        Map<Long, Double> refDist = new HashMap<>();
        Map<Long, Long> refParent = new HashMap<>();
        Random rnd = new Random(13);
        for (int i = 0; i < 20000; i++) {
            int a = rnd.nextInt(300) - 1;
            int b = rnd.nextInt(300);
            long key = rnd.nextBoolean() ? stateKey(a, b) : stateKey(b - 1, a + 1);
            double d = rnd.nextDouble() * 1000.0;
            long p = rnd.nextLong();
            dist.put(key, d);
            parent.put(key, p);
            refDist.put(key, d);
            refParent.put(key, p);
        }
        for (Map.Entry<Long, Double> e : refDist.entrySet()) {
            assertEquals(e.getValue(), dist.get(e.getKey(), Double.NaN), 0.0);
            assertTrue(parent.containsKey(e.getKey()));
            assertEquals(refParent.get(e.getKey()).longValue(), parent.get(e.getKey(), 0L));
        }
        for (int i = 0; i < 1000; i++) {
            long key = stateKey(1000 + i, 5000 + i);
            assertTrue(Double.isInfinite(dist.get(key, Double.POSITIVE_INFINITY)));
            assertFalse(parent.containsKey(key));
            assertEquals(-7L, parent.get(key, -7L));
        }
        // ключ 0 — обычный ключ, а не «пустая ячейка»
        dist.put(0L, 1.5);
        assertEquals(1.5, dist.get(0L, Double.NaN), 0.0);
    }

    @Test
    @DisplayName("LKM-2: флаги — три состояния (нет / false / true), перезапись и clear()")
    void flags() {
        LongKeyMaps.LongFlagMap memo = new LongKeyMaps.LongFlagMap(2);
        for (long k = 0; k < 5000; k++) {
            assertNull(memo.get(k));
            memo.put(k, (k % 3) == 0);
        }
        for (long k = 0; k < 5000; k++) {
            assertEquals((k % 3) == 0, memo.get(k));
        }
        memo.put(4L, true);
        assertEquals(Boolean.TRUE, memo.get(4L));
        memo.clear();
        for (long k = 0; k < 5000; k++) {
            assertNull(memo.get(k));
        }
        memo.put(7L, false);
        assertEquals(Boolean.FALSE, memo.get(7L));
    }
}
