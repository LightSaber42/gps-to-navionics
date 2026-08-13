package com.jaco.navionicsgpsbridge;

public final class AnchorAlarmTest {
    public static void main(String[] args) {
        assertNear(0.0, AnchorAlarm.distanceMeters(37.0, 23.0, 37.0, 23.0), 0.01);
        assertNear(111.2, AnchorAlarm.distanceMeters(0.0, 0.0, 0.001, 0.0), 0.2);
        assertNear(22.2, AnchorAlarm.distanceMeters(0.0, 179.9999, 0.0, -179.9999), 0.2);

        AnchorAlarm.Assessment accuracyProtected = AnchorAlarm.assess(
                0.0, 0.0, 0.001, 0.0, 15.0, 100.0);
        assertFalse(accuracyProtected.definitelyOutside,
                "Reported GPS accuracy should protect a marginal boundary fix");

        AnchorAlarm.Assessment outside = AnchorAlarm.assess(
                0.0, 0.0, 0.001, 0.0, 5.0, 100.0);
        assertTrue(outside.definitelyOutside,
                "A fix whose nearest likely point is outside the radius should breach");

        assertTrue(AnchorAlarm.Mode.fromStorageValue("free_swinging")
                        == AnchorAlarm.Mode.FREE_SWINGING,
                "Free-swinging mode should survive persistence");
        assertTrue(AnchorAlarm.Mode.fromStorageValue("unexpected")
                        == AnchorAlarm.Mode.SHORELINED,
                "Unknown persisted modes should fail safe to shorelined");
        assertNear(7.0, AnchorAlarm.Mode.SHORELINED.adjustedRadius(7.4), 0.01);
        assertNear(50.0, AnchorAlarm.Mode.SHORELINED.adjustedRadius(500.0), 0.01);
        assertNear(200.0, AnchorAlarm.Mode.FREE_SWINGING.adjustedRadius(500.0), 0.01);

        assertNull(AnchorGpsQuality.degradedWarning(1.0, 3.0),
                "Good GPS precision should not warn");
        assertNull(AnchorGpsQuality.degradedWarning(7.0, 15.0),
                "Accuracy below half the alarm radius should not warn");
        String degradedWarning = AnchorGpsQuality.degradedWarning(7.0, 3.0);
        assertContains(degradedWarning, "±7 m");
        assertContains(degradedWarning, "3 m alarm limit");
        assertContains(degradedWarning, "about 10 m");
        assertNull(AnchorGpsQuality.degradedWarning(Double.POSITIVE_INFINITY, 3.0),
                "Invalid GPS precision should not warn");

        AnchorAlarm.Offset north = AnchorAlarm.plotOffsetMeters(0.0, 0.0, 0.001, 0.0);
        assertNear(0.0, north.eastMeters, 0.01);
        assertNear(111.2, north.northMeters, 0.2);
        AnchorAlarm.Offset east = AnchorAlarm.plotOffsetMeters(0.0, 0.0, 0.0, 0.001);
        assertNear(111.2, east.eastMeters, 0.2);
        assertNear(0.0, east.northMeters, 0.01);
        AnchorAlarm.Offset wrapped = AnchorAlarm.plotOffsetMeters(
                0.0, 179.9999, 0.0, -179.9999);
        assertNear(22.2, wrapped.eastMeters, 0.2);
    }

    private static void assertNear(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                    "Expected " + expected + " ± " + tolerance + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertNull(Object value, String message) {
        if (value != null) throw new AssertionError(message + ": " + value);
    }

    private static void assertContains(String value, String expected) {
        assertTrue(value != null && value.contains(expected),
                "Expected warning to contain \"" + expected + "\", got: " + value);
    }
}
