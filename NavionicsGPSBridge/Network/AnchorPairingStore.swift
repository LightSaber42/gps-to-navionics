import Foundation
import Security

struct AnchorPairingConfiguration: Equatable, Sendable {
    let deviceID: String
    let pairingKey: Data
}

final class AnchorPairingStore {
    private enum DefaultsKey {
        static let deviceID = "anchor.sync.deviceID"
    }

    private let defaults: UserDefaults
    private let keychainService = "com.jaco.navionicsgpsbridge.anchor-sync"
    private let keychainAccount = "host-pairing-key"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> AnchorPairingConfiguration? {
        guard let deviceID = defaults.string(forKey: DefaultsKey.deviceID),
              let existingKey = readKey(),
              existingKey.count == AnchorSyncCrypto.pairingKeyBytes else {
            return nil
        }
        return AnchorPairingConfiguration(deviceID: deviceID, pairingKey: existingKey)
    }

    func loadOrCreate() throws -> AnchorPairingConfiguration {
        let deviceID: String
        if let storedID = defaults.string(forKey: DefaultsKey.deviceID) {
            deviceID = storedID
        } else {
            deviceID = UUID().uuidString.lowercased()
            defaults.set(deviceID, forKey: DefaultsKey.deviceID)
        }

        if let existingKey = readKey(), existingKey.count == AnchorSyncCrypto.pairingKeyBytes {
            return AnchorPairingConfiguration(deviceID: deviceID, pairingKey: existingKey)
        }
        let key = try AnchorSyncCrypto.randomBytes(count: AnchorSyncCrypto.pairingKeyBytes)
        try writeKey(key)
        return AnchorPairingConfiguration(deviceID: deviceID, pairingKey: key)
    }

    func rotate() throws -> AnchorPairingConfiguration {
        let deviceID = UUID().uuidString.lowercased()
        let key = try AnchorSyncCrypto.randomBytes(count: AnchorSyncCrypto.pairingKeyBytes)
        defaults.set(deviceID, forKey: DefaultsKey.deviceID)
        try writeKey(key)
        return AnchorPairingConfiguration(deviceID: deviceID, pairingKey: key)
    }

    func pairingURL(
        configuration: AnchorPairingConfiguration,
        addresses: [NetworkAddress]
    ) -> URL? {
        guard !addresses.isEmpty else { return nil }
        var components = URLComponents()
        components.scheme = "navionicsgpsbridge"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(AnchorSyncProtocol.version)),
            URLQueryItem(name: "id", value: configuration.deviceID),
            URLQueryItem(name: "key", value: AnchorSyncCrypto.urlEncoded(configuration.pairingKey)),
            URLQueryItem(name: "hosts", value: addresses.map(\.address).joined(separator: ",")),
            URLQueryItem(name: "port", value: String(AnchorSyncProtocol.port))
        ]
        return components.url
    }

    private func readKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else {
            return nil
        }
        return item as? Data
    }

    private func writeKey(_ key: Data) throws {
        let lookup: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]
        let updateStatus = SecItemUpdate(
            lookup as CFDictionary,
            [kSecValueData as String: key] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus))
        }
        var add = lookup
        add[kSecValueData as String] = key
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(addStatus))
        }
    }
}
