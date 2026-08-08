package com.jaco.navionicsgpsbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NetworkAddressProvider {
    private NetworkAddressProvider() {
    }

    static List<BridgeState.NetworkAddress> currentIpv4Addresses() {
        List<BridgeState.NetworkAddress> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return result;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || !isLocalNetworkInterface(networkInterface.getName())) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String text = address.getHostAddress();
                    if (text != null && seen.add(text)) {
                        result.add(new BridgeState.NetworkAddress(networkInterface.getName(), text));
                    }
                }
            }
        } catch (SocketException ignored) {
            // An empty list gives the UI a useful offline state.
        }

        result.sort(Comparator
                .comparingInt((BridgeState.NetworkAddress item) -> interfacePriority(item.interfaceName))
                .thenComparing(item -> item.interfaceName)
                .thenComparing(item -> item.address));
        return result;
    }

    static List<InetAddress> currentPrivateIpv4BroadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || !isLocalNetworkInterface(networkInterface.getName())) {
                    continue;
                }

                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (!(address instanceof Inet4Address)
                            || !address.isSiteLocalAddress()
                            || !(broadcast instanceof Inet4Address)) {
                        continue;
                    }
                    String text = broadcast.getHostAddress();
                    if (text != null && seen.add(text)) result.add(broadcast);
                }
            }
        } catch (SocketException ignored) {
            // An empty list is retried on the next network refresh.
        }
        return result;
    }

    private static boolean isLocalNetworkInterface(String name) {
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
                || name.startsWith("br");
    }

    private static int interfacePriority(String name) {
        if (name.startsWith("wlan") || name.startsWith("swlan")) return 0;
        if (name.startsWith("ap") || name.startsWith("rndis")) return 1;
        if (name.startsWith("eth")) return 2;
        return 3;
    }
}
