package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Converts canonical metre values for display without changing persisted or protocol data. */
final class DisplayUnits {
    private static final String PREFERENCES = "display_settings";
    private static final String KEY_PREFERENCE = "distance_unit_preference_v1";
    private static final double FEET_PER_METRE = 3.280_839_895_013_123;

    enum Preference {
        SYSTEM("system", "System Default"),
        METRES("metres", "Metres"),
        FEET("feet", "Feet");

        final String storageValue;
        final String displayName;

        Preference(String storageValue, String displayName) {
            this.storageValue = storageValue;
            this.displayName = displayName;
        }

        static Preference fromStorageValue(String value) {
            for (Preference preference : values()) {
                if (preference.storageValue.equals(value)) return preference;
            }
            return SYSTEM;
        }
    }

    enum Unit {
        METRES("m", "Metres"),
        FEET("ft", "Feet");

        final String symbol;
        final String displayName;

        Unit(String symbol, String displayName) {
            this.symbol = symbol;
            this.displayName = displayName;
        }
    }

    final Preference preference;
    final Unit unit;
    private final Locale locale;

    DisplayUnits(Preference preference, Locale locale) {
        this.preference = preference;
        this.locale = locale;
        unit = resolvedUnit(preference, locale);
    }

    static DisplayUnits load(Context context) {
        return new DisplayUnits(loadPreference(context), Locale.getDefault());
    }

    static Preference loadPreference(Context context) {
        return Preference.fromStorageValue(preferences(context).getString(
                KEY_PREFERENCE,
                Preference.SYSTEM.storageValue));
    }

    static void save(Context context, Preference preference) {
        preferences(context).edit()
                .putString(KEY_PREFERENCE, preference.storageValue)
                .apply();
    }

    static Unit resolvedUnit(Preference preference, Locale locale) {
        if (preference == Preference.METRES) return Unit.METRES;
        if (preference == Preference.FEET) return Unit.FEET;
        String country = locale.getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country)
                ? Unit.FEET : Unit.METRES;
    }

    String preferenceDetail() {
        if (preference != Preference.SYSTEM) return preference.displayName;
        return preference.displayName + " (" + unit.displayName + ")";
    }

    String format(double metres) {
        return format(metres, 0);
    }

    String format(double metres, int fractionDigits) {
        return number(metres, fractionDigits) + " " + unit.symbol;
    }

    String formatAccuracy(double metres) {
        return "±" + format(metres);
    }

    String number(double metres, int fractionDigits) {
        int digits = Math.max(0, Math.min(3, fractionDigits));
        return String.format(
                locale,
                "%." + digits + "f",
                unit == Unit.FEET ? metres * FEET_PER_METRE : metres);
    }

    String range(double minimumMetres, double maximumMetres, double stepMetres) {
        return number(minimumMetres, 0) + "–" + number(maximumMetres, 0)
                + " " + unit.symbol + " range • " + format(stepMetres) + " steps";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
