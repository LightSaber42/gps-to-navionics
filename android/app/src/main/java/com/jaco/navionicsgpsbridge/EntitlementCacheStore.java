package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class EntitlementCacheStore {
    private static final String PREFERENCES_NAME = "entitlement_validation_cache";
    private static final String CACHE_KEY = "validation_cache_v1";

    private final SharedPreferences preferences;

    public EntitlementCacheStore(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    EntitlementCacheStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public EntitlementCache load() {
        EntitlementCache cache = EntitlementCache.fromPersistedValue(
                preferences.getString(CACHE_KEY, null));
        return cache == null ? new EntitlementCache() : cache;
    }

    public boolean save(EntitlementCache cache) {
        if (cache == null) {
            return false;
        }
        return preferences.edit().putString(CACHE_KEY, cache.persistedValue()).commit();
    }
}
