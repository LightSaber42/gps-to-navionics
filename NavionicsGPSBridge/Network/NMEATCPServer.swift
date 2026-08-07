import Foundation
import Network

final class NMEATCPServer: @unchecked Sendable {
    enum ListenerState: Equatable, Sendable {
        case stopped
        case starting
        case ready(UInt16)
        case failed(String)
    }

    var onStateChange: ((ListenerState) -> Void)?
    var onClientCountChange: ((Int) -> Void)?

    private let queue = DispatchQueue(label: "com.jaco.navionicsgpsbridge.tcp-server")
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]

    func start(port: UInt16) {
        queue.async { [weak self] in
            self?.startOnQueue(port: port)
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            listener?.cancel()
            listener = nil
            connections.values.forEach { $0.cancel() }
            connections.removeAll()
            onClientCountChange?(0)
            onStateChange?(.stopped)
        }
    }

    func send(sentences: [String]) {
        guard let payload = sentences.joined().data(using: .ascii) else { return }
        queue.async { [weak self] in
            guard let self else { return }
            for (identifier, connection) in connections {
                connection.send(content: payload, completion: .contentProcessed { [weak self] error in
                    guard error != nil else { return }
                    self?.queue.async {
                        self?.removeConnection(identifier)
                    }
                })
            }
        }
    }

    private func startOnQueue(port: UInt16) {
        guard listener == nil else { return }
        onStateChange?(.starting)

        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.noDelay = true
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.allowLocalEndpointReuse = true

            guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
                onStateChange?(.failed("Port \(port) is invalid."))
                return
            }

            let listener = try NWListener(using: parameters, on: endpointPort)
            self.listener = listener

            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self else { return }
                switch state {
                case .ready:
                    let readyPort = listener?.port?.rawValue ?? port
                    onStateChange?(.ready(readyPort))
                case .failed(let error):
                    onStateChange?(.failed(error.localizedDescription))
                    stopOnQueue()
                case .cancelled:
                    onStateChange?(.stopped)
                default:
                    break
                }
            }

            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.start(queue: queue)
        } catch {
            listener = nil
            onStateChange?(.failed(error.localizedDescription))
        }
    }

    private func accept(_ connection: NWConnection) {
        let identifier = UUID()
        connections[identifier] = connection
        onClientCountChange?(connections.count)

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.removeConnection(identifier)
            default:
                break
            }
        }
        connection.start(queue: queue)
        receive(on: connection, identifier: identifier)
    }

    private func receive(on connection: NWConnection, identifier: UUID) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] _, _, isComplete, error in
            guard let self else { return }
            if isComplete || error != nil {
                removeConnection(identifier)
            } else if connections[identifier] != nil {
                receive(on: connection, identifier: identifier)
            }
        }
    }

    private func removeConnection(_ identifier: UUID) {
        guard let connection = connections.removeValue(forKey: identifier) else { return }
        connection.cancel()
        onClientCountChange?(connections.count)
    }

    private func stopOnQueue() {
        listener?.cancel()
        listener = nil
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
        onClientCountChange?(0)
    }
}
