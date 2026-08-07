import Darwin
import Foundation

struct NetworkAddress: Identifiable, Hashable, Sendable {
    let interfaceName: String
    let address: String

    var id: String { "\(interfaceName)-\(address)" }
}

enum NetworkAddressProvider {
    static func currentIPv4Addresses() -> [NetworkAddress] {
        var firstAddress: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&firstAddress) == 0, let firstAddress else { return [] }
        defer { freeifaddrs(firstAddress) }

        var results: [NetworkAddress] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress

        while let interface = cursor?.pointee {
            defer { cursor = interface.ifa_next }
            guard let socketAddress = interface.ifa_addr else { continue }
            guard socketAddress.pointee.sa_family == UInt8(AF_INET) else { continue }
            guard interface.ifa_flags & UInt32(IFF_UP) != 0 else { continue }

            let name = String(cString: interface.ifa_name)
            guard isUsefulLocalInterface(name) else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }

            let address = String(cString: host)
            guard address != "127.0.0.1", !address.hasPrefix("169.254.") else { continue }
            results.append(NetworkAddress(interfaceName: friendlyName(name), address: address))
        }

        return Array(Set(results)).sorted { lhs, rhs in
            let lhsPriority = priority(for: lhs.interfaceName)
            let rhsPriority = priority(for: rhs.interfaceName)
            return lhsPriority == rhsPriority ? lhs.address < rhs.address : lhsPriority < rhsPriority
        }
    }

    private static func isUsefulLocalInterface(_ name: String) -> Bool {
        name.hasPrefix("en") || name.hasPrefix("bridge") || name.hasPrefix("ap")
    }

    private static func friendlyName(_ name: String) -> String {
        if name == "en0" { return "Wi‑Fi" }
        if name.hasPrefix("bridge") || name.hasPrefix("ap") { return "Hotspot" }
        return name
    }

    private static func priority(for name: String) -> Int {
        switch name {
        case "Wi‑Fi": return 0
        case "Hotspot": return 1
        default: return 2
        }
    }
}
