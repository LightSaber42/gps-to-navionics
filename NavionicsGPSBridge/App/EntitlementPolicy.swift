import Foundation

enum FeatureLevel: Int, Codable, Equatable, Sendable {
    case free
    case anchorWatch
    case remoteAnchorWatch
}

enum StoreProductIdentifiers {
    static let anchorWatchLifetime =
        "com.jaco.navionicsgpsbridge.anchorwatch.lifetime"
    static let remoteMonthly =
        "com.jaco.navionicsgpsbridge.remote.monthly"
    static let remoteAnnual =
        "com.jaco.navionicsgpsbridge.remote.annual"
}

enum StoreEntitlementStatus: Equatable, Sendable {
    case active
    case inactive
    case unavailable
}

struct EntitlementEvidence: Equatable, Sendable {
    let storeStatus: StoreEntitlementStatus
    let lastValidatedActiveAt: Date?
    let knownExpirationAt: Date?
    let isKnownRevoked: Bool

    init(
        storeStatus: StoreEntitlementStatus,
        lastValidatedActiveAt: Date? = nil,
        knownExpirationAt: Date? = nil,
        isKnownRevoked: Bool = false
    ) {
        self.storeStatus = storeStatus
        self.lastValidatedActiveAt = lastValidatedActiveAt
        self.knownExpirationAt = knownExpirationAt
        self.isKnownRevoked = isKnownRevoked
    }
}

struct EntitlementDecision: Equatable, Sendable {
    let featureLevel: FeatureLevel
}

struct EntitlementCache: Codable, Equatable, Sendable {
    private(set) var anchorLastValidatedActiveAt: Date?
    private(set) var remoteLastValidatedActiveAt: Date?
    private(set) var remoteKnownExpirationAt: Date?
    private(set) var remoteKnownRevoked = false

    mutating func recordActive(
        _ feature: FeatureLevel,
        at validationDate: Date,
        expiration: Date? = nil
    ) {
        switch feature {
        case .free:
            break
        case .anchorWatch:
            anchorLastValidatedActiveAt = validationDate
        case .remoteAnchorWatch:
            remoteLastValidatedActiveAt = validationDate
            remoteKnownExpirationAt = expiration
            remoteKnownRevoked = false
        }
    }

    mutating func recordInactive(
        _ feature: FeatureLevel,
        knownExpirationAt: Date? = nil,
        knownRevoked: Bool = false
    ) {
        switch feature {
        case .free:
            break
        case .anchorWatch:
            anchorLastValidatedActiveAt = nil
        case .remoteAnchorWatch:
            remoteLastValidatedActiveAt = nil
            remoteKnownExpirationAt = knownExpirationAt
            remoteKnownRevoked = knownRevoked
        }
    }

    func unavailableEvidence(for feature: FeatureLevel) -> EntitlementEvidence {
        switch feature {
        case .free:
            return EntitlementEvidence(storeStatus: .inactive)
        case .anchorWatch:
            return EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: anchorLastValidatedActiveAt
            )
        case .remoteAnchorWatch:
            return EntitlementEvidence(
                storeStatus: .unavailable,
                lastValidatedActiveAt: remoteLastValidatedActiveAt,
                knownExpirationAt: remoteKnownExpirationAt,
                isKnownRevoked: remoteKnownRevoked
            )
        }
    }
}

struct EntitlementCacheStore {
    private static let storageKey = "entitlementValidationCache.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> EntitlementCache {
        guard let data = defaults.data(forKey: Self.storageKey),
              let cache = try? JSONDecoder().decode(EntitlementCache.self, from: data) else {
            return EntitlementCache()
        }
        return cache
    }

    func save(_ cache: EntitlementCache) {
        guard let data = try? JSONEncoder().encode(cache) else {
            return
        }
        defaults.set(data, forKey: Self.storageKey)
    }
}

struct SessionEntitlementLease: Codable, Equatable, Sendable {
    let sessionID: String
    let featureLevel: FeatureLevel
    let acquiredAt: Date

    static func acquire(
        sessionID: String,
        requiredFeature: FeatureLevel,
        decision: EntitlementDecision,
        at now: Date
    ) -> SessionEntitlementLease? {
        guard !sessionID.isEmpty,
              requiredFeature != .free,
              decision.featureLevel.rawValue >= requiredFeature.rawValue else {
            return nil
        }
        return SessionEntitlementLease(
            sessionID: sessionID,
            featureLevel: requiredFeature,
            acquiredAt: now
        )
    }

    func authorizes(_ requiredFeature: FeatureLevel, for requestedSessionID: String) -> Bool {
        !sessionID.isEmpty
            && sessionID == requestedSessionID
            && requiredFeature != .free
            && featureLevel.rawValue >= requiredFeature.rawValue
    }
}

struct SessionEntitlementLeaseStore {
    private static let storageKey = "activeSessionEntitlementLease.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    @discardableResult
    func save(_ lease: SessionEntitlementLease) -> Bool {
        guard let data = try? JSONEncoder().encode(lease) else {
            return false
        }
        defaults.set(data, forKey: Self.storageKey)
        return defaults.data(forKey: Self.storageKey) == data
    }

    func load() -> SessionEntitlementLease? {
        guard let data = defaults.data(forKey: Self.storageKey),
              let lease = try? JSONDecoder().decode(SessionEntitlementLease.self, from: data),
              !lease.sessionID.isEmpty,
              lease.featureLevel != .free else {
            return nil
        }
        return lease
    }

    func clear() {
        defaults.removeObject(forKey: Self.storageKey)
    }
}

enum EntitlementSessionAccess {
    static func authorizes(
        _ requiredFeature: FeatureLevel,
        decision: EntitlementDecision,
        activeSessionID: String?,
        lease: SessionEntitlementLease?
    ) -> Bool {
        if requiredFeature == .free {
            return true
        }
        if decision.featureLevel.rawValue >= requiredFeature.rawValue {
            return true
        }
        guard let activeSessionID, let lease else {
            return false
        }
        return lease.authorizes(requiredFeature, for: activeSessionID)
    }

    static func leaseForStartingSession(
        sessionID: String,
        decision: EntitlementDecision,
        at now: Date
    ) -> SessionEntitlementLease? {
        guard decision.featureLevel.rawValue >= FeatureLevel.anchorWatch.rawValue else {
            return nil
        }
        return SessionEntitlementLease.acquire(
            sessionID: sessionID,
            requiredFeature: decision.featureLevel,
            decision: decision,
            at: now
        )
    }

    static func upgradedLease(
        _ lease: SessionEntitlementLease,
        activeSessionID: String,
        decision: EntitlementDecision,
        at now: Date
    ) -> SessionEntitlementLease? {
        guard lease.sessionID == activeSessionID,
              decision.featureLevel.rawValue > lease.featureLevel.rawValue else {
            return nil
        }
        return SessionEntitlementLease.acquire(
            sessionID: activeSessionID,
            requiredFeature: decision.featureLevel,
            decision: decision,
            at: now
        )
    }
}

enum EntitlementPolicy {
    static let offlineGraceInterval: TimeInterval = 7 * 24 * 60 * 60

    static func evaluate(
        anchorWatch: EntitlementEvidence,
        remoteAnchorWatch: EntitlementEvidence,
        at now: Date
    ) -> EntitlementDecision {
        let anchorIsActive = isActive(
            anchorWatch,
            at: now,
            unavailableCacheInterval: nil
        )
        let remoteIsActive = isActive(
            remoteAnchorWatch,
            at: now,
            unavailableCacheInterval: offlineGraceInterval
        )
        if anchorIsActive, remoteIsActive {
            return EntitlementDecision(featureLevel: .remoteAnchorWatch)
        }
        return EntitlementDecision(
            featureLevel: anchorIsActive ? .anchorWatch : .free
        )
    }

    private static func isActive(
        _ evidence: EntitlementEvidence,
        at now: Date,
        unavailableCacheInterval: TimeInterval?
    ) -> Bool {
        if evidence.isKnownRevoked {
            return false
        }
        if let expiration = evidence.knownExpirationAt, expiration <= now {
            return false
        }

        switch evidence.storeStatus {
        case .active:
            return true
        case .inactive:
            return false
        case .unavailable:
            guard let validatedAt = evidence.lastValidatedActiveAt else {
                return false
            }
            guard let cacheInterval = unavailableCacheInterval else {
                return true
            }
            let elapsed = now.timeIntervalSince(validatedAt)
            return elapsed >= 0 && elapsed <= cacheInterval
        }
    }
}
