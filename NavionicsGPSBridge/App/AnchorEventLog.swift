import Foundation

@MainActor
final class AnchorEventLog {
    private enum DefaultsKey {
        static let events = "anchor.events"
        static let nextSequence = "anchor.events.nextSequence"
        static let localAcknowledgedSequence = "anchor.events.localAcknowledgedSequence"
    }

    private let defaults: UserDefaults
    private(set) var events: [AnchorSyncEvent]
    private var nextSequence: Int64
    private(set) var localAcknowledgedSequence: Int64

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: DefaultsKey.events),
           let decoded = try? AnchorSyncProtocol.decoder.decode([AnchorSyncEvent].self, from: data) {
            events = decoded
        } else {
            events = []
        }
        nextSequence = max(
            Int64(defaults.integer(forKey: DefaultsKey.nextSequence)),
            (events.last?.sequence ?? 0) + 1,
            1
        )
        localAcknowledgedSequence = Int64(
            defaults.integer(forKey: DefaultsKey.localAcknowledgedSequence)
        )
    }

    @discardableResult
    func append(sessionID: String?, kind: String, reason: String) -> AnchorSyncEvent {
        let event = AnchorSyncEvent(
            id: UUID().uuidString.lowercased(),
            sequence: nextSequence,
            sessionID: sessionID,
            kind: kind,
            occurredAtMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000),
            reason: reason
        )
        nextSequence += 1
        events.append(event)
        if events.count > 100 { events.removeFirst(events.count - 100) }
        persist()
        return event
    }

    func acknowledgeLocally() {
        localAcknowledgedSequence = events.last?.sequence ?? localAcknowledgedSequence
        persist()
    }

    var latestUnacknowledged: AnchorSyncEvent? {
        events.last { $0.sequence > localAcknowledgedSequence }
    }

    private func persist() {
        defaults.set(try? AnchorSyncProtocol.encoder.encode(events), forKey: DefaultsKey.events)
        defaults.set(nextSequence, forKey: DefaultsKey.nextSequence)
        defaults.set(localAcknowledgedSequence, forKey: DefaultsKey.localAcknowledgedSequence)
    }
}
