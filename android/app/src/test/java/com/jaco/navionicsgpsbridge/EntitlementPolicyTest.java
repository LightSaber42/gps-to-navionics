package com.jaco.navionicsgpsbridge;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

public final class EntitlementPolicyTest {
    public static void main(String[] args) throws Exception {
        testCumulativeEntitlementsAndOfflinePolicy();
        testKnownExpiryRevocationAndClockRollback();
        testSessionLeaseContinuityAndIsolation();
        testSessionLeaseSerialization();
        testStoreProductIdentifiersRemainStable();
        testEntitlementCacheTransitionsAndSerialization();
        testPlayPurchaseSignatureVerification();
    }

    private static void testCumulativeEntitlementsAndOfflinePolicy() {
        long validation = 1_000_000L;
        EntitlementPolicy.Evidence active = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.ACTIVE, validation, null, false);
        EntitlementPolicy.Evidence inactive = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.INACTIVE, null, null, false);
        EntitlementPolicy.Evidence unavailableRemote = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.UNAVAILABLE, validation, null, false);

        assertEquals(EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                EntitlementPolicy.evaluate(active, active, validation).featureLevel);
        assertEquals(EntitlementPolicy.FeatureLevel.FREE,
                EntitlementPolicy.evaluate(inactive, active, validation).featureLevel);
        assertEquals(EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                EntitlementPolicy.evaluate(
                        active,
                        unavailableRemote,
                        validation + EntitlementPolicy.OFFLINE_GRACE_MILLIS
                ).featureLevel);
        assertEquals(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                EntitlementPolicy.evaluate(
                        active,
                        unavailableRemote,
                        validation + EntitlementPolicy.OFFLINE_GRACE_MILLIS + 1
                ).featureLevel);

        EntitlementPolicy.Evidence unavailableLifetime = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.UNAVAILABLE, validation, null, false);
        assertEquals(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                EntitlementPolicy.evaluate(
                        unavailableLifetime,
                        inactive,
                        validation + 365L * 24 * 60 * 60 * 1000
                ).featureLevel);
    }

    private static void testKnownExpiryRevocationAndClockRollback() {
        long validation = 1_000_000L;
        EntitlementPolicy.Evidence activeAnchor = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.ACTIVE, validation, null, false);
        EntitlementPolicy.Evidence expired = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.UNAVAILABLE,
                validation,
                validation + 30_000L,
                false);
        EntitlementPolicy.Evidence revoked = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.UNAVAILABLE, validation, null, true);
        EntitlementPolicy.Evidence unavailable = new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.UNAVAILABLE, validation, null, false);

        assertEquals(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                EntitlementPolicy.evaluate(activeAnchor, expired, validation + 60_000L)
                        .featureLevel);
        assertEquals(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                EntitlementPolicy.evaluate(activeAnchor, revoked, validation + 60_000L)
                        .featureLevel);
        assertEquals(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                EntitlementPolicy.evaluate(activeAnchor, unavailable, validation - 1)
                        .featureLevel);
    }

    private static void testSessionLeaseContinuityAndIsolation() {
        long acquiredAt = 1_000_000L;
        SessionEntitlementLease lease = SessionEntitlementLease.acquire(
                "remote-session-1",
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                new EntitlementPolicy.Decision(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH),
                acquiredAt);

        assertNotNull(lease, "An entitled remote session should acquire a lease");
        assertTrue(lease.authorizes(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                "remote-session-1"), "The active remote session should stay authorized");
        assertFalse(lease.authorizes(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                "remote-session-2"), "A lease must not authorize another session");
        assertNull(SessionEntitlementLease.acquire(
                "remote-session-2",
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                new EntitlementPolicy.Decision(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH),
                acquiredAt + 60_000L),
                "A new remote session must revalidate and fail after entitlement loss");

        SessionEntitlementLease anchorLease = SessionEntitlementLease.acquire(
                "local-session",
                EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                new EntitlementPolicy.Decision(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH),
                acquiredAt);
        assertNotNull(anchorLease, "Anchor ownership should acquire a local-session lease");
        assertFalse(anchorLease.authorizes(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                "local-session"), "A local-session lease must not upgrade to remote");
        assertNull(SessionEntitlementLease.acquire(
                "",
                EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                new EntitlementPolicy.Decision(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH),
                acquiredAt), "An empty session identifier must fail closed");
    }

    private static void testSessionLeaseSerialization() {
        SessionEntitlementLease lease = SessionEntitlementLease.acquire(
                "session|with unicode π",
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                new EntitlementPolicy.Decision(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH),
                1_000_000L);
        assertNotNull(lease, "The fixture lease should be created");

        assertEquals(lease, SessionEntitlementLease.fromPersistedValue(
                lease.persistedValue()));
        assertNull(SessionEntitlementLease.fromPersistedValue(""),
                "Empty persisted data must fail closed");
        assertNull(SessionEntitlementLease.fromPersistedValue("2|REMOTE_ANCHOR_WATCH|1|eA"),
                "Unknown lease versions must fail closed");
        assertNull(SessionEntitlementLease.fromPersistedValue("1|FREE|1|eA"),
                "A free lease must never restore as a paid-session lease");
    }

    private static void testStoreProductIdentifiersRemainStable() {
        assertEquals("anchor_watch_lifetime",
                StoreProductIdentifiers.ANCHOR_WATCH_LIFETIME);
        assertEquals("remote_anchor_watch",
                StoreProductIdentifiers.REMOTE_ANCHOR_WATCH);
        assertEquals("monthly", StoreProductIdentifiers.REMOTE_MONTHLY_BASE_PLAN);
        assertEquals("annual", StoreProductIdentifiers.REMOTE_ANNUAL_BASE_PLAN);
    }

    private static void testEntitlementCacheTransitionsAndSerialization() {
        long validation = 1_000_000L;
        EntitlementCache cache = new EntitlementCache();
        cache.recordActive(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH, validation, null);
        cache.recordActive(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                validation,
                validation + 60_000L);

        EntitlementCache restored = EntitlementCache.fromPersistedValue(
                cache.persistedValue());
        assertNotNull(restored, "A valid entitlement cache should restore");
        assertEquals(validation, restored.anchorLastValidatedActiveAtMillis);
        assertEquals(validation, restored.remoteLastValidatedActiveAtMillis);
        assertEquals(validation + 60_000L, restored.remoteKnownExpirationAtMillis);

        restored.recordInactive(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                validation + 60_000L,
                true);
        EntitlementPolicy.Evidence evidence = restored.unavailableEvidence(
                EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH);
        assertNull(evidence.lastValidatedActiveAtMillis,
                "Known inactive state must clear the active cache");
        assertTrue(evidence.knownRevoked,
                "Known revocation must survive an unavailable store");
        assertNull(EntitlementCache.fromPersistedValue("2|-|-|-|0"),
                "Unknown cache versions must fail closed");
    }

    private static void testPlayPurchaseSignatureVerification() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        String purchaseData = "{\"productId\":\"anchor_watch_lifetime\"}";
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(purchaseData.getBytes(StandardCharsets.UTF_8));
        String publicKey = Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        assertTrue(PlayPurchaseSignatureVerifier.verify(
                publicKey,
                purchaseData,
                signature), "A valid Google Play signature should verify");
        assertFalse(PlayPurchaseSignatureVerifier.verify(
                publicKey,
                purchaseData + "tampered",
                signature), "Tampered purchase data must fail verification");
        assertFalse(PlayPurchaseSignatureVerifier.verify(
                "",
                purchaseData,
                signature), "A missing Play public key must fail closed");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertNull(Object value, String message) {
        if (value != null) throw new AssertionError(message + ": " + value);
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) throw new AssertionError(message);
    }
}
