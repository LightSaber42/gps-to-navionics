package com.jaco.navionicsgpsbridge;

import java.util.ArrayList;
import java.util.List;

/** Keeps anchor history bounded while progressively reducing older detail. */
final class AnchorHistoryCompactor {
    static final int MAXIMUM_POINT_COUNT = 1_024;

    private AnchorHistoryCompactor() {}

    static <T> boolean compactIfNeeded(List<T> points) {
        boolean compacted = false;
        while (points.size() >= MAXIMUM_POINT_COUNT) {
            int lastIndex = points.size() - 1;
            int alternatingStart = lastIndex % 2 == 0 ? 2 : 1;
            List<T> retained = new ArrayList<>((points.size() / 2) + 1);
            retained.add(points.get(0));
            for (int index = alternatingStart; index < points.size(); index += 2) {
                retained.add(points.get(index));
            }
            points.clear();
            points.addAll(retained);
            compacted = true;
        }
        return compacted;
    }
}
