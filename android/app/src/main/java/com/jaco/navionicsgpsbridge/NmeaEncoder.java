package com.jaco.navionicsgpsbridge;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NmeaEncoder {
    private static final double METERS_PER_SECOND_TO_KNOTS = 1.9438444924;
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("HHmmss.SS", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("ddMMyy", Locale.US).withZone(ZoneOffset.UTC);

    static final class Fix {
        final long timestampMillis;
        final double latitude;
        final double longitude;
        final double altitudeMeters;
        final double speedMetersPerSecond;
        final double courseDegrees;
        final double horizontalAccuracyMeters;
        final boolean valid;

        Fix(
                long timestampMillis,
                double latitude,
                double longitude,
                double altitudeMeters,
                double speedMetersPerSecond,
                double courseDegrees,
                double horizontalAccuracyMeters,
                boolean valid
        ) {
            this.timestampMillis = timestampMillis;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitudeMeters = altitudeMeters;
            this.speedMetersPerSecond = speedMetersPerSecond;
            this.courseDegrees = courseDegrees;
            this.horizontalAccuracyMeters = horizontalAccuracyMeters;
            this.valid = valid;
        }
    }

    private NmeaEncoder() {
    }

    static List<String> sentences(Fix fix) {
        boolean valid = fix.valid
                && Double.isFinite(fix.latitude)
                && fix.latitude >= -90.0
                && fix.latitude <= 90.0
                && Double.isFinite(fix.longitude)
                && fix.longitude >= -180.0
                && fix.longitude <= 180.0
                && fix.horizontalAccuracyMeters >= 0.0;

        Instant timestamp = Instant.ofEpochMilli(fix.timestampMillis);
        Coordinate latitude = coordinate(fix.latitude, true);
        Coordinate longitude = coordinate(fix.longitude, false);
        String time = UTC_TIME.format(timestamp);
        String date = UTC_DATE.format(timestamp);
        String status = valid ? "A" : "V";
        String mode = valid ? "A" : "N";
        String fixQuality = valid ? "1" : "0";
        double speedMetersPerSecond = Math.max(0.0, fix.speedMetersPerSecond);
        double speedKnots = speedMetersPerSecond * METERS_PER_SECOND_TO_KNOTS;
        double speedKilometersPerHour = speedMetersPerSecond * 3.6;
        String course = normalizedCourse(fix.courseDegrees);

        String gga = String.join(",",
                "GPGGA", time,
                latitude.value, latitude.hemisphere,
                longitude.value, longitude.hemisphere,
                fixQuality, valid ? "08" : "00", valid ? "0.9" : "9.9",
                format("%.1f", fix.altitudeMeters), "M", "0.0", "M", "", "");

        String rmc = String.join(",",
                "GPRMC", time, status,
                latitude.value, latitude.hemisphere,
                longitude.value, longitude.hemisphere,
                format("%.2f", speedKnots), course, date,
                "", "", mode);

        String vtg = String.join(",",
                "GPVTG", course, "T", "", "M",
                format("%.2f", speedKnots), "N",
                format("%.2f", speedKilometersPerHour), "K", mode);

        List<String> result = new ArrayList<>(3);
        result.add(line(gga));
        result.add(line(rmc));
        result.add(line(vtg));
        return result;
    }

    static String line(String body) {
        return "$" + body + "*" + checksum(body) + "\r\n";
    }

    static String checksum(String body) {
        int checksum = 0;
        for (int index = 0; index < body.length(); index++) {
            checksum ^= body.charAt(index);
        }
        return String.format(Locale.US, "%02X", checksum & 0xff);
    }

    private static Coordinate coordinate(double value, boolean latitude) {
        double maximum = latitude ? 90.0 : 180.0;
        double safeValue = Double.isFinite(value) ? value : 0.0;
        double clamped = Math.min(Math.max(safeValue, -maximum), maximum);
        double absolute = Math.abs(clamped);
        int degrees = (int) absolute;
        double minutes = (absolute - degrees) * 60.0;
        minutes = Math.round(minutes * 100_000.0) / 100_000.0;
        if (minutes >= 60.0) {
            degrees += 1;
            minutes = 0.0;
        }

        String degreeText = String.format(Locale.US, latitude ? "%02d" : "%03d", degrees);
        String minuteText = String.format(Locale.US, "%08.5f", minutes);
        String hemisphere;
        if (latitude) {
            hemisphere = clamped < 0.0 ? "S" : "N";
        } else {
            hemisphere = clamped < 0.0 ? "W" : "E";
        }
        return new Coordinate(degreeText + minuteText, hemisphere);
    }

    private static String normalizedCourse(double course) {
        if (!Double.isFinite(course) || course < 0.0) {
            return "";
        }
        return format("%.1f", course % 360.0);
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.US, pattern, value);
    }

    private static final class Coordinate {
        final String value;
        final String hemisphere;

        Coordinate(String value, String hemisphere) {
            this.value = value;
            this.hemisphere = hemisphere;
        }
    }
}
