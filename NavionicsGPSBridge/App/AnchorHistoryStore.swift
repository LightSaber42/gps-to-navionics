import Foundation

@MainActor
final class AnchorHistoryStore {
    static let maximumPointCount = 1_024

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
    private var needsRewrite = false

    init(defaults: UserDefaults = .standard, fileURL: URL? = nil) {
        self.defaults = defaults
        let defaultDirectory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("AnchorWatch", isDirectory: true)
        let resolvedFileURL = fileURL
            ?? defaultDirectory.appendingPathComponent("current-history.ndjson")
        let directory = resolvedFileURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        self.fileURL = resolvedFileURL
        sessionID = defaults.string(forKey: DefaultsKey.sessionID)
        nextSequence = max(1, Int64(defaults.integer(forKey: DefaultsKey.nextSequence)))
        points = loadPoints()
        if compactIfNeeded() {
            needsRewrite = !rewriteFile()
        }
        if let last = points.last { nextSequence = max(nextSequence, last.sequence + 1) }
        if let last = points.last {
            lastRecordedAt = Date(
                timeIntervalSince1970: TimeInterval(last.recordedAtMilliseconds) / 1_000
            )
        }
    }

    func startNewSession() -> String {
        let identifier = UUID().uuidString.lowercased()
        sessionID = identifier
        points.removeAll(keepingCapacity: true)
        nextSequence = 1
        lastRecordedAt = nil
        needsRewrite = !rewriteFile()
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
        if compactIfNeeded() {
            needsRewrite = true
        }
        if needsRewrite {
            needsRewrite = !rewriteFile()
        } else {
            needsRewrite = !appendToFile(point)
        }
        persistMetadata()
        return point
    }

    var renderedPoints: [AnchorTrackPoint] {
        points.map {
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

    @discardableResult
    private func compactIfNeeded() -> Bool {
        var compacted = false
        while points.count >= Self.maximumPointCount {
            let lastIndex = points.index(before: points.endIndex)
            let alternatingStart = lastIndex.isMultiple(of: 2) ? 2 : 1
            var retained = [points[0]]
            retained.reserveCapacity((points.count / 2) + 1)
            if alternatingStart < points.count {
                for index in stride(from: alternatingStart, to: points.count, by: 2) {
                    retained.append(points[index])
                }
            }
            points = retained
            compacted = true
        }
        return compacted
    }

    private func rewriteFile() -> Bool {
        var encoded = Data()
        for point in points {
            guard let line = try? AnchorSyncProtocol.encoder.encode(point) else { return false }
            encoded.append(line)
            encoded.append(0x0A)
        }
        do {
            try encoded.write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    private func appendToFile(_ point: AnchorSyncPoint) -> Bool {
        guard var encoded = try? AnchorSyncProtocol.encoder.encode(point) else { return false }
        encoded.append(0x0A)
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            do {
                try encoded.write(to: fileURL, options: .atomic)
                return true
            } catch {
                return false
            }
        }
        guard let handle = try? FileHandle(forWritingTo: fileURL) else { return false }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: encoded)
            return true
        } catch {
            // A subsequent location fix can retry; alarm monitoring remains independent.
            return false
        }
    }

    private func persistMetadata() {
        defaults.set(sessionID, forKey: DefaultsKey.sessionID)
        defaults.set(nextSequence, forKey: DefaultsKey.nextSequence)
    }
}
