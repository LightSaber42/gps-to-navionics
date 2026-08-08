import Foundation

enum AnchorSyncProtocol {
    static let version = 1
    static let port: UInt16 = 11_124
    static let heartbeatSeconds: TimeInterval = 10
    static let connectionLostSeconds: TimeInterval = 30
    static let historyPageSize = 250
    static let maximumLineBytes = 262_144

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    static let decoder = JSONDecoder()

    static func messageType(in data: Data) throws -> String {
        let envelope = try decoder.decode(Envelope.self, from: data)
        guard envelope.version == version else {
            throw AnchorSyncProtocolError.unsupportedVersion(envelope.version)
        }
        return envelope.type
    }

    struct Envelope: Codable, Sendable {
        let version: Int
        let type: String

        enum CodingKeys: String, CodingKey {
            case version = "v"
            case type
        }
    }
}

enum AnchorSyncProtocolError: LocalizedError {
    case unsupportedVersion(Int)
    case unexpectedMessage(String)
    case invalidFrame
    case authenticationFailed
    case replayedFrame

    var errorDescription: String? {
        switch self {
        case .unsupportedVersion(let version):
            "Unsupported anchor-sync protocol version \(version)."
        case .unexpectedMessage(let type):
            "Unexpected anchor-sync message \(type)."
        case .invalidFrame:
            "The anchor-sync frame is invalid."
        case .authenticationFailed:
            "The paired device could not be authenticated."
        case .replayedFrame:
            "The anchor-sync frame sequence is invalid."
        }
    }
}

struct AnchorSyncEvent: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let sequence: Int64
    let sessionID: String?
    let kind: String
    let occurredAtMilliseconds: Int64
    let reason: String

    enum CodingKeys: String, CodingKey {
        case id
        case sequence
        case sessionID = "session_id"
        case kind
        case occurredAtMilliseconds = "occurred_at_ms"
        case reason
    }
}

struct AnchorSyncPoint: Codable, Identifiable, Equatable, Sendable {
    let sequence: Int64
    let recordedAtMilliseconds: Int64
    let eastMeters: Double
    let northMeters: Double
    let accuracyMeters: Double

    var id: Int64 { sequence }

    enum CodingKeys: String, CodingKey {
        case sequence
        case recordedAtMilliseconds = "recorded_at_ms"
        case eastMeters = "east_m"
        case northMeters = "north_m"
        case accuracyMeters = "accuracy_m"
    }
}

struct AnchorSyncState: Codable, Equatable, Sendable {
    let version = AnchorSyncProtocol.version
    let type = "state"
    let sessionID: String?
    let armed: Bool
    let mode: String
    let radiusMeters: Double
    let distanceMeters: Double?
    let accuracyMeters: Double?
    let latestSequence: Int64
    let batteryPercent: Int
    let charging: Bool
    let serverTimeMilliseconds: Int64
    let events: [AnchorSyncEvent]

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case type
        case sessionID = "session_id"
        case armed
        case mode
        case radiusMeters = "radius_m"
        case distanceMeters = "distance_m"
        case accuracyMeters = "accuracy_m"
        case latestSequence = "latest_sequence"
        case batteryPercent = "battery_percent"
        case charging
        case serverTimeMilliseconds = "server_time_ms"
        case events
    }
}

struct AnchorSyncHistoryRequest: Codable, Sendable {
    let version = AnchorSyncProtocol.version
    let type = "history_request"
    let sessionID: String
    let afterSequence: Int64

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case type
        case sessionID = "session_id"
        case afterSequence = "after_sequence"
    }
}

struct AnchorSyncHistory: Codable, Sendable {
    let version = AnchorSyncProtocol.version
    let type = "history"
    let sessionID: String
    let points: [AnchorSyncPoint]
    let complete: Bool

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case type
        case sessionID = "session_id"
        case points
        case complete
    }
}

struct AnchorSyncClientStatus: Codable, Sendable {
    let version = AnchorSyncProtocol.version
    let type = "client_status"
    let soundReady: Bool
    let clientTimeMilliseconds: Int64

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case type
        case soundReady = "sound_ready"
        case clientTimeMilliseconds = "client_time_ms"
    }
}
