package ru.heatnet.routing;

import java.util.Arrays;

/**
 * AUDIT-13 (Claude, 25.09). Хеш-таблицы с примитивным ключом {@code long} (открытая адресация, линейное
 * пробирование, перемешивание ключа финализатором MurmurHash3) для горячего цикла Дейкстры
 * {@link SharedVisibilityRouter}.
 *
 * <p>Раньше состояния «(откуда пришли, где стоим)» и мемо рёбер хранились в {@code HashMap<Long, …>}. Ключ
 * состояния — {@code ((prev + 1) << 32) | node}, а {@code Long.hashCode} — это {@code старшие 32 бита XOR младшие}:
 * пары (a, b), (b, a) и вообще все пары с одинаковым {@code a ^ b} попадали в одну корзину. Корзины превращались в
 * деревья, и профиль JFR конкурсного расчёта показал ~38 % времени плана в {@code HashMap.getNode},
 * {@code HashMap.comparableClassFor} и {@code TreeNode}. Логика поиска не меняется — только структура хранения.</p>
 */
final class LongKeyMaps {

    private LongKeyMaps() {
    }

    static int mix(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h;
    }

    private static int capacityFor(int expected) {
        int cap = 16;
        while (cap < expected * 2) {
            cap <<= 1;
        }
        return cap;
    }

    /** {@code long → double}. */
    static final class LongDoubleMap {
        private long[] keys;
        private double[] values;
        private boolean[] used;
        private int size;
        private int mask;

        LongDoubleMap(int expected) {
            int cap = capacityFor(expected);
            keys = new long[cap];
            values = new double[cap];
            used = new boolean[cap];
            mask = cap - 1;
        }

        /** Значение или {@code missing}, если ключа нет. */
        double get(long key, double missing) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return values[i];
                }
                i = (i + 1) & mask;
            }
            return missing;
        }

        void put(long key, double value) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            values[i] = value;
            if (++size * 2 > keys.length) {
                grow();
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            double[] oldValues = values;
            boolean[] oldUsed = used;
            int cap = oldKeys.length * 2;
            keys = new long[cap];
            values = new double[cap];
            used = new boolean[cap];
            mask = cap - 1;
            size = 0;
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldUsed[j]) {
                    put(oldKeys[j], oldValues[j]);
                }
            }
        }
    }

    /** {@code long → long}. */
    static final class LongLongMap {
        private long[] keys;
        private long[] values;
        private boolean[] used;
        private int size;
        private int mask;

        LongLongMap(int expected) {
            int cap = capacityFor(expected);
            keys = new long[cap];
            values = new long[cap];
            used = new boolean[cap];
            mask = cap - 1;
        }

        boolean containsKey(long key) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return true;
                }
                i = (i + 1) & mask;
            }
            return false;
        }

        /** Значение или {@code missing}, если ключа нет. */
        long get(long key, long missing) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return values[i];
                }
                i = (i + 1) & mask;
            }
            return missing;
        }

        void put(long key, long value) {
            int i = mix(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            values[i] = value;
            if (++size * 2 > keys.length) {
                grow();
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            long[] oldValues = values;
            boolean[] oldUsed = used;
            int cap = oldKeys.length * 2;
            keys = new long[cap];
            values = new long[cap];
            used = new boolean[cap];
            mask = cap - 1;
            size = 0;
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldUsed[j]) {
                    put(oldKeys[j], oldValues[j]);
                }
            }
        }
    }

    /** {@code long → boolean} с «нет значения»: мемо проверок рёбер. */
    static final class LongFlagMap {
        private static final byte ABSENT = 0;
        private static final byte FALSE = 1;
        private static final byte TRUE = 2;
        private long[] keys;
        private byte[] values;
        private int size;
        private int mask;

        LongFlagMap(int expected) {
            int cap = capacityFor(expected);
            keys = new long[cap];
            values = new byte[cap];
            mask = cap - 1;
        }

        /** @return {@code null}, если значения нет */
        Boolean get(long key) {
            int i = mix(key) & mask;
            while (values[i] != ABSENT) {
                if (keys[i] == key) {
                    return values[i] == TRUE;
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        void put(long key, boolean value) {
            int i = mix(key) & mask;
            while (values[i] != ABSENT) {
                if (keys[i] == key) {
                    values[i] = value ? TRUE : FALSE;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            values[i] = value ? TRUE : FALSE;
            if (++size * 2 > keys.length) {
                grow();
            }
        }

        void clear() {
            Arrays.fill(values, ABSENT);
            size = 0;
        }

        private void grow() {
            long[] oldKeys = keys;
            byte[] oldValues = values;
            int cap = oldKeys.length * 2;
            keys = new long[cap];
            values = new byte[cap];
            mask = cap - 1;
            size = 0;
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldValues[j] != ABSENT) {
                    put(oldKeys[j], oldValues[j] == TRUE);
                }
            }
        }
    }
}
