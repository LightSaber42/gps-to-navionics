package com.jaco.navionicsgpsbridge;

public final class EntitlementPolicy {
    public static final long OFFLINE_GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public enum FeatureLevel {
        FREE(0),
        ANCHOR_WATCH(1),
        REMOTE_ANCHOR_WATCH(2);

        private final int rank;

        FeatureLevel(int rank) {
            this.rank = rank;
        }

        boolean includes(FeatureLevel requiredFeature) {
            return requiredFeature != null && rank >= requiredFeature.rank;
        }
    }

    public enum StoreStatus {
        ACTIVE,
        INACTIVE,
        UNAVAILABLE
    }

    public static final class Evidence {
        public final StoreStatus storeStatus;
        public final Long lastValidatedActiveAtMillis;
        public final Long knownExpirationAtMillis;
        public final boolean knownRevoked;

        public Evidence(
                StoreStatus storeStatus,
                Long lastValidatedActiveAtMillis,
                Long knownExpirationAtMillis,
                boolean knownRevoked
        ) {
            this.storeStatus = storeStatus;
            this.lastValidatedActiveAtMillis = lastValidatedActiveAtMillis;
            this.knownExpirationAtMillis = knownExpirationAtMillis;
            this.knownRevoked = knownRevoked;
        }
    }

    public static final class Decision {
        public final FeatureLevel featureLevel;

        public Decision(FeatureLevel featureLevel) {
            this.featureLevel = featureLevel;
        }
    }

    private EntitlementPolicy() {}

    public static Decision evaluate(
            Evidence anchorWatch,
            Evidence remoteAnchorWatch,
            long nowMillis
    ) {
        boolean anchorIsActive = isActive(anchorWatch, nowMillis, null);
        boolean remoteIsActive = isActive(
                remoteAnchorWatch,
                nowMillis,
                OFFLINE_GRACE_MILLIS);
        if (anchorIsActive && remoteIsActive) {
            return new Decision(FeatureLevel.REMOTE_ANCHOR_WATCH);
        }
        return new Decision(anchorIsActive ? FeatureLevel.ANCHOR_WATCH : FeatureLevel.FREE);
    }

    private static boolean isActive(
            Evidence evidence,
            long nowMillis,
            Long unavailableCacheMillis
    ) {
        if (evidence == null || evidence.storeStatus == null || evidence.knownRevoked) {
            return false;
        }
        if (evidence.knownExpirationAtMillis != null
                && evidence.knownExpirationAtMillis <= nowMillis) {
            return false;
        }

        switch (evidence.storeStatus) {
            case ACTIVE:
                return true;
            case INACTIVE:
                return false;
            case UNAVAILABLE:
                if (evidence.lastValidatedActiveAtMillis == null) {
                    return false;
                }
                if (unavailableCacheMillis == null) {
                    return true;
                }
                long elapsedMillis = nowMillis - evidence.lastValidatedActiveAtMillis;
                return elapsedMillis >= 0 && elapsedMillis <= unavailableCacheMillis;
            default:
                return false;
        }
    }
}
