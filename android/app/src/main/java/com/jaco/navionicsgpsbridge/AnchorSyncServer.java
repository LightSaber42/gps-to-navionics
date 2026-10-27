package com.jaco.navionicsgpsbridge;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One-client authenticated anchor-state server for LAN and Tailscale connections. */
final class AnchorSyncServer {
    interface Listener {
        void onMonitorStatusChanged(boolean connected, boolean soundReady);
        void onAnchorSyncServerError(String message);
    }

    private final Object lock = new Object();
    private final Context context;
    private final Listener listener;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "anchor-sync-server-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "anchor-sync-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private byte[] pairingKey = new byte[0];
    private Client client;
    private AnchorSyncProtocol.State latestState;
    private AnchorHistoryStore historyStore;

    AnchorSyncServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(byte[] pairingKey, AnchorHistoryStore historyStore) {
        synchronized (lock) {
            this.pairingKey = Arrays.copyOf(pairingKey, pairingKey.length);
            this.historyStore = historyStore;
            if (running) return;
            running = true;
        }
        workers.execute(this::acceptLoop);
        heartbeat.scheduleWithFixedDelay(
                this::sendStateNow,
                AnchorSyncProtocol.HEARTBEAT_MILLIS,
                AnchorSyncProtocol.HEARTBEAT_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    void updateState(AnchorSyncProtocol.State state) {
        synchronized (lock) {
            latestState = state;
        }
    }

    void sendStateNow() {
        Client current;
        AnchorSyncProtocol.State state;
        synchronized (lock) {
            current = client;
            state = latestState;
        }
        if (current == null || state == null) return;
        try {
            current.send(AnchorSyncProtocol.state(state).toString());
        } catch (IOException | GeneralSecurityException | JSONException error) {
            disconnect(current);
        }
    }

    void rotatePairingKey(byte[] newPairingKey) {
        Client current;
        synchronized (lock) {
            pairingKey = Arrays.copyOf(newPairingKey, newPairingKey.length);
            current = client;
        }
        if (current != null) disconnect(current);
    }

    void shutdown() {
        Client current;
        synchronized (lock) {
            running = false;
            current = client;
            client = null;
        }
        close(serverSocket);
        if (current != null) close(current.socket);
        heartbeat.shutdownNow();
        workers.shutdownNow();
        notifyStatus(false, false);
    }

    private void acceptLoop() {
        try (ServerSocket listenerSocket = new ServerSocket()) {
            listenerSocket.setReuseAddress(true);
            listenerSocket.bind(new InetSocketAddress(AnchorSyncProtocol.PORT));
            serverSocket = listenerSocket;
            while (running) {
                Socket socket = listenerSocket.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                workers.execute(() -> handle(socket));
            }
        } catch (IOException error) {
            if (running) reportError(context.getString(
                    R.string.shore_monitor_listener_failed,
                    safeMessage(error)));
        } finally {
            serverSocket = null;
        }
    }

    private void handle(Socket socket) {
        Client candidate = null;
        try {
            socket.setSoTimeout(0);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String hello = readLine(input);
            String[] parts = hello.split(" ");
            if (parts.length != 3 || !"HELLO".equals(parts[0])) {
                throw new GeneralSecurityException("Invalid HELLO");
            }
            byte[] clientNonce = AnchorSyncCrypto.decode(parts[1]);
            byte[] receivedProof = AnchorSyncCrypto.decode(parts[2]);
            byte[] currentPairingKey;
            synchronized (lock) {
                currentPairingKey = Arrays.copyOf(pairingKey, pairingKey.length);
            }
            if (clientNonce.length != AnchorSyncCrypto.NONCE_BYTES
                    || !AnchorSyncCrypto.constantTimeEquals(
                    AnchorSyncCrypto.clientProof(currentPairingKey, clientNonce),
                    receivedProof)) {
                throw new GeneralSecurityException("Pairing authentication failed");
            }
            byte[] serverNonce = AnchorSyncCrypto.randomBytes(AnchorSyncCrypto.NONCE_BYTES);
            byte[] serverProof = AnchorSyncCrypto.serverProof(
                    currentPairingKey, clientNonce, serverNonce);
            byte[] sessionKey = AnchorSyncCrypto.sessionKey(
                    currentPairingKey, clientNonce, serverNonce);
            writeLine(output, "WELCOME " + AnchorSyncCrypto.encode(serverNonce)
                    + " " + AnchorSyncCrypto.encode(serverProof));

            candidate = new Client(socket, input, output, sessionKey);
            Client replaced;
            synchronized (lock) {
                replaced = client;
                client = candidate;
            }
            if (replaced != null) close(replaced.socket);
            notifyStatus(true, false);
            sendStateNow();

            while (running) processFrame(candidate, readLine(input));
        } catch (IOException | GeneralSecurityException | JSONException error) {
            // Authentication failures and ordinary network loss are intentionally quiet.
        } finally {
            if (candidate != null) disconnect(candidate);
            else close(socket);
        }
    }

    private void processFrame(Client source, String line)
            throws GeneralSecurityException, JSONException, IOException {
        if (!line.startsWith("FRAME ")) throw new GeneralSecurityException("Invalid frame");
        AnchorSyncCrypto.OpenedFrame frame = AnchorSyncCrypto.open(
                source.sessionKey,
                line.substring("FRAME ".length()),
                "c2s");
        if (frame.sequence != source.receiveSequence + 1L) {
            throw new GeneralSecurityException("Invalid frame sequence");
        }
        source.receiveSequence = frame.sequence;
        JSONObject message = new JSONObject(frame.plaintext);
        String type = AnchorSyncProtocol.type(message);
        if (AnchorSyncProtocol.TYPE_CLIENT_STATUS.equals(type)) {
            source.soundReady = message.optBoolean("sound_ready", false);
            notifyStatus(true, source.soundReady);
        } else if (AnchorSyncProtocol.TYPE_HISTORY_REQUEST.equals(type)) {
            sendHistory(source, message);
        } else {
            throw new JSONException("Unexpected anchor-sync message " + type);
        }
    }

    private void sendHistory(Client target, JSONObject request)
            throws JSONException, GeneralSecurityException, IOException {
        AnchorHistoryStore store;
        synchronized (lock) {
            store = historyStore;
        }
        if (store == null || !request.getString("session_id").equals(store.sessionId())) return;
        long after = request.optLong("after_sequence", 0L);
        List<AnchorSyncProtocol.Point> page = store.pageAfter(
                after, AnchorSyncProtocol.HISTORY_PAGE_SIZE);
        long last = page.isEmpty() ? after : page.get(page.size() - 1).sequence;
        target.send(AnchorSyncProtocol.history(
                store.sessionId(), page, !store.hasMoreAfter(last)).toString());
    }

    private void disconnect(Client candidate) {
        boolean wasActive;
        synchronized (lock) {
            wasActive = client == candidate;
            if (wasActive) client = null;
        }
        close(candidate.socket);
        if (wasActive) notifyStatus(false, false);
    }

    private void notifyStatus(boolean connected, boolean soundReady) {
        listener.onMonitorStatusChanged(connected, soundReady);
    }

    private void reportError(String message) {
        listener.onAnchorSyncServerError(message);
    }

    static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("Connection closed");
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > AnchorSyncProtocol.MAX_LINE_BYTES) {
                throw new IOException("Anchor-sync frame is too large");
            }
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    static void writeLine(BufferedOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write('\n');
        output.flush();
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getLocalizedMessage() == null
                ? error.getClass().getSimpleName() : error.getLocalizedMessage();
    }

    private static final class Client {
        final Socket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;
        final byte[] sessionKey;
        long receiveSequence;
        long sendSequence;
        boolean soundReady;

        Client(Socket socket, BufferedInputStream input, BufferedOutputStream output,
                byte[] sessionKey) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.sessionKey = sessionKey;
        }

        synchronized void send(String plaintext)
                throws GeneralSecurityException, IOException {
            sendSequence += 1L;
            String frame = AnchorSyncCrypto.seal(
                    sessionKey, sendSequence, plaintext, "s2c");
            writeLine(output, "FRAME " + frame);
        }
    }
}
