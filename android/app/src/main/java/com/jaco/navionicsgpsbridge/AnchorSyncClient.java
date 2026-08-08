package com.jaco.navionicsgpsbridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** A single low-duty-cycle connection from the Android shore device to the boat. */
final class AnchorSyncClient {
    interface Listener {
        void onConnected();
        void onState(AnchorSyncProtocol.State state);
        void onHistory(AnchorSyncProtocol.History history);
        void onDisconnected(String message);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "anchor-sync-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "anchor-sync-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    private Connection connection;

    AnchorSyncClient(Listener listener) {
        this.listener = listener;
    }

    void connect(AnchorPairingStore.MonitorConfiguration configuration, boolean soundReady) {
        stopped = false;
        executor.execute(() -> connectOnWorker(configuration, soundReady));
    }

    void requestHistory(String sessionId, long afterSequence) {
        try {
            send(AnchorSyncProtocol.historyRequest(sessionId, afterSequence));
        } catch (JSONException ignored) {
            // Primitive values cannot fail JSON encoding.
        }
    }

    void sendSoundReady(boolean soundReady) {
        try {
            send(AnchorSyncProtocol.clientStatus(soundReady));
        } catch (JSONException ignored) {
            // Primitive values cannot fail JSON encoding.
        }
    }

    void stop() {
        stopped = true;
        Connection current;
        synchronized (lock) {
            current = connection;
            connection = null;
        }
        if (current != null) close(current.socket);
        executor.shutdownNow();
        writerExecutor.shutdownNow();
    }

    private void connectOnWorker(AnchorPairingStore.MonitorConfiguration configuration,
            boolean soundReady) {
        Throwable lastError = null;
        for (String host : configuration.hosts) {
            if (stopped) return;
            try {
                runConnection(host, configuration, soundReady);
                return;
            } catch (IOException | GeneralSecurityException | JSONException error) {
                lastError = error;
            }
        }
        if (!stopped) listener.onDisconnected(lastError == null
                ? "Boat device is unavailable."
                : safeMessage(lastError));
    }

    private void runConnection(String host,
            AnchorPairingStore.MonitorConfiguration configuration,
            boolean soundReady) throws IOException, GeneralSecurityException, JSONException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, configuration.port), 5_000);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) AnchorSyncProtocol.CONNECTION_LOST_MILLIS);
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());

        byte[] clientNonce = AnchorSyncCrypto.randomBytes(AnchorSyncCrypto.NONCE_BYTES);
        byte[] clientProof = AnchorSyncCrypto.clientProof(
                configuration.pairingKey, clientNonce);
        AnchorSyncServer.writeLine(output, "HELLO " + AnchorSyncCrypto.encode(clientNonce)
                + " " + AnchorSyncCrypto.encode(clientProof));
        String[] welcome = AnchorSyncServer.readLine(input).split(" ");
        if (welcome.length != 3 || !"WELCOME".equals(welcome[0])) {
            throw new GeneralSecurityException("Boat authentication failed");
        }
        byte[] serverNonce = AnchorSyncCrypto.decode(welcome[1]);
        byte[] receivedProof = AnchorSyncCrypto.decode(welcome[2]);
        if (serverNonce.length != AnchorSyncCrypto.NONCE_BYTES
                || !AnchorSyncCrypto.constantTimeEquals(
                AnchorSyncCrypto.serverProof(
                        configuration.pairingKey, clientNonce, serverNonce),
                receivedProof)) {
            throw new GeneralSecurityException("Boat authentication failed");
        }
        Connection authenticated = new Connection(
                socket,
                input,
                output,
                AnchorSyncCrypto.sessionKey(
                        configuration.pairingKey, clientNonce, serverNonce));
        synchronized (lock) {
            if (stopped) {
                close(socket);
                return;
            }
            connection = authenticated;
        }
        listener.onConnected();
        authenticated.send(AnchorSyncProtocol.clientStatus(soundReady).toString());

        try {
            while (!stopped) processFrame(
                    authenticated, AnchorSyncServer.readLine(authenticated.input));
        } finally {
            synchronized (lock) {
                if (connection == authenticated) connection = null;
            }
            close(socket);
            if (!stopped) listener.onDisconnected("Boat heartbeat was lost.");
        }
    }

    private void processFrame(Connection source, String line)
            throws GeneralSecurityException, JSONException {
        if (!line.startsWith("FRAME ")) throw new GeneralSecurityException("Invalid frame");
        AnchorSyncCrypto.OpenedFrame frame = AnchorSyncCrypto.open(
                source.sessionKey,
                line.substring("FRAME ".length()),
                "s2c");
        if (frame.sequence != source.receiveSequence + 1L) {
            throw new GeneralSecurityException("Invalid frame sequence");
        }
        source.receiveSequence = frame.sequence;
        JSONObject value = new JSONObject(frame.plaintext);
        String type = AnchorSyncProtocol.type(value);
        if (AnchorSyncProtocol.TYPE_STATE.equals(type)) {
            listener.onState(AnchorSyncProtocol.parseState(value));
        } else if (AnchorSyncProtocol.TYPE_HISTORY.equals(type)) {
            listener.onHistory(AnchorSyncProtocol.parseHistory(value));
        } else {
            throw new JSONException("Unexpected anchor-sync message " + type);
        }
    }

    private void send(JSONObject message) {
        String plaintext = message.toString();
        try {
            writerExecutor.execute(() -> sendOnWriter(plaintext));
        } catch (RejectedExecutionException ignored) {
            // A stopped client discards pending UI and status messages.
        }
    }

    private void sendOnWriter(String plaintext) {
        Connection current;
        synchronized (lock) {
            current = connection;
        }
        if (current == null) return;
        try {
            current.send(plaintext);
        } catch (IOException | GeneralSecurityException error) {
            close(current.socket);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getLocalizedMessage() == null
                ? error.getClass().getSimpleName() : error.getLocalizedMessage();
    }

    private static final class Connection {
        final Socket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;
        final byte[] sessionKey;
        long receiveSequence;
        long sendSequence;

        Connection(Socket socket, BufferedInputStream input, BufferedOutputStream output,
                byte[] sessionKey) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.sessionKey = Arrays.copyOf(sessionKey, sessionKey.length);
        }

        synchronized void send(String plaintext)
                throws GeneralSecurityException, IOException {
            sendSequence += 1L;
            AnchorSyncServer.writeLine(output, "FRAME " + AnchorSyncCrypto.seal(
                    sessionKey, sendSequence, plaintext, "c2s"));
        }
    }
}
