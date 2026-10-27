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
        SYSTEM("system", R.string.system_default, "System Default"),
        METRES("metres", R.string.metres, "Metres"),
        FEET("feet", R.string.feet, "Feet");

        final String storageValue;
        final int displayNameResource;
        final String fallbackDisplayName;

        Preference(String storageValue, int displayNameResource, String fallbackDisplayName) {
            this.storageValue = storageValue;
            this.displayNameResource = displayNameResource;
            this.fallbackDisplayName = fallbackDisplayName;
        }

        static Preference fromStorageValue(String value) {
            for (Preference preference : values()) {
                if (preference.storageValue.equals(value)) return preference;
            }
            return SYSTEM;
        }
    }

    enum Unit {
        METRES("m", R.string.metres, "Metres"),
        FEET("ft", R.string.feet, "Feet");

        final String symbol;
        final int displayNameResource;
        final String fallbackDisplayName;

        Unit(String symbol, int displayNameResource, String fallbackDisplayName) {
            this.symbol = symbol;
            this.displayNameResource = displayNameResource;
            this.fallbackDisplayName = fallbackDisplayName;
        }
    }

    final Preference preference;
    final Unit unit;
    private final Context context;
    private final Locale locale;

    DisplayUnits(Preference preference, Locale locale) {
        this(null, preference, locale);
    }

    DisplayUnits(Context context, Preference preference, Locale locale) {
        this.context = context;
        this.preference = preference;
        this.locale = locale;
        unit = resolvedUnit(preference, locale);
    }

    static DisplayUnits load(Context context) {
        return new DisplayUnits(context, loadPreference(context), Locale.getDefault());
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
        String preferenceName = displayName(preference);
        if (preference != Preference.SYSTEM) return preferenceName;
        String unitName = displayName(unit);
        return context == null
                ? preferenceName + " (" + unitName + ")"
                : context.getString(R.string.system_default_detail, preferenceName, unitName);
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
        String minimum = number(minimumMetres, 0);
        String maximum = number(maximumMetres, 0);
        String step = format(stepMetres);
        return context == null
                ? minimum + "–" + maximum + " " + unit.symbol + " range • " + step + " steps"
                : context.getString(
                        R.string.distance_range,
                        minimum,
                        maximum,
                        unit.symbol,
                        step);
    }

    private String displayName(Preference value) {
        return context == null
                ? value.fallbackDisplayName
                : context.getString(value.displayNameResource);
    }

    private String displayName(Unit value) {
        return context == null
                ? value.fallbackDisplayName
                : context.getString(value.displayNameResource);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
