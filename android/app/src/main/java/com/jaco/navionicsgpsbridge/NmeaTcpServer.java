package com.jaco.navionicsgpsbridge;

import android.content.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class NmeaTcpServer {
    interface Listener {
        void onListenerStateChanged(String description, String error);

        void onClientCountChanged(int count);
    }

    private final Context context;
    private final Listener listener;
    private final Map<Integer, Socket> clients = new ConcurrentHashMap<>();
    private final AtomicInteger nextClientId = new AtomicInteger();
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(
            namedThreadFactory("nmea-accept"));
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(
            namedThreadFactory("nmea-client"));

    private volatile boolean running;
    private volatile ServerSocket serverSocket;

    NmeaTcpServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start(int port) {
        if (running) {
            return;
        }
        running = true;
        listener.onListenerStateChanged(
                context.getString(R.string.starting_tcp_server), null);
        acceptExecutor.execute(() -> acceptConnections(port));
    }

    synchronized void stop() {
        running = false;
        ServerSocket activeServer = serverSocket;
        serverSocket = null;
        closeQuietly(activeServer);
        for (Socket socket : clients.values()) {
            closeQuietly(socket);
        }
        clients.clear();
        listener.onClientCountChanged(0);
        listener.onListenerStateChanged(context.getString(R.string.stopped), null);
    }

    void send(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        for (Map.Entry<Integer, Socket> item : clients.entrySet()) {
            try {
                item.getValue().getOutputStream().write(bytes);
                item.getValue().getOutputStream().flush();
            } catch (IOException error) {
                removeClient(item.getKey());
            }
        }
    }

    void shutdown() {
        stop();
        acceptExecutor.shutdownNow();
        clientExecutor.shutdownNow();
    }

    private void acceptConnections(int port) {
        try {
            ServerSocket newServer = new ServerSocket();
            newServer.setReuseAddress(true);
            newServer.bind(new InetSocketAddress(port));
            serverSocket = newServer;

            if (!running) {
                closeQuietly(newServer);
                return;
            }
            listener.onListenerStateChanged(
                    context.getString(R.string.listening_tcp_port, port), null);

            while (running) {
                Socket socket = newServer.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                int identifier = nextClientId.incrementAndGet();
                clients.put(identifier, socket);
                listener.onClientCountChanged(clients.size());
                clientExecutor.execute(() -> monitorClient(identifier, socket));
            }
        } catch (IOException error) {
            if (running) {
                running = false;
                listener.onListenerStateChanged(
                        context.getString(R.string.tcp_server_failed),
                        error.getLocalizedMessage());
            }
        } finally {
            ServerSocket activeServer = serverSocket;
            serverSocket = null;
            closeQuietly(activeServer);
        }
    }

    private void monitorClient(int identifier, Socket socket) {
        try {
            byte[] buffer = new byte[4096];
            while (running && socket.getInputStream().read(buffer) >= 0) {
                // Navionics normally only reads; incoming bytes are intentionally ignored.
            }
        } catch (IOException ignored) {
            // A disconnect is reflected by removing the client below.
        } finally {
            removeClient(identifier);
        }
    }

    private void removeClient(int identifier) {
        Socket removed = clients.remove(identifier);
        if (removed != null) {
            closeQuietly(removed);
            listener.onClientCountChanged(clients.size());
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
