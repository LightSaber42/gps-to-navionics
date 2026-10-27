package com.jaco.navionicsgpsbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Low-power, read-only Android companion that alarms on boat events or heartbeat loss. */
public final class ShoreMonitorService extends Service implements AnchorSyncClient.Listener {
    static final String ACTION_START = "com.jaco.navionicsgpsbridge.SHORE_START";
    static final String ACTION_SILENCE = "com.jaco.navionicsgpsbridge.SHORE_SILENCE";
    static final String ACTION_STOP = "com.jaco.navionicsgpsbridge.SHORE_STOP";
    static final String ACTION_UNPAIR = "com.jaco.navionicsgpsbridge.SHORE_UNPAIR";

    private static final String PREFERENCES = "shore_monitor";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ACKNOWLEDGED_EVENT = "acknowledged_event";
    private static final String KEY_SESSION_WAS_ARMED = "session_was_armed";
    private static final String MONITOR_CHANNEL = "shore_anchor_monitor";
    private static final String ALARM_CHANNEL = "shore_anchor_alarm";
    private static final int MONITOR_NOTIFICATION_ID = 21_124;
    private static final int ALARM_NOTIFICATION_ID = 21_125;
    private static final long RECONNECT_INTERVAL_MILLIS = 10_000L;

    interface StateListener {
        void onShoreMonitorStateChanged(ShoreMonitorState state);
    }

    final class LocalBinder extends Binder {
        ShoreMonitorState currentState() {
            return snapshot();
        }

        void addListener(StateListener listener) {
            listeners.add(listener);
            listener.onShoreMonitorStateChanged(snapshot());
        }

        void removeListener(StateListener listener) {
            listeners.remove(listener);
        }

        void setHistoryVisible(boolean visible) {
            historyVisible = visible;
            if (visible) requestHistoryIfNeeded();
        }

        void reconnectNow() {
            lastConnectAttemptElapsedMillis = 0L;
            monitorTick();
        }
    }

    private final Object lock = new Object();
    private final LocalBinder binder = new LocalBinder();
    private final Set<StateListener> listeners = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "shore-monitor-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private final List<AnchorSyncProtocol.Point> historyPoints = new ArrayList<>();

    private SharedPreferences preferences;
    private AnchorPairingStore pairingStore;
    private AnchorPairingStore.MonitorConfiguration configuration;
    private AnchorSyncClient client;
    private AnchorSyncProtocol.State latestState;
    private AnchorSyncProtocol.Event activeRemoteEvent;
    private boolean connected;
    private boolean connecting;
    private boolean historyVisible;
    private boolean historyRequestInFlight;
    private boolean linkLossEpisodeActive;
    private boolean linkLossEventPending;
    private boolean sessionWasArmed;
    private long lastStateElapsedMillis;
    private long lastHeartbeatAtMillis;
    private long monitoringStartedElapsedMillis;
    private long lastConnectAttemptElapsedMillis;
    private long acknowledgedEventSequence;
    private String historySessionId;
    private String statusMessage;
    private Ringtone alarmRingtone;
    private int previousAlarmVolume = -1;
    private boolean alarmPresentationActive;
    private boolean lastReportedSoundReady;
    private boolean configurationLoaded;
    private boolean foregroundStarted;
    private boolean monitoringEnabled;

    static void start(Context context) {
        Intent intent = new Intent(context, ShoreMonitorService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        statusMessage = getString(R.string.waiting_for_pairing);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        monitoringEnabled = preferences.getBoolean(KEY_ENABLED, false);
        acknowledgedEventSequence = preferences.getLong(KEY_ACKNOWLEDGED_EVENT, 0L);
        sessionWasArmed = preferences.getBoolean(KEY_SESSION_WAS_ARMED, false);
        pairingStore = new AnchorPairingStore(this);
        client = new AnchorSyncClient(this, this);
        monitoringStartedElapsedMillis = SystemClock.elapsedRealtime();
        createNotificationChannels();
        loadConfiguration();
        scheduler.scheduleWithFixedDelay(this::monitorTick, 0L, 10L, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitoring(false);
            return START_NOT_STICKY;
        }
        if (ACTION_UNPAIR.equals(action)) {
            pairingStore.clearMonitor();
            stopMonitoring(true);
            return START_NOT_STICKY;
        }
        if (ACTION_SILENCE.equals(action)) {
            if (monitoringEnabled) silenceLocally();
            return monitoringEnabled ? START_STICKY : START_NOT_STICKY;
        }
        synchronized (lock) {
            if (!monitoringEnabled) {
                monitoringStartedElapsedMillis = SystemClock.elapsedRealtime();
                lastStateElapsedMillis = 0L;
                lastHeartbeatAtMillis = 0L;
                connected = false;
                connecting = false;
                historyRequestInFlight = false;
                linkLossEpisodeActive = false;
                linkLossEventPending = false;
                latestState = null;
                activeRemoteEvent = null;
                historyPoints.clear();
                historySessionId = null;
                lastConnectAttemptElapsedMillis = 0L;
                statusMessage = configuration == null
                        ? getString(R.string.pair_phone_with_boat)
                        : getString(R.string.connecting_to_boat);
            }
            monitoringEnabled = true;
        }
        preferences.edit().putBoolean(KEY_ENABLED, true).apply();
        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification(snapshot()));
        foregroundStarted = true;
        loadConfiguration();
        monitorTick();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        foregroundStarted = false;
        client.stop();
        scheduler.shutdownNow();
        stopAlarmPresentation();
        super.onDestroy();
    }

    @Override
    public void onConnected() {
        synchronized (lock) {
            if (!monitoringEnabled) return;
            connected = true;
            connecting = false;
            linkLossEpisodeActive = false;
            statusMessage = getString(R.string.connected_waiting_boat_state);
        }
        lastReportedSoundReady = isSoundReady();
        client.sendSoundReady(lastReportedSoundReady);
        emitState();
    }

    @Override
    public void onState(AnchorSyncProtocol.State state) {
        boolean shouldStopAfterDisarm = false;
        synchronized (lock) {
            if (!monitoringEnabled) return;
            connected = true;
            connecting = false;
            latestState = state;
            lastStateElapsedMillis = SystemClock.elapsedRealtime();
            lastHeartbeatAtMillis = System.currentTimeMillis();
            linkLossEpisodeActive = false;
            statusMessage = state.armed
                    ? getString(R.string.anchor_watch_live)
                    : getString(R.string.boat_anchor_watch_disarmed);

            if (state.armed) {
                sessionWasArmed = true;
                preferences.edit().putBoolean(KEY_SESSION_WAS_ARMED, true).apply();
            }
            if (state.sessionId == null) {
                historyPoints.clear();
                historySessionId = null;
            } else if (!state.sessionId.equals(historySessionId)) {
                historyPoints.clear();
                historySessionId = state.sessionId;
            }
            activeRemoteEvent = null;
            for (AnchorSyncProtocol.Event event : state.events) {
                if (state.sessionId != null && state.sessionId.equals(event.sessionId)
                        && event.sequence > acknowledgedEventSequence) {
                    activeRemoteEvent = event;
                }
            }
            shouldStopAfterDisarm = !state.armed && sessionWasArmed
                    && activeRemoteEvent == null && !linkLossEventPending;
        }
        updateAlarmPresentation();
        requestHistoryIfNeeded();
        emitState();
        updateMonitorNotification();
        if (shouldStopAfterDisarm) stopAfterDisarm();
    }

    @Override
    public void onHistory(AnchorSyncProtocol.History history) {
        long after = 0L;
        boolean requestNext = false;
        synchronized (lock) {
            if (!monitoringEnabled) return;
            historyRequestInFlight = false;
            if (latestState == null || latestState.sessionId == null
                    || !latestState.sessionId.equals(history.sessionId)) return;
            long currentLast = historyPoints.isEmpty()
                    ? 0L : historyPoints.get(historyPoints.size() - 1).sequence;
            for (AnchorSyncProtocol.Point point : history.points) {
                if (point.sequence > currentLast) historyPoints.add(point);
            }
            AnchorHistoryCompactor.compactIfNeeded(historyPoints);
            after = historyPoints.isEmpty()
                    ? 0L : historyPoints.get(historyPoints.size() - 1).sequence;
            requestNext = historyVisible && !history.complete;
            if (requestNext) historyRequestInFlight = true;
        }
        emitState();
        if (requestNext) client.requestHistory(history.sessionId, after);
    }

    @Override
    public void onDisconnected(String message) {
        synchronized (lock) {
            if (!monitoringEnabled) return;
            connected = false;
            connecting = false;
            historyRequestInFlight = false;
            statusMessage = message;
        }
        emitState();
        updateMonitorNotification();
    }

    private void loadConfiguration() {
        try {
            AnchorPairingStore.MonitorConfiguration loaded = pairingStore.loadMonitor();
            boolean identityChanged;
            boolean endpointChanged;
            AnchorSyncClient replacedClient = null;
            synchronized (lock) {
                identityChanged = configurationLoaded && loaded != null
                        && (configuration == null
                        || !loaded.deviceId.equals(configuration.deviceId)
                        || !Arrays.equals(loaded.pairingKey, configuration.pairingKey));
                endpointChanged = configurationLoaded && loaded != null
                        && configuration != null
                        && (loaded.port != configuration.port
                        || !loaded.hosts.equals(configuration.hosts));
                configurationLoaded = true;
                configuration = loaded;
                if (loaded == null) {
                    statusMessage = getString(R.string.pair_phone_with_boat);
                } else if (!monitoringEnabled) {
                    statusMessage = getString(R.string.monitoring_stopped);
                } else if (identityChanged) {
                    monitoringStartedElapsedMillis = SystemClock.elapsedRealtime();
                    lastStateElapsedMillis = 0L;
                    connected = false;
                    connecting = false;
                    linkLossEpisodeActive = false;
                    linkLossEventPending = false;
                    sessionWasArmed = false;
                    acknowledgedEventSequence = 0L;
                    latestState = null;
                    activeRemoteEvent = null;
                    historyPoints.clear();
                    historySessionId = null;
                    preferences.edit()
                            .putBoolean(KEY_SESSION_WAS_ARMED, false)
                            .putLong(KEY_ACKNOWLEDGED_EVENT, 0L)
                            .apply();
                    statusMessage = getString(R.string.connecting_to_boat);
                } else if (endpointChanged) {
                    connected = false;
                    connecting = false;
                    historyRequestInFlight = false;
                    statusMessage = getString(R.string.connecting_to_boat);
                }
                if (identityChanged || endpointChanged) {
                    replacedClient = client;
                    client = new AnchorSyncClient(this, this);
                    lastConnectAttemptElapsedMillis = 0L;
                }
            }
            if (replacedClient != null) replacedClient.stop();
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            synchronized (lock) {
                configuration = null;
                statusMessage = getString(
                        R.string.pairing_could_not_be_read,
                        safeMessage(error));
            }
        }
        emitState();
    }

    private void monitorTick() {
        AnchorPairingStore.MonitorConfiguration currentConfiguration;
        boolean shouldConnect = false;
        boolean shouldAlarmForLoss = false;
        boolean stateChanged = false;
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (!monitoringEnabled) return;
            currentConfiguration = configuration;
            long heartbeatBaseline = lastStateElapsedMillis > 0L
                    ? lastStateElapsedMillis : monitoringStartedElapsedMillis;
            if (currentConfiguration != null && sessionWasArmed && !linkLossEpisodeActive
                    && now - heartbeatBaseline >= AnchorSyncProtocol.CONNECTION_LOST_MILLIS) {
                linkLossEpisodeActive = true;
                linkLossEventPending = true;
                connected = false;
                statusMessage = getString(R.string.boat_heartbeat_lost);
                shouldAlarmForLoss = true;
                stateChanged = true;
            }
            if (currentConfiguration != null && !connected && !connecting
                    && (lastConnectAttemptElapsedMillis == 0L
                    || now - lastConnectAttemptElapsedMillis >= RECONNECT_INTERVAL_MILLIS)) {
                connecting = true;
                lastConnectAttemptElapsedMillis = now;
                statusMessage = getString(R.string.connecting_to_boat);
                shouldConnect = true;
                stateChanged = true;
            }
            if (shouldConnect) client.connect(currentConfiguration, isSoundReady());
        }
        if (shouldAlarmForLoss) updateAlarmPresentation();
        boolean soundReady = isSoundReady();
        if (soundReady != lastReportedSoundReady) {
            lastReportedSoundReady = soundReady;
            createNotificationChannels();
            client.sendSoundReady(soundReady);
            stateChanged = true;
        }
        if (stateChanged) {
            emitState();
            updateMonitorNotification();
        }
    }

    private void requestHistoryIfNeeded() {
        String sessionId;
        long after;
        synchronized (lock) {
            if (!historyVisible || !connected || historyRequestInFlight
                    || latestState == null || latestState.sessionId == null) return;
            after = historyPoints.isEmpty()
                    ? 0L : historyPoints.get(historyPoints.size() - 1).sequence;
            if (after >= latestState.latestSequence) return;
            sessionId = latestState.sessionId;
            historyRequestInFlight = true;
        }
        client.requestHistory(sessionId, after);
    }

    private void silenceLocally() {
        boolean shouldStop;
        synchronized (lock) {
            if (activeRemoteEvent != null) {
                acknowledgedEventSequence = Math.max(
                        acknowledgedEventSequence, activeRemoteEvent.sequence);
                preferences.edit()
                        .putLong(KEY_ACKNOWLEDGED_EVENT, acknowledgedEventSequence)
                        .apply();
                activeRemoteEvent = null;
            }
            linkLossEventPending = false;
            shouldStop = latestState != null && !latestState.armed && sessionWasArmed;
        }
        stopAlarmPresentation();
        emitState();
        updateMonitorNotification();
        if (shouldStop) stopAfterDisarm();
    }

    private void stopAfterDisarm() {
        stopMonitoring(false);
    }

    private void stopMonitoring(boolean unpair) {
        AnchorSyncClient stoppedClient;
        synchronized (lock) {
            monitoringEnabled = false;
            connected = false;
            connecting = false;
            historyRequestInFlight = false;
            linkLossEpisodeActive = false;
            linkLossEventPending = false;
            sessionWasArmed = false;
            latestState = null;
            activeRemoteEvent = null;
            historyPoints.clear();
            historySessionId = null;
            lastStateElapsedMillis = 0L;
            lastHeartbeatAtMillis = 0L;
            lastConnectAttemptElapsedMillis = 0L;
            if (unpair) configuration = null;
            if (unpair) acknowledgedEventSequence = 0L;
            statusMessage = unpair
                    ? getString(R.string.not_paired)
                    : getString(R.string.monitoring_stopped);
            stoppedClient = client;
            client = new AnchorSyncClient(this, this);
        }
        SharedPreferences.Editor preferenceChanges = preferences.edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_SESSION_WAS_ARMED, false);
        if (unpair) preferenceChanges.remove(KEY_ACKNOWLEDGED_EVENT);
        preferenceChanges.apply();
        stoppedClient.stop();
        stopAlarmPresentation();
        foregroundStarted = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        emitState();
        stopSelf();
    }

    private void updateAlarmPresentation() {
        if (alarmReason() == null) stopAlarmPresentation();
        else startAlarmPresentation();
    }

    private String alarmReason() {
        synchronized (lock) {
            if (activeRemoteEvent != null) return activeRemoteEvent.reason;
            if (linkLossEventPending) {
                return getString(R.string.boat_connection_lost_30_seconds);
            }
            return null;
        }
    }

    private synchronized void startAlarmPresentation() {
        String reason = alarmReason();
        if (reason == null) return;
        boolean first = !alarmPresentationActive;
        alarmPresentationActive = true;
        if (first) {
            try {
                AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
                previousAlarmVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM);
                manager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        manager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0);
            } catch (RuntimeException ignored) {
                previousAlarmVolume = -1;
            }
        }
        if (alarmRingtone == null || !alarmRingtone.isPlaying()) {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            alarmRingtone = RingtoneManager.getRingtone(this, alarmUri);
            if (alarmRingtone != null) {
                alarmRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build());
                if (Build.VERSION.SDK_INT >= 28) {
                    alarmRingtone.setLooping(true);
                    alarmRingtone.setVolume(1.0f);
                }
                alarmRingtone.play();
            }
        }
        if (first) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0L, 700L, 300L, 700L, 800L}, 0));
            }
        }
        getSystemService(NotificationManager.class)
                .notify(ALARM_NOTIFICATION_ID, buildAlarmNotification(reason));
    }

    private synchronized void stopAlarmPresentation() {
        alarmPresentationActive = false;
        if (alarmRingtone != null) {
            alarmRingtone.stop();
            alarmRingtone = null;
        }
        if (previousAlarmVolume >= 0) {
            try {
                AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
                manager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            } catch (RuntimeException ignored) {
                // The alarm itself is already stopped.
            }
            previousAlarmVolume = -1;
        }
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.cancel();
        getSystemService(NotificationManager.class).cancel(ALARM_NOTIFICATION_ID);
    }

    private ShoreMonitorState snapshot() {
        synchronized (lock) {
            List<BridgeState.AnchorTrackPoint> rendered = new ArrayList<>();
            for (AnchorSyncProtocol.Point point : historyPoints) {
                rendered.add(new BridgeState.AnchorTrackPoint(
                        point.eastMeters, point.northMeters, point.accuracyMeters));
            }
            String reason = activeRemoteEvent != null
                    ? activeRemoteEvent.reason
                    : linkLossEventPending
                    ? getString(R.string.boat_connection_lost_30_seconds)
                    : null;
            return new ShoreMonitorState(
                    configuration != null,
                    monitoringEnabled,
                    connected,
                    latestState != null,
                    isSoundReady(),
                    latestState != null && latestState.armed,
                    reason != null,
                    reason,
                    latestState == null ? "unknown" : latestState.mode,
                    latestState == null ? 15.0 : latestState.radiusMeters,
                    latestState == null ? Double.NaN : latestState.distanceMeters,
                    latestState == null ? Double.NaN : latestState.accuracyMeters,
                    latestState == null ? -1 : latestState.batteryPercent,
                    latestState != null && latestState.charging,
                    latestState == null ? null : latestState.sessionId,
                    lastHeartbeatAtMillis,
                    rendered,
                    statusMessage);
        }
    }

    private void emitState() {
        ShoreMonitorState state = snapshot();
        for (StateListener listener : listeners) {
            listener.onShoreMonitorStateChanged(state);
        }
    }

    private void createNotificationChannels() {
        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL,
                getString(R.string.shore_anchor_monitor),
                NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription(getString(R.string.shore_monitor_channel_description));
        NotificationChannel alarm = new NotificationChannel(
                ALARM_CHANNEL,
                getString(R.string.shore_anchor_alarms),
                NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription(getString(R.string.shore_alarm_channel_description));
        alarm.setSound(null, null);
        alarm.enableVibration(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            alarm.setBypassDnd(manager.isNotificationPolicyAccessGranted());
        } catch (SecurityException ignored) {
            // The UI exposes Android's Do Not Disturb access screen.
        }
        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(alarm);
    }

    private Notification buildMonitorNotification(ShoreMonitorState state) {
        Intent openIntent = new Intent(this, ShoreMonitorActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this, 20, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = state.connected
                ? (state.armed
                        ? getString(R.string.boat_anchor_watch_live)
                        : getString(R.string.boat_watch_disarmed))
                : state.statusMessage;
        return new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle(getString(R.string.shore_anchor_monitor))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private Notification buildAlarmNotification(String reason) {
        Intent openIntent = new Intent(this, ShoreMonitorActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this, 22, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent silenceIntent = new Intent(this, ShoreMonitorService.class)
                .setAction(ACTION_SILENCE);
        PendingIntent silence = PendingIntent.getService(
                this, 23, silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, ALARM_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle(getString(R.string.boat_anchor_alarm_notification_title))
                .setContentText(reason)
                .setStyle(new Notification.BigTextStyle().bigText(reason))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.silence_here), silence).build())
                .build();
    }

    private void updateMonitorNotification() {
        if (!foregroundStarted) return;
        getSystemService(NotificationManager.class)
                .notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(snapshot()));
    }

    private boolean isSoundReady() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private static String safeMessage(Throwable error) {
        return error.getLocalizedMessage() == null
                ? error.getClass().getSimpleName() : error.getLocalizedMessage();
    }
}
