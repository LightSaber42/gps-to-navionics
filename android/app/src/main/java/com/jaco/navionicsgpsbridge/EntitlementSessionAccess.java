package com.jaco.navionicsgpsbridge;

public final class EntitlementSessionAccess {
    private EntitlementSessionAccess() {
    }

    public static boolean authorizes(
            EntitlementPolicy.FeatureLevel requiredFeature,
            EntitlementPolicy.Decision decision,
            String activeSessionID,
            SessionEntitlementLease lease
    ) {
        if (requiredFeature == EntitlementPolicy.FeatureLevel.FREE) {
            return true;
        }
        if (decision != null
                && decision.featureLevel != null
                && decision.featureLevel.includes(requiredFeature)) {
            return true;
        }
        return activeSessionID != null
                && lease != null
                && lease.authorizes(requiredFeature, activeSessionID);
    }

    public static SessionEntitlementLease leaseForStartingSession(
            String sessionID,
            EntitlementPolicy.Decision decision,
            long nowMillis
    ) {
        if (decision == null
                || decision.featureLevel == null
                || !decision.featureLevel.includes(
                        EntitlementPolicy.FeatureLevel.ANCHOR_WATCH)) {
            return null;
        }
        return SessionEntitlementLease.acquire(
                sessionID,
                decision.featureLevel,
                decision,
                nowMillis);
    }

    public static SessionEntitlementLease upgradedLease(
            SessionEntitlementLease lease,
            String activeSessionID,
            EntitlementPolicy.Decision decision,
            long nowMillis
    ) {
        if (lease == null
                || activeSessionID == null
                || !activeSessionID.equals(lease.sessionID)
                || decision == null
                || decision.featureLevel == null
                || decision.featureLevel.ordinal() <= lease.featureLevel.ordinal()) {
            return null;
        }
        return SessionEntitlementLease.acquire(
                activeSessionID,
                decision.featureLevel,
                decision,
                nowMillis);
    }
}
