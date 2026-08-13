package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionEntitlementLeaseStore {
    private static final String PREFERENCES_NAME = "session_entitlement_lease";
    private static final String LEASE_KEY = "active_session_lease_v1";

    private final SharedPreferences preferences;

    public SessionEntitlementLeaseStore(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    SessionEntitlementLeaseStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public boolean save(SessionEntitlementLease lease) {
        if (lease == null) {
            return false;
        }
        return preferences.edit().putString(LEASE_KEY, lease.persistedValue()).commit();
    }

    public SessionEntitlementLease load() {
        return SessionEntitlementLease.fromPersistedValue(
                preferences.getString(LEASE_KEY, null));
    }

    public boolean clear() {
        return preferences.edit().remove(LEASE_KEY).commit();
    }
}
