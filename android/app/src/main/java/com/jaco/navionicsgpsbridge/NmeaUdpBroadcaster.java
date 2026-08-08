package com.jaco.navionicsgpsbridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class NmeaUdpBroadcaster {
    private DatagramSocket socket;
    private int destinationPort;

    synchronized void start(int port) throws IOException {
        if (socket != null) return;

        DatagramSocket newSocket = new DatagramSocket();
        try {
            newSocket.setBroadcast(true);
            socket = newSocket;
            destinationPort = port;
        } catch (IOException | RuntimeException error) {
            newSocket.close();
            throw error;
        }
    }

    synchronized int send(String payload, List<InetAddress> broadcastAddresses) {
        if (socket == null || payload.isEmpty()) return 0;

        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        int successfulSends = 0;
        for (InetAddress broadcastAddress : broadcastAddresses) {
            try {
                socket.send(new DatagramPacket(
                        bytes,
                        bytes.length,
                        broadcastAddress,
                        destinationPort));
                successfulSends++;
            } catch (IOException ignored) {
                // Another interface may still be able to deliver the same GPS payload.
            }
        }
        return successfulSends;
    }

    synchronized void stop() {
        DatagramSocket activeSocket = socket;
        socket = null;
        destinationPort = 0;
        if (activeSocket != null) activeSocket.close();
    }
}
