import Foundation
import Network

final class AnchorSyncServer: @unchecked Sendable {
    struct MonitorStatus: Equatable, Sendable {
        let connected: Bool
        let soundReady: Bool
    }

    var onMonitorStatusChange: ((MonitorStatus) -> Void)?
    var onError: ((String) -> Void)?

    private final class ClientConnection {
        let connection: NWConnection
        var buffer = Data()
        var sessionKey: Data?
        var clientNonce: Data?
        var receiveSequence: UInt64 = 0
        var sendSequence: UInt64 = 0
        var soundReady = false

        init(connection: NWConnection) {
            self.connection = connection
        }
    }

    private let queue = DispatchQueue(label: "com.jaco.navionicsgpsbridge.anchor-sync")
    private var listener: NWListener?
    private var heartbeatTimer: DispatchSourceTimer?
    private var client: ClientConnection?
    private var pairingKey = Data()
    private var latestState: AnchorSyncState?
    private var history: [AnchorSyncPoint] = []

    func start(pairingKey: Data, port: UInt16 = AnchorSyncProtocol.port) {
        queue.async { [weak self] in
            self?.startOnQueue(pairingKey: pairingKey, port: port)
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.stopOnQueue()
        }
    }

    func publish(
        state: AnchorSyncState,
        history: [AnchorSyncPoint],
        sendImmediately: Bool = false
    ) {
        queue.async { [weak self] in
            guard let self else { return }
            latestState = state
            self.history = history
            if sendImmediately { sendLatestState() }
        }
    }

    private func startOnQueue(pairingKey: Data, port: UInt16) {
        self.pairingKey = pairingKey
        guard listener == nil else { return }
        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.noDelay = true
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.allowLocalEndpointReuse = true
            guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
                reportError("Anchor-sync port \(port) is invalid.")
                return
            }
            let listener = try NWListener(using: parameters, on: endpointPort)
            self.listener = listener
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                if case .failed(let error) = state {
                    reportError("Anchor-sync listener failed: \(error.localizedDescription)")
                    stopOnQueue()
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.start(queue: queue)
            startHeartbeatTimer()
        } catch {
            reportError("Anchor-sync listener failed: \(error.localizedDescription)")
        }
    }

    private func stopOnQueue() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        listener?.cancel()
        listener = nil
        disconnectClient()
    }

    private func startHeartbeatTimer() {
        guard heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(
            deadline: .now() + AnchorSyncProtocol.heartbeatSeconds,
            repeating: AnchorSyncProtocol.heartbeatSeconds
        )
        timer.setEventHandler { [weak self] in
            self?.sendLatestState()
        }
        heartbeatTimer = timer
        timer.resume()
    }

    private func accept(_ connection: NWConnection) {
        let candidate = ClientConnection(connection: connection)
        connection.stateUpdateHandler = { [weak self, weak candidate] state in
            guard let self, let candidate else { return }
            switch state {
            case .failed, .cancelled:
                if client === candidate { disconnectClient() }
            default:
                break
            }
        }
        connection.start(queue: queue)
        receive(on: candidate)
    }

    private func receive(on candidate: ClientConnection) {
        candidate.connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 16_384
        ) { [weak self, candidate] data, _, complete, error in
            guard let self else { return }
            if let data { candidate.buffer.append(data) }
            do {
                try processLines(for: candidate)
            } catch {
                candidate.connection.cancel()
                if client === candidate { disconnectClient() }
                reportError(error.localizedDescription)
                return
            }
            if complete || error != nil {
                candidate.connection.cancel()
                if client === candidate { disconnectClient() }
            } else {
                receive(on: candidate)
            }
        }
    }

    private func processLines(for candidate: ClientConnection) throws {
        while let newline = candidate.buffer.firstIndex(of: 0x0A) {
            let lineData = candidate.buffer[..<newline]
            candidate.buffer.removeSubrange(...newline)
            guard lineData.count <= AnchorSyncProtocol.maximumLineBytes,
                  let line = String(data: lineData, encoding: .utf8) else {
                throw AnchorSyncProtocolError.invalidFrame
            }
            if candidate.sessionKey == nil {
                try authenticate(candidate, hello: line)
            } else {
                try processEncrypted(candidate, line: line)
            }
        }
        if candidate.buffer.count > AnchorSyncProtocol.maximumLineBytes {
            throw AnchorSyncProtocolError.invalidFrame
        }
    }

    private func authenticate(_ candidate: ClientConnection, hello: String) throws {
        let parts = hello.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3,
              parts[0] == "HELLO",
              let clientNonce = AnchorSyncCrypto.urlDecoded(String(parts[1])),
              clientNonce.count == AnchorSyncCrypto.nonceBytes,
              let receivedProof = AnchorSyncCrypto.urlDecoded(String(parts[2])) else {
            throw AnchorSyncProtocolError.authenticationFailed
        }
        let expectedProof = AnchorSyncCrypto.clientProof(
            pairingKey: pairingKey,
            clientNonce: clientNonce
        )
        guard AnchorSyncCrypto.constantTimeEquals(receivedProof, expectedProof) else {
            throw AnchorSyncProtocolError.authenticationFailed
        }
        let serverNonce = try AnchorSyncCrypto.randomBytes(count: AnchorSyncCrypto.nonceBytes)
        let proof = AnchorSyncCrypto.serverProof(
            pairingKey: pairingKey,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        candidate.clientNonce = clientNonce
        candidate.sessionKey = AnchorSyncCrypto.sessionKey(
            pairingKey: pairingKey,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        let welcome = "WELCOME \(AnchorSyncCrypto.urlEncoded(serverNonce)) "
            + "\(AnchorSyncCrypto.urlEncoded(proof))\n"
        candidate.connection.send(content: Data(welcome.utf8), completion: .contentProcessed { _ in })

        client?.connection.cancel()
        client = candidate
        notifyMonitorStatus()
        sendLatestState()
    }

    private func processEncrypted(_ candidate: ClientConnection, line: String) throws {
        guard client === candidate,
              line.hasPrefix("FRAME "),
              let sessionKey = candidate.sessionKey else {
            throw AnchorSyncProtocolError.invalidFrame
        }
        let encodedFrame = String(line.dropFirst("FRAME ".count))
        let opened = try AnchorSyncCrypto.open(
            sessionKey: sessionKey,
            encodedFrame: encodedFrame,
            direction: "c2s"
        )
        guard opened.sequence == candidate.receiveSequence + 1 else {
            throw AnchorSyncProtocolError.replayedFrame
        }
        candidate.receiveSequence = opened.sequence
        let type = try AnchorSyncProtocol.messageType(in: opened.plaintext)
        switch type {
        case "client_status":
            let status = try AnchorSyncProtocol.decoder.decode(
                AnchorSyncClientStatus.self,
                from: opened.plaintext
            )
            candidate.soundReady = status.soundReady
            notifyMonitorStatus()
        case "history_request":
            let request = try AnchorSyncProtocol.decoder.decode(
                AnchorSyncHistoryRequest.self,
                from: opened.plaintext
            )
            sendHistory(request: request, to: candidate)
        default:
            throw AnchorSyncProtocolError.unexpectedMessage(type)
        }
    }

    private func sendLatestState() {
        guard let latestState, let client else { return }
        do {
            try send(AnchorSyncProtocol.encoder.encode(latestState), to: client)
        } catch {
            reportError("Could not send anchor state: \(error.localizedDescription)")
            disconnectClient()
        }
    }

    private func sendHistory(request: AnchorSyncHistoryRequest, to candidate: ClientConnection) {
        guard request.sessionID == latestState?.sessionID else { return }
        let remaining = history.filter { $0.sequence > request.afterSequence }
        let page = Array(remaining.prefix(AnchorSyncProtocol.historyPageSize))
        let message = AnchorSyncHistory(
            sessionID: request.sessionID,
            points: page,
            complete: page.count == remaining.count
        )
        do {
            try send(AnchorSyncProtocol.encoder.encode(message), to: candidate)
        } catch {
            reportError("Could not send anchor history: \(error.localizedDescription)")
            disconnectClient()
        }
    }

    private func send(_ plaintext: Data, to candidate: ClientConnection) throws {
        guard let sessionKey = candidate.sessionKey else {
            throw AnchorSyncProtocolError.authenticationFailed
        }
        candidate.sendSequence += 1
        let frame = try AnchorSyncCrypto.seal(
            sessionKey: sessionKey,
            sequence: candidate.sendSequence,
            plaintext: plaintext,
            direction: "s2c"
        )
        let line = Data("FRAME \(frame)\n".utf8)
        candidate.connection.send(content: line, completion: .contentProcessed { [weak self] error in
            guard error != nil else { return }
            self?.queue.async { self?.disconnectClient() }
        })
    }

    private func disconnectClient() {
        client?.connection.cancel()
        client = nil
        notifyMonitorStatus()
    }

    private func notifyMonitorStatus() {
        let status = MonitorStatus(
            connected: client?.sessionKey != nil,
            soundReady: client?.soundReady ?? false
        )
        onMonitorStatusChange?(status)
    }

    private func reportError(_ message: String) {
        onError?(message)
    }
}
