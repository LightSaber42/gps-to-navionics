import Darwin
import Foundation

/// Includes VPN interfaces such as Tailscale in addition to Wi-Fi and hotspot addresses.
enum AnchorPairingAddressProvider {
    static func currentIPv4Addresses() -> [NetworkAddress] {
        var firstAddress: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&firstAddress) == 0, let firstAddress else { return [] }
        defer { freeifaddrs(firstAddress) }

        var result: [NetworkAddress] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let interface = cursor?.pointee {
            defer { cursor = interface.ifa_next }
            guard let socketAddress = interface.ifa_addr,
                  socketAddress.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else {
                continue
            }
            let interfaceName = String(cString: interface.ifa_name)
            guard isPairingInterface(interfaceName) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 else { continue }
            let address = String(cString: host)
            guard address != "127.0.0.1", !address.hasPrefix("169.254.") else { continue }
            result.append(NetworkAddress(interfaceName: interfaceName, address: address))
        }
        return Array(Set(result)).sorted { lhs, rhs in
            if lhs.address.hasPrefix("100.") != rhs.address.hasPrefix("100.") {
                return lhs.address.hasPrefix("100.")
            }
            return lhs.address < rhs.address
        }
    }

    private static func isPairingInterface(_ name: String) -> Bool {
        name.hasPrefix("en")
            || name.hasPrefix("bridge")
            || name.hasPrefix("ap")
            || name.hasPrefix("utun")
    }
}
