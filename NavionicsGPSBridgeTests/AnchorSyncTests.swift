import Foundation
import Network
import XCTest
@testable import NavionicsGPSBridge

final class AnchorSyncTests: XCTestCase {
    @MainActor
    func testAnchorHistoryProgressivelyCompactsAndPersists() throws {
        let suiteName = "AnchorHistoryTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(suiteName, isDirectory: true)
        let fileURL = directory.appendingPathComponent("history.ndjson")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: directory)
        }

        var store: AnchorHistoryStore? = AnchorHistoryStore(
            defaults: defaults,
            fileURL: fileURL
        )
        _ = store?.startNewSession()
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        for sequence in 1...1_024 {
            XCTAssertNotNil(store?.append(
                eastMeters: Double(sequence),
                northMeters: 0,
                accuracyMeters: 3,
                recordedAt: start.addingTimeInterval(Double(sequence * 5))
            ))
        }

        XCTAssertEqual(store?.points.count, 513)
        XCTAssertEqual(store?.points.first?.sequence, 1)
        XCTAssertEqual(store?.points.last?.sequence, 1_024)
        XCTAssertEqual(store?.points.prefix(3).map(\.sequence), [1, 2, 4])

        for sequence in 1_025...1_545 {
            _ = store?.append(
                eastMeters: Double(sequence),
                northMeters: 0,
                accuracyMeters: 3,
                recordedAt: start.addingTimeInterval(Double(sequence * 5))
            )
        }
        XCTAssertLessThan(try XCTUnwrap(store?.points.count), AnchorHistoryStore.maximumPointCount)
        XCTAssertEqual(store?.points.first?.sequence, 1)
        XCTAssertEqual(store?.points.last?.sequence, 1_545)
        XCTAssertEqual(store?.points.suffix(10).map(\.sequence), Array(1_536...1_545))
        XCTAssertEqual(store?.renderedPoints.count, store?.points.count)

        store = nil
        let restored = AnchorHistoryStore(defaults: defaults, fileURL: fileURL)
        XCTAssertEqual(restored.points.first?.sequence, 1)
        XCTAssertEqual(restored.points.last?.sequence, 1_545)
        XCTAssertNil(restored.append(
            eastMeters: 0,
            northMeters: 0,
            accuracyMeters: 3,
            recordedAt: start.addingTimeInterval(Double(1_545 * 5 + 1))
        ))
        XCTAssertEqual(restored.append(
            eastMeters: 0,
            northMeters: 0,
            accuracyMeters: 3,
            recordedAt: start.addingTimeInterval(Double(1_546 * 5))
        )?.sequence, 1_546)
    }

    func testEncryptedFrameRoundTripsAndCarriesSequence() throws {
        let pairingKey = Data(repeating: 0x41, count: AnchorSyncCrypto.pairingKeyBytes)
        let clientNonce = Data(repeating: 0x42, count: AnchorSyncCrypto.nonceBytes)
        let serverNonce = Data(repeating: 0x43, count: AnchorSyncCrypto.nonceBytes)
        let sessionKey = AnchorSyncCrypto.sessionKey(
            pairingKey: pairingKey,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        let plaintext = Data(#"{"type":"state","v":1}"#.utf8)

        let encoded = try AnchorSyncCrypto.seal(
            sessionKey: sessionKey,
            sequence: 42,
            plaintext: plaintext,
            direction: "server"
        )
        let opened = try AnchorSyncCrypto.open(
            sessionKey: sessionKey,
            encodedFrame: encoded,
            direction: "server"
        )

        XCTAssertEqual(opened.sequence, 42)
        XCTAssertEqual(opened.plaintext, plaintext)
    }

    func testHandshakeMatchesCrossPlatformVectors() {
        let pairingKey = Data(repeating: 0x41, count: AnchorSyncCrypto.pairingKeyBytes)
        let clientNonce = Data(repeating: 0x42, count: AnchorSyncCrypto.nonceBytes)
        let serverNonce = Data(repeating: 0x43, count: AnchorSyncCrypto.nonceBytes)

        XCTAssertEqual(
            AnchorSyncCrypto.urlEncoded(AnchorSyncCrypto.clientProof(
                pairingKey: pairingKey,
                clientNonce: clientNonce
            )),
            "BUVzSnonV4yK7x8xd7kQtCfcFvp0IPlntX7Y5WlQKnY"
        )
        XCTAssertEqual(
            AnchorSyncCrypto.urlEncoded(AnchorSyncCrypto.serverProof(
                pairingKey: pairingKey,
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )),
            "d2bKxGA0iQKsccMawRbZuUJu1I5uzag3RjD2cuwK2lc"
        )
        XCTAssertEqual(
            AnchorSyncCrypto.urlEncoded(AnchorSyncCrypto.sessionKey(
                pairingKey: pairingKey,
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )),
            "MCbfxCzLsIiZ2bs4xaPJxgBtIkTyXmvISKx6PGtXg4k"
        )
    }

    func testEncryptedFrameRejectsWrongDirection() throws {
        let key = Data(repeating: 0x11, count: AnchorSyncCrypto.pairingKeyBytes)
        let encoded = try AnchorSyncCrypto.seal(
            sessionKey: key,
            sequence: 1,
            plaintext: Data("alarm".utf8),
            direction: "server"
        )

        XCTAssertThrowsError(try AnchorSyncCrypto.open(
            sessionKey: key,
            encodedFrame: encoded,
            direction: "client"
        ))
    }

    func testStatePayloadRoundTripsIncludingDurableEvents() throws {
        let event = AnchorSyncEvent(
            id: "event-1",
            sequence: 7,
            sessionID: "session-1",
            kind: "drift",
            occurredAtMilliseconds: 1_700_000_000_000,
            reason: "Boat is outside the anchor limit."
        )
        let state = AnchorSyncState(
            sessionID: "session-1",
            armed: true,
            mode: "anchored",
            radiusMeters: 25,
            distanceMeters: 31.5,
            accuracyMeters: 3.2,
            latestSequence: 99,
            batteryPercent: 74,
            charging: false,
            serverTimeMilliseconds: 1_700_000_001_000,
            events: [event]
        )

        let data = try AnchorSyncProtocol.encoder.encode(state)

        XCTAssertEqual(try AnchorSyncProtocol.messageType(in: data), "state")
        XCTAssertEqual(try AnchorSyncProtocol.decoder.decode(AnchorSyncState.self, from: data), state)
    }

    func testHistoryPayloadPreservesPlotCoordinates() throws {
        let point = AnchorSyncPoint(
            sequence: 3,
            recordedAtMilliseconds: 1_700_000_000_000,
            eastMeters: 5.5,
            northMeters: -2.25,
            accuracyMeters: 4
        )
        let history = AnchorSyncHistory(
            sessionID: "session-1",
            points: [point],
            complete: true
        )

        let data = try AnchorSyncProtocol.encoder.encode(history)
        let decoded = try AnchorSyncProtocol.decoder.decode(AnchorSyncHistory.self, from: data)

        XCTAssertEqual(decoded.sessionID, history.sessionID)
        XCTAssertEqual(decoded.points, history.points)
        XCTAssertTrue(decoded.complete)
    }

    func testPairingURLRequiresAReachableAddress() {
        let configuration = AnchorPairingConfiguration(
            deviceID: "boat-1",
            pairingKey: Data(repeating: 0x22, count: AnchorSyncCrypto.pairingKeyBytes)
        )
        let store = AnchorPairingStore(defaults: UserDefaults(suiteName: #function)!)

        XCTAssertNil(store.pairingURL(configuration: configuration, addresses: []))
        XCTAssertEqual(
            store.pairingURL(
                configuration: configuration,
                addresses: [NetworkAddress(interfaceName: "utun3", address: "100.64.0.5")]
            )?.host,
            "pair"
        )
    }

    func testServerRetainsClientUntilHandshakeArrives() throws {
        let pairingKey = Data(repeating: 0x51, count: AnchorSyncCrypto.pairingKeyBytes)
        let clientNonce = Data(repeating: 0x52, count: AnchorSyncCrypto.nonceBytes)
        let proof = AnchorSyncCrypto.clientProof(
            pairingKey: pairingKey,
            clientNonce: clientNonce
        )
        let portNumber: UInt16 = 32_124
        let server = AnchorSyncServer()
        server.start(pairingKey: pairingKey, port: portNumber)
        defer { server.stop() }

        let listenerStarted = expectation(description: "Listener starts")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.25) {
            listenerStarted.fulfill()
        }
        wait(for: [listenerStarted], timeout: 1)

        let connectionReady = expectation(description: "Client connects")
        let welcomeReceived = expectation(description: "Server replies to HELLO")
        let connection = NWConnection(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: portNumber)!,
            using: .tcp
        )
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connectionReady.fulfill()
            case .failed(let error):
                XCTFail("Client connection failed: \(error)")
            default:
                break
            }
        }
        connection.start(queue: DispatchQueue(label: "anchor-sync-test-client"))
        defer { connection.cancel() }
        wait(for: [connectionReady], timeout: 2)

        let hello = "HELLO \(AnchorSyncCrypto.urlEncoded(clientNonce)) "
            + "\(AnchorSyncCrypto.urlEncoded(proof))\n"
        connection.send(content: Data(hello.utf8), completion: .contentProcessed { error in
            if let error {
                XCTFail("HELLO send failed: \(error)")
                welcomeReceived.fulfill()
                return
            }
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) {
                data, _, _, error in
                if let error {
                    XCTFail("WELCOME receive failed: \(error)")
                }
                let response = data.flatMap { String(data: $0, encoding: .utf8) }
                XCTAssertTrue(response?.hasPrefix("WELCOME ") == true)
                welcomeReceived.fulfill()
            }
        })
        wait(for: [welcomeReceived], timeout: 2)
    }
}
