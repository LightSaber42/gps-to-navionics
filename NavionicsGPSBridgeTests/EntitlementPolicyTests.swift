import XCTest
@testable import NavionicsGPSBridge

final class EntitlementPolicyTests: XCTestCase {
    func testVerifiedAnchorOwnershipUnlocksAnchorWatch() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let decision = EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(
                storeStatus: .active,
                lastValidatedActiveAt: now
            ),
            remoteAnchorWatch: EntitlementEvidence(storeStatus: .inactive),
            at: now
        )

        XCTAssertEqual(decision.featureLevel, .anchorWatch)
    }

    func testRemoteSubscriptionRequiresAnchorWatchOwnership() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let active = EntitlementEvidence(
            storeStatus: .active,
            lastValidatedActiveAt: now
        )

        XCTAssertEqual(EntitlementPolicy.evaluate(
            anchorWatch: active,
            remoteAnchorWatch: active,
            at: now
        ).featureLevel, .remoteAnchorWatch)
        XCTAssertEqual(EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(storeStatus: .inactive),
            remoteAnchorWatch: active,
            at: now
        ).featureLevel, .free)
    }

    func testUnavailableRemoteStoreUsesSevenDayActiveCache() {
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let anchor = EntitlementEvidence(
            storeStatus: .active,
            lastValidatedActiveAt: validation
        )
        let unavailableRemote = EntitlementEvidence(
            storeStatus: .unavailable,
            lastValidatedActiveAt: validation
        )

        XCTAssertEqual(EntitlementPolicy.evaluate(
            anchorWatch: anchor,
            remoteAnchorWatch: unavailableRemote,
            at: validation.addingTimeInterval(7 * 24 * 60 * 60)
        ).featureLevel, .remoteAnchorWatch)
        XCTAssertEqual(EntitlementPolicy.evaluate(
            anchorWatch: anchor,
            remoteAnchorWatch: unavailableRemote,
            at: validation.addingTimeInterval(7 * 24 * 60 * 60 + 1)
        ).featureLevel, .anchorWatch)
    }

    func testKnownRemoteExpiryOrRevocationNeverUsesActiveCache() {
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let expired = EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(storeStatus: .active),
            remoteAnchorWatch: EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: validation,
                knownExpirationAt: validation.addingTimeInterval(30)
            ),
            at: validation.addingTimeInterval(60)
        )
        let revoked = EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(storeStatus: .active),
            remoteAnchorWatch: EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: validation,
                isKnownRevoked: true
            ),
            at: validation.addingTimeInterval(60)
        )

        XCTAssertEqual(expired.featureLevel, .anchorWatch)
        XCTAssertEqual(revoked.featureLevel, .anchorWatch)
    }

    func testClockRollbackDoesNotExtendRemoteCache() {
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let decision = EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(storeStatus: .active),
            remoteAnchorWatch: EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: validation
            ),
            at: validation.addingTimeInterval(-1)
        )

        XCTAssertEqual(decision.featureLevel, .anchorWatch)
    }

    func testPreviouslyVerifiedLifetimeOwnershipSurvivesUnavailableStore() {
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let decision = EntitlementPolicy.evaluate(
            anchorWatch: EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: validation
            ),
            remoteAnchorWatch: EntitlementEvidence(storeStatus: .inactive),
            at: validation.addingTimeInterval(365 * 24 * 60 * 60)
        )

        XCTAssertEqual(decision.featureLevel, .anchorWatch)
    }

    func testActiveSessionLeaseSurvivesEntitlementLossOnlyForThatSession() throws {
        let acquiredAt = Date(timeIntervalSince1970: 1_000_000)
        let lease = try XCTUnwrap(SessionEntitlementLease.acquire(
            sessionID: "remote-session-1",
            requiredFeature: .remoteAnchorWatch,
            decision: EntitlementDecision(featureLevel: .remoteAnchorWatch),
            at: acquiredAt
        ))

        let laterDecision = EntitlementDecision(featureLevel: .anchorWatch)
        XCTAssertEqual(lease.featureLevel, .remoteAnchorWatch)
        XCTAssertEqual(lease.acquiredAt, acquiredAt)
        XCTAssertTrue(lease.authorizes(.remoteAnchorWatch, for: "remote-session-1"))
        XCTAssertFalse(lease.authorizes(.remoteAnchorWatch, for: "remote-session-2"))
        XCTAssertNil(SessionEntitlementLease.acquire(
            sessionID: "remote-session-2",
            requiredFeature: .remoteAnchorWatch,
            decision: laterDecision,
            at: acquiredAt.addingTimeInterval(60)
        ))
    }

    func testLeaseDoesNotUpgradeAnAnchorOnlySession() throws {
        let lease = try XCTUnwrap(SessionEntitlementLease.acquire(
            sessionID: "local-session",
            requiredFeature: .anchorWatch,
            decision: EntitlementDecision(featureLevel: .remoteAnchorWatch),
            at: Date(timeIntervalSince1970: 1_000_000)
        ))

        XCTAssertTrue(lease.authorizes(.anchorWatch, for: "local-session"))
        XCTAssertFalse(lease.authorizes(.remoteAnchorWatch, for: "local-session"))
        XCTAssertNil(SessionEntitlementLease.acquire(
            sessionID: "",
            requiredFeature: .anchorWatch,
            decision: EntitlementDecision(featureLevel: .anchorWatch),
            at: Date(timeIntervalSince1970: 1_000_000)
        ))
    }

    func testSessionLeaseSurvivesStoreRecreationAndCanBeCleared() throws {
        let suiteName = "SessionEntitlementLeaseTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let lease = try XCTUnwrap(SessionEntitlementLease.acquire(
            sessionID: "persisted-session",
            requiredFeature: .remoteAnchorWatch,
            decision: EntitlementDecision(featureLevel: .remoteAnchorWatch),
            at: Date(timeIntervalSince1970: 1_000_000)
        ))

        SessionEntitlementLeaseStore(defaults: defaults).save(lease)
        let restored = SessionEntitlementLeaseStore(defaults: defaults).load()

        XCTAssertEqual(restored, lease)
        SessionEntitlementLeaseStore(defaults: defaults).clear()
        XCTAssertNil(SessionEntitlementLeaseStore(defaults: defaults).load())
    }

    func testStoreProductIdentifiersRemainStable() {
        XCTAssertEqual(
            StoreProductIdentifiers.anchorWatchLifetime,
            "com.jaco.navionicsgpsbridge.anchorwatch.lifetime"
        )
        XCTAssertEqual(
            StoreProductIdentifiers.remoteMonthly,
            "com.jaco.navionicsgpsbridge.remote.monthly"
        )
        XCTAssertEqual(
            StoreProductIdentifiers.remoteAnnual,
            "com.jaco.navionicsgpsbridge.remote.annual"
        )
    }

    func testEntitlementCachePersistsOnlyMinimumValidationEvidence() throws {
        let suiteName = "EntitlementCacheTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let expiration = validation.addingTimeInterval(24 * 60 * 60)
        var cache = EntitlementCache()
        cache.recordActive(.anchorWatch, at: validation)
        cache.recordActive(.remoteAnchorWatch, at: validation, expiration: expiration)

        EntitlementCacheStore(defaults: defaults).save(cache)
        let restored = EntitlementCacheStore(defaults: defaults).load()

        XCTAssertEqual(restored.anchorLastValidatedActiveAt, validation)
        XCTAssertEqual(restored.remoteLastValidatedActiveAt, validation)
        XCTAssertEqual(restored.remoteKnownExpirationAt, expiration)
        XCTAssertFalse(restored.remoteKnownRevoked)
    }

    func testAuthoritativeInactiveRemoteStateCannotResurrectFromCache() {
        let validation = Date(timeIntervalSince1970: 1_000_000)
        let expiration = validation.addingTimeInterval(60)
        var cache = EntitlementCache()
        cache.recordActive(.remoteAnchorWatch, at: validation, expiration: expiration)

        cache.recordInactive(
            .remoteAnchorWatch,
            knownExpirationAt: expiration,
            knownRevoked: true
        )
        let evidence = cache.unavailableEvidence(for: .remoteAnchorWatch)

        XCTAssertNil(evidence.lastValidatedActiveAt)
        XCTAssertEqual(evidence.knownExpirationAt, expiration)
        XCTAssertTrue(evidence.isKnownRevoked)
        XCTAssertEqual(evidence.storeStatus, .unavailable)
    }
}
