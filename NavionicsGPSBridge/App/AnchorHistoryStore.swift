import Foundation

@MainActor
final class AnchorHistoryStore {
    private enum DefaultsKey {
        static let sessionID = "anchor.history.sessionID"
        static let nextSequence = "anchor.history.nextSequence"
    }

    private let defaults: UserDefaults
    private let fileURL: URL
    private(set) var sessionID: String?
    private(set) var points: [AnchorSyncPoint] = []
    private var nextSequence: Int64 = 1
    private var lastRecordedAt: Date?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let directory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("AnchorWatch", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        fileURL = directory.appendingPathComponent("current-history.ndjson")
        sessionID = defaults.string(forKey: DefaultsKey.sessionID)
        nextSequence = max(1, Int64(defaults.integer(forKey: DefaultsKey.nextSequence)))
        points = loadPoints()
        if let last = points.last { nextSequence = max(nextSequence, last.sequence + 1) }
    }

    func startNewSession() -> String {
        let identifier = UUID().uuidString.lowercased()
        sessionID = identifier
        points.removeAll(keepingCapacity: true)
        nextSequence = 1
        lastRecordedAt = nil
        try? Data().write(to: fileURL, options: .atomic)
        persistMetadata()
        return identifier
    }

    @discardableResult
    func append(
        eastMeters: Double,
        northMeters: Double,
        accuracyMeters: Double,
        recordedAt: Date
    ) -> AnchorSyncPoint? {
        guard sessionID != nil else { return nil }
        if let lastRecordedAt,
           recordedAt.timeIntervalSince(lastRecordedAt) < 5 {
            return nil
        }
        let point = AnchorSyncPoint(
            sequence: nextSequence,
            recordedAtMilliseconds: Int64(recordedAt.timeIntervalSince1970 * 1_000),
            eastMeters: eastMeters,
            northMeters: northMeters,
            accuracyMeters: max(0, accuracyMeters)
        )
        nextSequence += 1
        lastRecordedAt = recordedAt
        points.append(point)
        appendToFile(point)
        persistMetadata()
        return point
    }

    var renderedPoints: [AnchorTrackPoint] {
        points.suffix(1_200).map {
            AnchorTrackPoint(
                id: Int(clamping: $0.sequence),
                eastMeters: $0.eastMeters,
                northMeters: $0.northMeters,
                accuracyMeters: $0.accuracyMeters
            )
        }
    }

    private func loadPoints() -> [AnchorSyncPoint] {
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty else { return [] }
        return data.split(separator: 0x0A).compactMap { line in
            try? AnchorSyncProtocol.decoder.decode(AnchorSyncPoint.self, from: Data(line))
        }
    }

    private func appendToFile(_ point: AnchorSyncPoint) {
        guard var encoded = try? AnchorSyncProtocol.encoder.encode(point) else { return }
        encoded.append(0x0A)
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            try? encoded.write(to: fileURL, options: .atomic)
            return
        }
        guard let handle = try? FileHandle(forWritingTo: fileURL) else { return }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: encoded)
        } catch {
            // A subsequent location fix can retry; alarm monitoring remains independent.
        }
    }

    private func persistMetadata() {
        defaults.set(sessionID, forKey: DefaultsKey.sessionID)
        defaults.set(nextSequence, forKey: DefaultsKey.nextSequence)
    }
}
