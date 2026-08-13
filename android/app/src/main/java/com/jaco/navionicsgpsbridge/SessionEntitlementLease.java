package com.jaco.navionicsgpsbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public final class SessionEntitlementLease {
    private static final String SERIALIZATION_VERSION = "1";

    public final String sessionID;
    public final EntitlementPolicy.FeatureLevel featureLevel;
    public final long acquiredAtMillis;

    private SessionEntitlementLease(
            String sessionID,
            EntitlementPolicy.FeatureLevel featureLevel,
            long acquiredAtMillis
    ) {
        this.sessionID = sessionID;
        this.featureLevel = featureLevel;
        this.acquiredAtMillis = acquiredAtMillis;
    }

    public static SessionEntitlementLease acquire(
            String sessionID,
            EntitlementPolicy.FeatureLevel requiredFeature,
            EntitlementPolicy.Decision decision,
            long nowMillis
    ) {
        if (isBlank(sessionID)
                || requiredFeature == null
                || requiredFeature == EntitlementPolicy.FeatureLevel.FREE
                || decision == null
                || decision.featureLevel == null
                || !decision.featureLevel.includes(requiredFeature)) {
            return null;
        }
        return new SessionEntitlementLease(sessionID, requiredFeature, nowMillis);
    }

    public boolean authorizes(
            EntitlementPolicy.FeatureLevel requiredFeature,
            String requestedSessionID
    ) {
        return !isBlank(sessionID)
                && sessionID.equals(requestedSessionID)
                && requiredFeature != null
                && requiredFeature != EntitlementPolicy.FeatureLevel.FREE
                && featureLevel.includes(requiredFeature);
    }

    public String persistedValue() {
        String encodedSessionID = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(sessionID.getBytes(StandardCharsets.UTF_8));
        return SERIALIZATION_VERSION
                + "|" + featureLevel.name()
                + "|" + acquiredAtMillis
                + "|" + encodedSessionID;
    }

    public static SessionEntitlementLease fromPersistedValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] components = value.split("\\|", -1);
        if (components.length != 4 || !SERIALIZATION_VERSION.equals(components[0])) {
            return null;
        }
        try {
            EntitlementPolicy.FeatureLevel featureLevel =
                    EntitlementPolicy.FeatureLevel.valueOf(components[1]);
            long acquiredAtMillis = Long.parseLong(components[2]);
            String sessionID = new String(
                    Base64.getUrlDecoder().decode(components[3]),
                    StandardCharsets.UTF_8);
            if (featureLevel == EntitlementPolicy.FeatureLevel.FREE
                    || acquiredAtMillis < 0
                    || isBlank(sessionID)) {
                return null;
            }
            return new SessionEntitlementLease(sessionID, featureLevel, acquiredAtMillis);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SessionEntitlementLease)) return false;
        SessionEntitlementLease lease = (SessionEntitlementLease) other;
        return acquiredAtMillis == lease.acquiredAtMillis
                && sessionID.equals(lease.sessionID)
                && featureLevel == lease.featureLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionID, featureLevel, acquiredAtMillis);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
