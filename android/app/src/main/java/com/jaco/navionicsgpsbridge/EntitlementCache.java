package com.jaco.navionicsgpsbridge;

public final class EntitlementCache {
    private static final String SERIALIZATION_VERSION = "1";
    private static final String ABSENT = "-";

    Long anchorLastValidatedActiveAtMillis;
    Long remoteLastValidatedActiveAtMillis;
    Long remoteKnownExpirationAtMillis;
    boolean remoteKnownRevoked;

    public void recordActive(
            EntitlementPolicy.FeatureLevel feature,
            long validationTimeMillis,
            Long expirationTimeMillis
    ) {
        if (feature == EntitlementPolicy.FeatureLevel.ANCHOR_WATCH) {
            anchorLastValidatedActiveAtMillis = validationTimeMillis;
        } else if (feature == EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH) {
            remoteLastValidatedActiveAtMillis = validationTimeMillis;
            remoteKnownExpirationAtMillis = expirationTimeMillis;
            remoteKnownRevoked = false;
        }
    }

    public void recordInactive(
            EntitlementPolicy.FeatureLevel feature,
            Long knownExpirationTimeMillis,
            boolean knownRevoked
    ) {
        if (feature == EntitlementPolicy.FeatureLevel.ANCHOR_WATCH) {
            anchorLastValidatedActiveAtMillis = null;
        } else if (feature == EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH) {
            remoteLastValidatedActiveAtMillis = null;
            remoteKnownExpirationAtMillis = knownExpirationTimeMillis;
            remoteKnownRevoked = knownRevoked;
        }
    }

    public EntitlementPolicy.Evidence unavailableEvidence(
            EntitlementPolicy.FeatureLevel feature
    ) {
        if (feature == EntitlementPolicy.FeatureLevel.ANCHOR_WATCH) {
            return new EntitlementPolicy.Evidence(
                    EntitlementPolicy.StoreStatus.UNAVAILABLE,
                    anchorLastValidatedActiveAtMillis,
                    null,
                    false);
        }
        if (feature == EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH) {
            return new EntitlementPolicy.Evidence(
                    EntitlementPolicy.StoreStatus.UNAVAILABLE,
                    remoteLastValidatedActiveAtMillis,
                    remoteKnownExpirationAtMillis,
                    remoteKnownRevoked);
        }
        return new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.INACTIVE,
                null,
                null,
                false);
    }

    public String persistedValue() {
        return SERIALIZATION_VERSION
                + "|" + encode(anchorLastValidatedActiveAtMillis)
                + "|" + encode(remoteLastValidatedActiveAtMillis)
                + "|" + encode(remoteKnownExpirationAtMillis)
                + "|" + (remoteKnownRevoked ? "1" : "0");
    }

    public static EntitlementCache fromPersistedValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] components = value.split("\\|", -1);
        if (components.length != 5 || !SERIALIZATION_VERSION.equals(components[0])) {
            return null;
        }
        if (!"0".equals(components[4]) && !"1".equals(components[4])) {
            return null;
        }
        try {
            EntitlementCache cache = new EntitlementCache();
            cache.anchorLastValidatedActiveAtMillis = decode(components[1]);
            cache.remoteLastValidatedActiveAtMillis = decode(components[2]);
            cache.remoteKnownExpirationAtMillis = decode(components[3]);
            cache.remoteKnownRevoked = "1".equals(components[4]);
            return cache;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String encode(Long value) {
        return value == null ? ABSENT : Long.toString(value);
    }

    private static Long decode(String value) {
        if (ABSENT.equals(value)) {
            return null;
        }
        long decoded = Long.parseLong(value);
        if (decoded < 0) {
            throw new NumberFormatException("Negative timestamps are invalid");
        }
        return decoded;
    }
}
