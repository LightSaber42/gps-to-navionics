package com.jaco.navionicsgpsbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Address list for pairing, including VPN interfaces such as Tailscale's tun device. */
final class AnchorPairingAddressProvider {
    private AnchorPairingAddressProvider() {
    }

    static List<BridgeState.NetworkAddress> currentIpv4Addresses() {
        List<BridgeState.NetworkAddress> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || !isPairingInterface(networkInterface.getName())) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) continue;
                    String text = address.getHostAddress();
                    if (text != null && seen.add(text)) {
                        result.add(new BridgeState.NetworkAddress(
                                networkInterface.getName(), text));
                    }
                }
            }
        } catch (SocketException ignored) {
            return result;
        }
        result.sort(Comparator
                .comparingInt((BridgeState.NetworkAddress item) ->
                        item.address.startsWith("100.") ? 0 : 1)
                .thenComparing(item -> item.address));
        return result;
    }

    private static boolean isPairingInterface(String name) {
        return name.startsWith("wlan")
                || name.startsWith("swlan")
                || name.startsWith("wifi")
                || name.startsWith("ap")
                || name.startsWith("softap")
                || name.startsWith("eth")
                || name.startsWith("rndis")
                || name.startsWith("usb")
                || name.startsWith("bnep")
                || name.startsWith("bt-pan")
                || name.startsWith("bridge")
                || name.startsWith("br")
                || name.startsWith("tun")
                || name.startsWith("tailscale");
    }
}
