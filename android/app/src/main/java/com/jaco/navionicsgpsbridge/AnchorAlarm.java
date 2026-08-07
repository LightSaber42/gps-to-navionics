package com.jaco.navionicsgpsbridge;

import java.util.Locale;

/** Pure anchor-watch geometry shared by the service and its tests. */
final class AnchorAlarm {
    enum Mode {
        SHORELINED("shorelined", "Shorelined", 3.0, 50.0, 1.0, 15.0),
        FREE_SWINGING("free_swinging", "Free swinging", 5.0, 200.0, 5.0, 50.0);

        final String storageValue;
        final String displayName;
        final double minimumRadiusMeters;
        final double maximumRadiusMeters;
        final double radiusStepMeters;
        final double defaultRadiusMeters;

        Mode(
                String storageValue,
                String displayName,
                double minimumRadiusMeters,
                double maximumRadiusMeters,
                double radiusStepMeters,
                double defaultRadiusMeters
        ) {
            this.storageValue = storageValue;
            this.displayName = displayName;
            this.minimumRadiusMeters = minimumRadiusMeters;
            this.maximumRadiusMeters = maximumRadiusMeters;
            this.radiusStepMeters = radiusStepMeters;
            this.defaultRadiusMeters = defaultRadiusMeters;
        }

        double adjustedRadius(double radiusMeters) {
            if (!Double.isFinite(radiusMeters)) return defaultRadiusMeters;
            double clamped = Math.max(minimumRadiusMeters,
                    Math.min(maximumRadiusMeters, radiusMeters));
            return Math.round(clamped / radiusStepMeters) * radiusStepMeters;
        }

        static Mode fromStorageValue(String value) {
            for (Mode mode : values()) {
                if (mode.storageValue.equals(value)) return mode;
            }
            return SHORELINED;
        }
    }

    static final class Assessment {
        final double distanceMeters;
        final double minimumLikelyDistanceMeters;
        final boolean definitelyOutside;

        Assessment(
                double distanceMeters,
                double minimumLikelyDistanceMeters,
                boolean definitelyOutside
        ) {
            this.distanceMeters = distanceMeters;
            this.minimumLikelyDistanceMeters = minimumLikelyDistanceMeters;
            this.definitelyOutside = definitelyOutside;
        }
    }

    static final class Offset {
        final double eastMeters;
        final double northMeters;

        Offset(double eastMeters, double northMeters) {
            this.eastMeters = eastMeters;
            this.northMeters = northMeters;
        }
    }

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private AnchorAlarm() {
    }

    static Assessment assess(
            double referenceLatitude,
            double referenceLongitude,
            double currentLatitude,
            double currentLongitude,
            double currentAccuracyMeters,
            double radiusMeters
    ) {
        double distance = distanceMeters(
                referenceLatitude, referenceLongitude, currentLatitude, currentLongitude);
        double accuracy = Double.isFinite(currentAccuracyMeters)
                ? Math.max(0.0, currentAccuracyMeters) : 0.0;
        double minimumLikelyDistance = Math.max(0.0, distance - accuracy);
        return new Assessment(
                distance,
                minimumLikelyDistance,
                minimumLikelyDistance > Math.max(1.0, radiusMeters));
    }

    static double distanceMeters(
            double firstLatitude,
            double firstLongitude,
            double secondLatitude,
            double secondLongitude
    ) {
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double latitudeDelta = secondLatitudeRadians - firstLatitudeRadians;
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double sinLatitude = Math.sin(latitudeDelta / 2.0);
        double sinLongitude = Math.sin(longitudeDelta / 2.0);
        double haversine = sinLatitude * sinLatitude
                + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
                * sinLongitude * sinLongitude;
        double angularDistance = 2.0 * Math.atan2(
                Math.sqrt(haversine), Math.sqrt(Math.max(0.0, 1.0 - haversine)));
        return EARTH_RADIUS_METERS * angularDistance;
    }

    static Offset plotOffsetMeters(
            double referenceLatitude,
            double referenceLongitude,
            double pointLatitude,
            double pointLongitude
    ) {
        double referenceLatitudeRadians = Math.toRadians(referenceLatitude);
        double pointLatitudeRadians = Math.toRadians(pointLatitude);
        double latitudeDelta = pointLatitudeRadians - referenceLatitudeRadians;
        double longitudeDeltaDegrees = (pointLongitude - referenceLongitude + 540.0) % 360.0 - 180.0;
        double longitudeDelta = Math.toRadians(longitudeDeltaDegrees);
        double meanLatitude = (referenceLatitudeRadians + pointLatitudeRadians) / 2.0;
        return new Offset(
                EARTH_RADIUS_METERS * longitudeDelta * Math.cos(meanLatitude),
                EARTH_RADIUS_METERS * latitudeDelta);
    }

    static String coordinate(double latitude, double longitude) {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }
}
