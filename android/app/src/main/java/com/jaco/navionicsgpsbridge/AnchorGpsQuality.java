package com.jaco.navionicsgpsbridge;

import android.content.Context;

import java.util.Locale;

/** Explains when reported GPS uncertainty is large relative to the alarm radius. */
final class AnchorGpsQuality {
    private static final double MINIMUM_WARNING_ACCURACY_METERS = 2.0;
    private static final double MAXIMUM_ACCURACY_FRACTION_OF_RADIUS = 0.5;

    private AnchorGpsQuality() {
    }

    static String degradedWarning(double accuracyMeters, double radiusMeters) {
        return degradedWarning(
                accuracyMeters,
                radiusMeters,
                new DisplayUnits(DisplayUnits.Preference.METRES, Locale.US));
    }

    static String degradedWarning(
            double accuracyMeters,
            double radiusMeters,
            DisplayUnits displayUnits
    ) {
        return degradedWarning(accuracyMeters, radiusMeters, displayUnits, null);
    }

    static String degradedWarning(
            double accuracyMeters,
            double radiusMeters,
            DisplayUnits displayUnits,
            Context context
    ) {
        if (!Double.isFinite(accuracyMeters) || accuracyMeters < 0.0
                || !Double.isFinite(radiusMeters) || radiusMeters <= 0.0) return null;
        double warningThreshold = Math.max(
                MINIMUM_WARNING_ACCURACY_METERS,
                radiusMeters * MAXIMUM_ACCURACY_FRACTION_OF_RADIUS);
        if (accuracyMeters <= warningThreshold) return null;
        String accuracy = displayUnits.formatAccuracy(accuracyMeters);
        String radius = displayUnits.format(radiusMeters);
        String requiredDrift = displayUnits.format(radiusMeters + accuracyMeters);
        if (context != null) {
            return context.getString(
                    R.string.gps_precision_degraded_detail,
                    accuracy,
                    radius,
                    requiredDrift);
        }
        return "GPS precision is degraded (" + accuracy + ") for a " + radius
                + " alarm limit. With this fix, the conservative alarm needs about "
                + requiredDrift + " of measured drift to confirm a breach. Give the boat "
                + "phone a clearer view of the sky.";
    }
}
