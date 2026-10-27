import Foundation
import StoreKit

enum PurchaseActionState: Equatable {
    case idle
    case loadingProducts
    case purchasing
    case pending
    case cancelled
    case restoring
    case failed(String)
}

enum RemoteSubscriptionState: Equatable {
    case unavailable
    case notSubscribed
    case subscribed
    case inGracePeriod
    case inBillingRetry
    case expired
    case revoked
}

@MainActor
final class PurchaseStore: ObservableObject {
    @Published private(set) var products: [String: Product] = [:]
    @Published private(set) var entitlementDecision: EntitlementDecision
    @Published private(set) var actionState = PurchaseActionState.idle
    @Published private(set) var remoteSubscriptionState =
        RemoteSubscriptionState.unavailable
    @Published private(set) var lastEntitlementRefreshAt: Date?

    private let cacheStore: EntitlementCacheStore
    private var cache: EntitlementCache
    private var transactionListener: Task<Void, Never>?
    private var started = false

    init(cacheStore: EntitlementCacheStore = EntitlementCacheStore()) {
        self.cacheStore = cacheStore
        cache = cacheStore.load()
        entitlementDecision = EntitlementPolicy.evaluate(
            anchorWatch: cache.unavailableEvidence(for: .anchorWatch),
            remoteAnchorWatch: cache.unavailableEvidence(for: .remoteAnchorWatch),
            at: Date()
        )
    }

    deinit {
        transactionListener?.cancel()
    }

    var anchorWatchPrice: String? {
        products[StoreProductIdentifiers.anchorWatchLifetime]?.displayPrice
    }

    var remoteMonthlyPrice: String? {
        products[StoreProductIdentifiers.remoteMonthly]?.displayPrice
    }

    var remoteAnnualPrice: String? {
        products[StoreProductIdentifiers.remoteAnnual]?.displayPrice
    }

    func start() {
        guard !started else { return }
        started = true
        transactionListener = Task { [weak self] in
            await self?.observeTransactions()
        }
        Task { [weak self] in
            await self?.refresh()
        }
    }

    func refresh() async {
        let storeAvailable = await loadProducts()
        if storeAvailable {
            await refreshRemoteSubscriptionState()
        } else {
            remoteSubscriptionState = .unavailable
        }
        await refreshEntitlements(storeAvailable: storeAvailable)
    }

    func purchase(productID: String) async {
        if [
            StoreProductIdentifiers.remoteMonthly,
            StoreProductIdentifiers.remoteAnnual
        ].contains(productID),
           entitlementDecision.featureLevel.rawValue < FeatureLevel.anchorWatch.rawValue {
            actionState = .failed(String(localized: "Unlock Anchor Watch before subscribing to Remote Anchor Watch."))
            return
        }
        guard let product = products[productID] else {
            actionState = .failed(String(localized: "This purchase is not available from the App Store right now."))
            return
        }
        actionState = .purchasing
        do {
            let result = try await product.purchase()
            switch result {
            case let .success(verification):
                guard let transaction = verifiedTransaction(from: verification) else {
                    actionState = .failed(String(localized: "The App Store transaction could not be verified."))
                    return
                }
                await transaction.finish()
                await refreshEntitlements(storeAvailable: true)
                await refreshRemoteSubscriptionState()
                actionState = .idle
            case .pending:
                actionState = .pending
            case .userCancelled:
                actionState = .cancelled
            @unknown default:
                actionState = .failed(String(localized: "The App Store returned an unknown purchase result."))
            }
        } catch {
            actionState = .failed(error.localizedDescription)
        }
    }

    func restorePurchases() async {
        actionState = .restoring
        do {
            try await AppStore.sync()
            await refreshEntitlements(storeAvailable: true)
            await refreshRemoteSubscriptionState()
            actionState = .idle
        } catch {
            actionState = .failed(error.localizedDescription)
        }
    }

    func clearActionState() {
        actionState = .idle
    }

    private func loadProducts() async -> Bool {
        if products.isEmpty {
            actionState = .loadingProducts
        }
        do {
            let loadedProducts = try await Product.products(for: [
                StoreProductIdentifiers.anchorWatchLifetime,
                StoreProductIdentifiers.remoteMonthly,
                StoreProductIdentifiers.remoteAnnual
            ])
            products = Dictionary(uniqueKeysWithValues: loadedProducts.map { ($0.id, $0) })
            if actionState == .loadingProducts {
                actionState = .idle
            }
            return true
        } catch {
            actionState = .failed(error.localizedDescription)
            return false
        }
    }

    private func refreshEntitlements(storeAvailable: Bool) async {
        let now = Date()
        var activeTransactions: [String: StoreKit.Transaction] = [:]
        for await verification in StoreKit.Transaction.currentEntitlements {
            guard let transaction = verifiedTransaction(from: verification) else {
                continue
            }
            activeTransactions[transaction.productID] = transaction
            await transaction.finish()
        }

        let anchorTransaction = activeTransactions[
            StoreProductIdentifiers.anchorWatchLifetime
        ]
        let remoteTransaction = [
            activeTransactions[StoreProductIdentifiers.remoteMonthly],
            activeTransactions[StoreProductIdentifiers.remoteAnnual]
        ]
        .compactMap { $0 }
        .max { lhs, rhs in
            (lhs.expirationDate ?? .distantFuture) < (rhs.expirationDate ?? .distantFuture)
        }

        let anchorEvidence: EntitlementEvidence
        if anchorTransaction != nil {
            cache.recordActive(.anchorWatch, at: now)
            anchorEvidence = EntitlementEvidence(
                storeStatus: .active,
                lastValidatedActiveAt: now
            )
        } else if storeAvailable {
            cache.recordInactive(.anchorWatch)
            anchorEvidence = EntitlementEvidence(storeStatus: .inactive)
        } else {
            anchorEvidence = cache.unavailableEvidence(for: .anchorWatch)
        }

        let remoteEvidence: EntitlementEvidence
        if let remoteTransaction {
            cache.recordActive(
                .remoteAnchorWatch,
                at: now,
                expiration: remoteTransaction.expirationDate
            )
            remoteEvidence = EntitlementEvidence(
                storeStatus: .active,
                lastValidatedActiveAt: now,
                knownExpirationAt: remoteTransaction.expirationDate
            )
        } else if storeAvailable {
            let latestTransactions = await latestRemoteTransactions()
            let knownExpiration = latestTransactions.compactMap(\.expirationDate).max()
            let knownRevoked = latestTransactions.contains { $0.revocationDate != nil }
            cache.recordInactive(
                .remoteAnchorWatch,
                knownExpirationAt: knownExpiration,
                knownRevoked: knownRevoked
            )
            remoteEvidence = EntitlementEvidence(
                storeStatus: .inactive,
                knownExpirationAt: knownExpiration,
                isKnownRevoked: knownRevoked
            )
        } else {
            remoteEvidence = cache.unavailableEvidence(for: .remoteAnchorWatch)
        }

        cacheStore.save(cache)
        entitlementDecision = EntitlementPolicy.evaluate(
            anchorWatch: anchorEvidence,
            remoteAnchorWatch: remoteEvidence,
            at: now
        )
        lastEntitlementRefreshAt = now
    }

    private func refreshRemoteSubscriptionState() async {
        let remoteProducts = [
            products[StoreProductIdentifiers.remoteMonthly],
            products[StoreProductIdentifiers.remoteAnnual]
        ].compactMap { $0 }
        guard !remoteProducts.isEmpty else {
            remoteSubscriptionState = .unavailable
            return
        }

        do {
            var renewalStates: [Product.SubscriptionInfo.RenewalState] = []
            for product in remoteProducts {
                guard let subscription = product.subscription else { continue }
                renewalStates.append(contentsOf: try await subscription.status.map(\.state))
            }
            if renewalStates.contains(.subscribed) {
                remoteSubscriptionState = .subscribed
            } else if renewalStates.contains(.inGracePeriod) {
                remoteSubscriptionState = .inGracePeriod
            } else if renewalStates.contains(.inBillingRetryPeriod) {
                remoteSubscriptionState = .inBillingRetry
            } else if renewalStates.contains(.revoked) {
                remoteSubscriptionState = .revoked
            } else if renewalStates.contains(.expired) {
                remoteSubscriptionState = .expired
            } else {
                remoteSubscriptionState = .notSubscribed
            }
        } catch {
            remoteSubscriptionState = .unavailable
        }
    }

    private func observeTransactions() async {
        for await verification in StoreKit.Transaction.updates {
            guard !Task.isCancelled else { return }
            guard let transaction = verifiedTransaction(from: verification) else {
                actionState = .failed(String(localized: "An App Store transaction update could not be verified."))
                continue
            }
            await transaction.finish()
            await refreshEntitlements(storeAvailable: true)
            await refreshRemoteSubscriptionState()
        }
    }

    private func latestRemoteTransactions() async -> [StoreKit.Transaction] {
        var transactions: [StoreKit.Transaction] = []
        for productID in [
            StoreProductIdentifiers.remoteMonthly,
            StoreProductIdentifiers.remoteAnnual
        ] {
            guard let verification = await StoreKit.Transaction.latest(for: productID),
                  let transaction = verifiedTransaction(from: verification) else {
                continue
            }
            transactions.append(transaction)
        }
        return transactions
    }

    private func verifiedTransaction(
        from result: VerificationResult<StoreKit.Transaction>
    ) -> StoreKit.Transaction? {
        guard case let .verified(transaction) = result else {
            return nil
        }
        return transaction
    }
}
