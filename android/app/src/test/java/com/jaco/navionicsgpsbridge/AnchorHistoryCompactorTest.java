package com.jaco.navionicsgpsbridge;

import java.util.ArrayList;
import java.util.List;

public final class AnchorHistoryCompactorTest {
    public static void main(String[] args) {
        List<Integer> points = new ArrayList<>();
        for (int sequence = 1; sequence <= 1_024; sequence++) points.add(sequence);

        assertTrue(AnchorHistoryCompactor.compactIfNeeded(points),
                "A full history should be compacted");
        assertEquals(513, points.size());
        assertEquals(1, points.get(0));
        assertEquals(1_024, points.get(points.size() - 1));
        assertEquals(2, points.get(1));
        assertEquals(4, points.get(2));

        for (int sequence = 1_025; sequence <= 1_545; sequence++) {
            points.add(sequence);
            AnchorHistoryCompactor.compactIfNeeded(points);
            assertTrue(points.size() < AnchorHistoryCompactor.MAXIMUM_POINT_COUNT,
                    "Compacted history must stay below its threshold");
        }
        assertEquals(1, points.get(0));
        assertEquals(1_545, points.get(points.size() - 1));
        for (int sequence = 1_536; sequence <= 1_545; sequence++) {
            assertTrue(points.contains(sequence), "Recent history should retain every point");
        }

        List<Integer> legacyHistory = new ArrayList<>();
        for (int sequence = 1; sequence <= 5_000; sequence++) legacyHistory.add(sequence);
        assertTrue(AnchorHistoryCompactor.compactIfNeeded(legacyHistory),
                "Oversized persisted history should be compacted on load");
        assertTrue(legacyHistory.size() < AnchorHistoryCompactor.MAXIMUM_POINT_COUNT,
                "One call should compact repeatedly until bounded");
        assertEquals(1, legacyHistory.get(0));
        assertEquals(5_000, legacyHistory.get(legacyHistory.size() - 1));
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
