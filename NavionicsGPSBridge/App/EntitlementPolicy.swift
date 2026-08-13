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

    func save(_ lease: SessionEntitlementLease) {
        guard let data = try? JSONEncoder().encode(lease) else {
            return
        }
        defaults.set(data, forKey: Self.storageKey)
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
