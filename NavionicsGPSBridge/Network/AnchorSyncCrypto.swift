import CryptoKit
import Foundation

enum AnchorSyncCrypto {
    static let pairingKeyBytes = 32
    static let nonceBytes = 16

    struct OpenedFrame: Sendable {
        let sequence: UInt64
        let plaintext: Data
    }

    static func randomBytes(count: Int) throws -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, count, bytes.baseAddress!)
        }
        guard status == errSecSuccess else {
            throw AnchorSyncProtocolError.invalidFrame
        }
        return data
    }

    static func clientProof(pairingKey: Data, clientNonce: Data) -> Data {
        hmac(key: pairingKey, message: data("anchor-sync-client-v1") + clientNonce)
    }

    static func serverProof(pairingKey: Data, clientNonce: Data, serverNonce: Data) -> Data {
        hmac(
            key: pairingKey,
            message: data("anchor-sync-server-v1") + clientNonce + serverNonce
        )
    }

    static func sessionKey(pairingKey: Data, clientNonce: Data, serverNonce: Data) -> Data {
        hmac(
            key: pairingKey,
            message: data("anchor-sync-key-v1") + clientNonce + serverNonce
        )
    }

    static func seal(
        sessionKey: Data,
        sequence: UInt64,
        plaintext: Data,
        direction: String
    ) throws -> String {
        let sealed = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: sessionKey),
            authenticating: associatedData(direction: direction, sequence: sequence)
        )
        guard let combined = sealed.combined else {
            throw AnchorSyncProtocolError.invalidFrame
        }
        var sequenceBigEndian = sequence.bigEndian
        var frame = Data(bytes: &sequenceBigEndian, count: MemoryLayout<UInt64>.size)
        frame.append(combined)
        return frame.base64EncodedString().removingBase64Padding
    }

    static func open(
        sessionKey: Data,
        encodedFrame: String,
        direction: String
    ) throws -> OpenedFrame {
        guard let frame = Data(base64Unpadded: encodedFrame),
              frame.count >= MemoryLayout<UInt64>.size + 12 + 16 else {
            throw AnchorSyncProtocolError.invalidFrame
        }
        let sequence = frame.prefix(MemoryLayout<UInt64>.size).reduce(UInt64(0)) {
            ($0 << 8) | UInt64($1)
        }
        let combined = frame.dropFirst(MemoryLayout<UInt64>.size)
        let box = try AES.GCM.SealedBox(combined: combined)
        let plaintext = try AES.GCM.open(
            box,
            using: SymmetricKey(data: sessionKey),
            authenticating: associatedData(direction: direction, sequence: sequence)
        )
        return OpenedFrame(sequence: sequence, plaintext: plaintext)
    }

    static func urlEncoded(_ value: Data) -> String {
        value.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .removingBase64Padding
    }

    static func urlDecoded(_ value: String) -> Data? {
        Data(base64Unpadded: value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/"))
    }

    static func constantTimeEquals(_ first: Data, _ second: Data) -> Bool {
        guard first.count == second.count else { return false }
        var difference: UInt8 = 0
        for index in first.indices {
            difference |= first[index] ^ second[index]
        }
        return difference == 0
    }

    private static func hmac(key: Data, message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(
            for: message,
            using: SymmetricKey(data: key)
        ))
    }

    private static func associatedData(direction: String, sequence: UInt64) -> Data {
        var bigEndian = sequence.bigEndian
        var result = data("anchor-sync-\(direction)-v1")
        result.append(Data(bytes: &bigEndian, count: MemoryLayout<UInt64>.size))
        return result
    }

    private static func data(_ value: String) -> Data {
        Data(value.utf8)
    }
}

private extension String {
    var removingBase64Padding: String {
        trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }
}

private extension Data {
    init?(base64Unpadded value: String) {
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value + padding)
    }
}
