package com.jaco.navionicsgpsbridge;

import java.util.Locale;

/** Dependency-free checks for locale resolution and anchor-radius display rounding. */
public final class DisplayUnitsSelfTest {
    public static void main(String[] arguments) {
        require(DisplayUnits.resolvedUnit(
                DisplayUnits.Preference.SYSTEM,
                Locale.US) == DisplayUnits.Unit.FEET);
        require(DisplayUnits.resolvedUnit(
                DisplayUnits.Preference.SYSTEM,
                Locale.UK) == DisplayUnits.Unit.METRES);
        require(DisplayUnits.resolvedUnit(
                DisplayUnits.Preference.METRES,
                Locale.US) == DisplayUnits.Unit.METRES);
        require(DisplayUnits.resolvedUnit(
                DisplayUnits.Preference.FEET,
                Locale.FRANCE) == DisplayUnits.Unit.FEET);

        DisplayUnits feet = new DisplayUnits(DisplayUnits.Preference.FEET, Locale.US);
        require("9.8 ft".equals(feet.format(3.0, 1)));
        require("164 ft".equals(feet.format(50.0)));
        require("10–164 ft range • 3 ft steps".equals(feet.range(
                AnchorAlarm.Mode.SHORELINED.minimumRadiusMeters,
                AnchorAlarm.Mode.SHORELINED.maximumRadiusMeters,
                AnchorAlarm.Mode.SHORELINED.radiusStepMeters)));
        require("16–656 ft range • 16 ft steps".equals(feet.range(
                AnchorAlarm.Mode.FREE_SWINGING.minimumRadiusMeters,
                AnchorAlarm.Mode.FREE_SWINGING.maximumRadiusMeters,
                AnchorAlarm.Mode.FREE_SWINGING.radiusStepMeters)));
        System.out.println("DisplayUnitsSelfTest passed");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Display-unit expectation failed");
    }
}
