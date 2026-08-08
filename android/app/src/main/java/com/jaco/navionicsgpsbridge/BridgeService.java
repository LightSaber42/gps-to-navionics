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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class BridgeService extends Service implements LocationListener, NmeaTcpServer.Listener {
    static final String ACTION_START = "com.jaco.navionicsgpsbridge.START";
    static final String ACTION_STOP = "com.jaco.navionicsgpsbridge.STOP";
    static final String ACTION_ARM_ANCHOR = "com.jaco.navionicsgpsbridge.ARM_ANCHOR";
    static final String ACTION_DISARM_ANCHOR = "com.jaco.navionicsgpsbridge.DISARM_ANCHOR";
    static final String ACTION_SILENCE_ANCHOR = "com.jaco.navionicsgpsbridge.SILENCE_ANCHOR";
    static final String EXTRA_ANCHOR_MODE = "anchor_mode";
    static final String EXTRA_ANCHOR_RADIUS = "anchor_radius";
    static final String EXTRA_ANCHOR_LATITUDE = "anchor_latitude";
    static final String EXTRA_ANCHOR_LONGITUDE = "anchor_longitude";
    static final int PORT = 11_123;

    private static final String MONITORING_CHANNEL_ID = "gps_streaming";
    private static final String ALARM_CHANNEL_ID = "anchor_alarm_alerts";
    private static final int MONITORING_NOTIFICATION_ID = 11_123;
    private static final int ALARM_NOTIFICATION_ID = 11_124;
    private static final double STALE_FIX_SECONDS = 10.0;
    private static final double LOST_FIX_ALARM_SECONDS = 30.0;
    private static final long BREACH_CONFIRMATION_MILLIS = 5_000L;
    private static final int MAXIMUM_ANCHOR_TRACK_POINTS = 1_200;
    private static final String PREFERENCES = "bridge_settings";
    private static final String KEY_STREAMING = "streaming_enabled";
    private static final String KEY_ANCHOR_ARMED = "anchor_alarm_armed";
    private static final String KEY_ANCHOR_MODE = "anchor_mode";
    private static final String KEY_ANCHOR_RADIUS = "anchor_radius";
    private static final String KEY_HAS_ANCHOR_REFERENCE = "has_anchor_reference";
    private static final String KEY_ANCHOR_LATITUDE = "anchor_latitude";
    private static final String KEY_ANCHOR_LONGITUDE = "anchor_longitude";

    interface StateListener {
        void onBridgeStateChanged(BridgeState state);
    }

    final class LocalBinder extends Binder {
        BridgeState currentState() {
            return snapshot();
        }

        void addListener(StateListener listener) {
            listeners.add(listener);
            updateLocationRegistration();
            listener.onBridgeStateChanged(snapshot());
        }

        void removeListener(StateListener listener) {
            listeners.remove(listener);
            updateLocationRegistration();
        }

        void refreshNetworkAddresses() {
            BridgeService.this.refreshNetworkAddresses();
        }

        void refreshLocationUpdates() {
            updateLocationRegistration();
        }

        void updateAnchorSettings(AnchorAlarm.Mode mode, double radiusMeters) {
            BridgeService.this.updateAnchorSettings(mode, radiusMeters);
        }

        void markAnchorReference(double latitude, double longitude) {
            BridgeService.this.markAnchorReference(latitude, longitude);
        }
    }

    private final Object stateLock = new Object();
    private final LocalBinder binder = new LocalBinder();
    private final Set<StateListener> listeners = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "bridge-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private SharedPreferences preferences;
    private LocationManager locationManager;
    private NmeaTcpServer server;
    private ScheduledFuture<?> monitorTask;
    private PowerManager.WakeLock wakeLock;
    private Ringtone alarmRingtone;
    private int previousAlarmVolume = -1;
    private boolean alarmPresentationActive;
    private volatile boolean foregroundActive;
    private boolean locationUpdatesRegistered;
    private boolean streaming;
    private boolean anchorAlarmArmed;
    private boolean anchorAlarmTriggered;
    private boolean anchorAlarmSilenced;
    private long anchorArmedElapsedMillis;
    private long breachStartedElapsedMillis;
    private Location latestLocation;
    private int clientCount;
    private String listenerDescription = "Stopped";
    private List<BridgeState.NetworkAddress> networkAddresses = new ArrayList<>();
    private String lastSentences = "";
    private String error;
    private AnchorAlarm.Mode anchorMode = AnchorAlarm.Mode.SHORELINED;
    private boolean hasAnchorReference;
    private double anchorReferenceLatitude;
    private double anchorReferenceLongitude;
    private double anchorRadiusMeters = 15.0;
    private double anchorDistanceMeters = Double.NaN;
    private final List<BridgeState.AnchorTrackPoint> anchorTrackPoints = new ArrayList<>();
    private String anchorAlertReason;
    private volatile long lastNetworkRefreshElapsedMillis;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        server = new NmeaTcpServer(this);
        loadAnchorConfiguration();
        createNotificationChannels();
        refreshNetworkAddresses();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            restoreDesiredMonitoring();
            return isMonitoringRequired() ? START_STICKY : START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStreaming();
        } else if (ACTION_ARM_ANCHOR.equals(action)) {
            armAnchorAlarm(intent);
        } else if (ACTION_DISARM_ANCHOR.equals(action)) {
            disarmAnchorAlarm();
        } else if (ACTION_SILENCE_ANCHOR.equals(action)) {
            silenceAnchorAlarm();
        } else {
            startStreaming();
        }

        if (!isMonitoringRequired()) stopSelf();
        return isMonitoringRequired() ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        ScheduledFuture<?> activeTask = monitorTask;
        monitorTask = null;
        if (activeTask != null) activeTask.cancel(false);
        if (locationUpdatesRegistered) {
            locationManager.removeUpdates(this);
            locationUpdatesRegistered = false;
        }
        server.shutdown();
        scheduler.shutdownNow();
        stopAlarmPresentation();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!location.hasAccuracy() || location.getAccuracy() < 0.0f) return;
        synchronized (stateLock) {
            latestLocation = new Location(location);
            error = null;
        }
        evaluateAnchorPosition(location);
        emitState();
        updateMonitoringNotification();
    }

    @Override
    public void onProviderDisabled(String provider) {
        synchronized (stateLock) {
            error = "Turn on Location in Android settings to acquire a GPS fix.";
        }
        emitState();
    }

    @Override
    public void onProviderEnabled(String provider) {
        synchronized (stateLock) {
            error = null;
        }
        updateLocationRegistration();
        emitState();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Retained for devices below Android 12.
    }

    @Override
    public void onListenerStateChanged(String description, String serverError) {
        synchronized (stateLock) {
            listenerDescription = description;
            if (serverError != null) error = serverError;
        }
        emitState();
    }

    @Override
    public void onClientCountChanged(int count) {
        synchronized (stateLock) {
            clientCount = count;
        }
        emitState();
    }

    private void loadAnchorConfiguration() {
        synchronized (stateLock) {
            anchorMode = AnchorAlarm.Mode.fromStorageValue(
                    preferences.getString(KEY_ANCHOR_MODE, AnchorAlarm.Mode.SHORELINED.storageValue));
            anchorRadiusMeters = anchorMode.adjustedRadius(preferences.getFloat(
                    KEY_ANCHOR_RADIUS, (float) anchorMode.defaultRadiusMeters));
            hasAnchorReference = preferences.getBoolean(KEY_HAS_ANCHOR_REFERENCE, false);
            anchorReferenceLatitude = Double.longBitsToDouble(
                    preferences.getLong(KEY_ANCHOR_LATITUDE, Double.doubleToRawLongBits(0.0)));
            anchorReferenceLongitude = Double.longBitsToDouble(
                    preferences.getLong(KEY_ANCHOR_LONGITUDE, Double.doubleToRawLongBits(0.0)));
        }
    }

    private void restoreDesiredMonitoring() {
        boolean shouldStream = preferences.getBoolean(KEY_STREAMING, false);
        boolean shouldArmAnchor = preferences.getBoolean(KEY_ANCHOR_ARMED, false);
        synchronized (stateLock) {
            streaming = shouldStream;
            anchorAlarmArmed = shouldArmAnchor && hasAnchorReference;
            anchorArmedElapsedMillis = SystemClock.elapsedRealtime();
            anchorTrackPoints.clear();
        }
        if (streaming) server.start(PORT);
        if (isMonitoringRequired()) ensureMonitoringInfrastructure();
    }

    private void startStreaming() {
        synchronized (stateLock) {
            if (streaming) return;
            if (!hasFineLocationPermission()) {
                error = "Precise Location permission is required to stream a marine position.";
                return;
            }
            streaming = true;
            error = null;
        }
        preferences.edit().putBoolean(KEY_STREAMING, true).apply();
        ensureMonitoringInfrastructure();
        refreshNetworkAddresses();
        server.start(PORT);
        emitState();
    }

    private void stopStreaming() {
        boolean wasStreaming;
        synchronized (stateLock) {
            wasStreaming = streaming;
            streaming = false;
            clientCount = 0;
            listenerDescription = "Stopped";
        }
        preferences.edit().putBoolean(KEY_STREAMING, false).apply();
        if (wasStreaming) server.stop();
        stopMonitoringInfrastructureIfIdle();
        emitState();
    }

    private void armAnchorAlarm(Intent intent) {
        if (!hasFineLocationPermission()) {
            synchronized (stateLock) {
                error = "Precise Location permission is required for the anchor alarm.";
            }
            return;
        }

        AnchorAlarm.Mode mode = AnchorAlarm.Mode.fromStorageValue(
                intent.getStringExtra(EXTRA_ANCHOR_MODE));
        double radius = mode.adjustedRadius(intent.getDoubleExtra(
                EXTRA_ANCHOR_RADIUS, mode.defaultRadiusMeters));
        double latitude = intent.getDoubleExtra(EXTRA_ANCHOR_LATITUDE, Double.NaN);
        double longitude = intent.getDoubleExtra(EXTRA_ANCHOR_LONGITUDE, Double.NaN);
        if (!validCoordinate(latitude, longitude)) {
            synchronized (stateLock) {
                error = "A fresh GPS position is required before arming the anchor alarm.";
            }
            return;
        }

        synchronized (stateLock) {
            anchorMode = mode;
            anchorRadiusMeters = radius;
            hasAnchorReference = true;
            anchorReferenceLatitude = latitude;
            anchorReferenceLongitude = longitude;
            anchorDistanceMeters = Double.NaN;
            anchorTrackPoints.clear();
            anchorAlarmArmed = true;
            anchorAlarmTriggered = false;
            anchorAlarmSilenced = false;
            anchorAlertReason = null;
            anchorArmedElapsedMillis = SystemClock.elapsedRealtime();
            breachStartedElapsedMillis = 0L;
            error = null;
        }
        persistAnchorConfiguration(true);
        stopAlarmPresentation();
        ensureMonitoringInfrastructure();
        Location location = copyLatestLocation();
        if (location != null) evaluateAnchorPosition(location);
        emitState();
    }

    private void disarmAnchorAlarm() {
        synchronized (stateLock) {
            anchorAlarmArmed = false;
            anchorAlarmTriggered = false;
            anchorAlarmSilenced = false;
            anchorAlertReason = null;
            anchorDistanceMeters = Double.NaN;
            anchorTrackPoints.clear();
            breachStartedElapsedMillis = 0L;
        }
        preferences.edit().putBoolean(KEY_ANCHOR_ARMED, false).apply();
        stopAlarmPresentation();
        stopMonitoringInfrastructureIfIdle();
        emitState();
    }

    private void silenceAnchorAlarm() {
        synchronized (stateLock) {
            if (!anchorAlarmTriggered) return;
            anchorAlarmTriggered = false;
            anchorAlarmSilenced = true;
            anchorAlertReason = "Alarm silenced — monitoring continues until the boat is back inside the limit.";
            breachStartedElapsedMillis = 0L;
        }
        stopAlarmPresentation();
        emitState();
        updateMonitoringNotification();
    }

    private void updateAnchorSettings(AnchorAlarm.Mode mode, double radiusMeters) {
        boolean adjustedWhileArmed = false;
        synchronized (stateLock) {
            if (anchorAlarmArmed) {
                if (anchorMode != AnchorAlarm.Mode.FREE_SWINGING
                        || mode != anchorMode) return;
                double adjustedRadius = mode.adjustedRadius(radiusMeters);
                if (adjustedRadius == anchorRadiusMeters) return;
                anchorRadiusMeters = adjustedRadius;
                adjustedWhileArmed = true;
            }
            if (anchorMode != mode) {
                anchorMode = mode;
                hasAnchorReference = false;
                anchorDistanceMeters = Double.NaN;
                anchorTrackPoints.clear();
            }
            if (!adjustedWhileArmed) {
                anchorRadiusMeters = mode.adjustedRadius(radiusMeters);
            }
        }
        persistAnchorConfiguration(false);
        if (adjustedWhileArmed) {
            Location location = copyLatestLocation();
            if (location != null && fixAgeSeconds(location) <= STALE_FIX_SECONDS) {
                evaluateAnchorPosition(location);
            }
            updateMonitoringNotification();
        }
        emitState();
    }

    private void markAnchorReference(double latitude, double longitude) {
        if (!validCoordinate(latitude, longitude)) return;
        synchronized (stateLock) {
            if (anchorAlarmArmed) return;
            hasAnchorReference = true;
            anchorReferenceLatitude = latitude;
            anchorReferenceLongitude = longitude;
            anchorDistanceMeters = 0.0;
            anchorTrackPoints.clear();
        }
        persistAnchorConfiguration(false);
        emitState();
    }

    private void persistAnchorConfiguration(boolean armed) {
        SharedPreferences.Editor editor;
        synchronized (stateLock) {
            editor = preferences.edit()
                    .putString(KEY_ANCHOR_MODE, anchorMode.storageValue)
                    .putFloat(KEY_ANCHOR_RADIUS, (float) anchorRadiusMeters)
                    .putBoolean(KEY_HAS_ANCHOR_REFERENCE, hasAnchorReference)
                    .putLong(KEY_ANCHOR_LATITUDE,
                            Double.doubleToRawLongBits(anchorReferenceLatitude))
                    .putLong(KEY_ANCHOR_LONGITUDE,
                            Double.doubleToRawLongBits(anchorReferenceLongitude));
        }
        if (armed) editor.putBoolean(KEY_ANCHOR_ARMED, true);
        editor.apply();
    }

    private void ensureMonitoringInfrastructure() {
        if (!isMonitoringRequired()) return;
        if (!foregroundActive) {
            startForeground(MONITORING_NOTIFICATION_ID, buildMonitoringNotification(snapshot()));
            foregroundActive = true;
        }
        acquireWakeLock();
        updateLocationRegistration();
        if (monitorTask == null || monitorTask.isCancelled()) {
            monitorTask = scheduler.scheduleAtFixedRate(this::monitorTick, 0, 1, TimeUnit.SECONDS);
        }
        updateMonitoringNotification();
    }

    private void stopMonitoringInfrastructureIfIdle() {
        if (isMonitoringRequired()) {
            updateMonitoringNotification();
            return;
        }
        ScheduledFuture<?> activeTask = monitorTask;
        monitorTask = null;
        if (activeTask != null) activeTask.cancel(false);
        releaseWakeLock();
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundActive = false;
        }
        updateLocationRegistration();
    }

    private void updateLocationRegistration() {
        boolean shouldReceiveLocations = isMonitoringRequired() || !listeners.isEmpty();
        if (!shouldReceiveLocations || !hasFineLocationPermission()) {
            if (locationUpdatesRegistered) {
                locationManager.removeUpdates(this);
                locationUpdatesRegistered = false;
            }
            return;
        }
        if (locationUpdatesRegistered) return;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 500L, 0.0f, this, Looper.getMainLooper());
                locationUpdatesRegistered = true;
            } else {
                synchronized (stateLock) {
                    error = "Turn on Location in Android settings to acquire a GPS fix.";
                }
            }
        } catch (SecurityException securityError) {
            synchronized (stateLock) {
                error = "Precise Location permission is required for GPS monitoring.";
            }
        }
    }

    private void monitorTick() {
        BridgeState state = snapshot();
        if (state.streaming) transmitLatestFix();
        evaluateAnchorHealth();
        if (state.streaming
                && SystemClock.elapsedRealtime() - lastNetworkRefreshElapsedMillis > 5_000L) {
            refreshNetworkAddresses();
        }
        if (snapshot().anchorAlarmTriggered) startAlarmPresentation();
        emitState();
        updateMonitoringNotification();
    }

    private void transmitLatestFix() {
        Location location = copyLatestLocation();
        if (location == null) return;

        double ageSeconds = fixAgeSeconds(location);
        NmeaEncoder.Fix fix = new NmeaEncoder.Fix(
                location.getTime(),
                location.getLatitude(),
                location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : 0.0,
                location.hasSpeed() ? location.getSpeed() : -1.0,
                location.hasBearing() ? location.getBearing() : -1.0,
                location.getAccuracy(),
                location.getAccuracy() >= 0.0f && ageSeconds <= STALE_FIX_SECONDS);
        List<String> sentences = NmeaEncoder.sentences(fix);
        String payload = String.join("", sentences);
        synchronized (stateLock) {
            lastSentences = payload;
        }
        server.send(payload);
    }

    private void evaluateAnchorPosition(Location location) {
        boolean shouldTrigger = false;
        String reason = null;
        long now = SystemClock.elapsedRealtime();
        synchronized (stateLock) {
            if (!anchorAlarmArmed || !hasAnchorReference) return;
            AnchorAlarm.Offset offset = AnchorAlarm.plotOffsetMeters(
                    anchorReferenceLatitude,
                    anchorReferenceLongitude,
                    location.getLatitude(),
                    location.getLongitude());
            anchorTrackPoints.add(new BridgeState.AnchorTrackPoint(
                    offset.eastMeters,
                    offset.northMeters,
                    Math.max(0.0, location.getAccuracy())));
            if (anchorTrackPoints.size() > MAXIMUM_ANCHOR_TRACK_POINTS) {
                anchorTrackPoints.subList(
                        0, anchorTrackPoints.size() - MAXIMUM_ANCHOR_TRACK_POINTS).clear();
            }
            AnchorAlarm.Assessment assessment = AnchorAlarm.assess(
                    anchorReferenceLatitude,
                    anchorReferenceLongitude,
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAccuracy(),
                    anchorRadiusMeters);
            anchorDistanceMeters = assessment.distanceMeters;

            if (!assessment.definitelyOutside) {
                breachStartedElapsedMillis = 0L;
                if (anchorAlarmSilenced) {
                    anchorAlarmSilenced = false;
                    anchorAlertReason = null;
                }
                return;
            }
            if (anchorAlarmTriggered || anchorAlarmSilenced) return;
            if (breachStartedElapsedMillis == 0L) breachStartedElapsedMillis = now;
            if (now - breachStartedElapsedMillis >= BREACH_CONFIRMATION_MILLIS) {
                shouldTrigger = true;
                reason = anchorMode == AnchorAlarm.Mode.SHORELINED
                        ? String.format(Locale.US,
                        "Boat moved %.1f m from the shore-tied position (limit %.0f m).",
                        assessment.distanceMeters, anchorRadiusMeters)
                        : String.format(Locale.US,
                        "Boat moved %.1f m from the anchor (swing limit %.0f m).",
                        assessment.distanceMeters, anchorRadiusMeters);
            }
        }
        if (shouldTrigger) triggerAnchorAlarm(reason);
    }

    private void evaluateAnchorHealth() {
        boolean armed;
        boolean alreadyAlerting;
        boolean silenced;
        long armedAt;
        Location location;
        synchronized (stateLock) {
            armed = anchorAlarmArmed;
            alreadyAlerting = anchorAlarmTriggered;
            silenced = anchorAlarmSilenced;
            armedAt = anchorArmedElapsedMillis;
            location = latestLocation == null ? null : new Location(latestLocation);
        }
        if (!armed || alreadyAlerting || silenced) return;

        double ageSeconds = location == null
                ? (SystemClock.elapsedRealtime() - armedAt) / 1000.0
                : fixAgeSeconds(location);
        if (ageSeconds >= LOST_FIX_ALARM_SECONDS) {
            triggerAnchorAlarm(String.format(Locale.US,
                    "GPS position has been unavailable for %.0f seconds.", ageSeconds));
        }
    }

    private void triggerAnchorAlarm(String reason) {
        synchronized (stateLock) {
            if (!anchorAlarmArmed || anchorAlarmTriggered || anchorAlarmSilenced) return;
            anchorAlarmTriggered = true;
            anchorAlertReason = reason;
        }
        startAlarmPresentation();
        emitState();
        updateMonitoringNotification();
    }

    private synchronized void startAlarmPresentation() {
        synchronized (stateLock) {
            if (!anchorAlarmTriggered) return;
        }
        boolean firstPresentation = !alarmPresentationActive;
        alarmPresentationActive = true;
        if (firstPresentation) {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0);
            } catch (RuntimeException ignored) {
                previousAlarmVolume = -1;
            }
        }
        if (alarmRingtone == null || !alarmRingtone.isPlaying()) {
            try {
                Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
                alarmRingtone = RingtoneManager.getRingtone(this, alarmUri);
                if (alarmRingtone != null) {
                    alarmRingtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    if (Build.VERSION.SDK_INT >= 28) {
                        alarmRingtone.setLooping(true);
                        alarmRingtone.setVolume(1.0f);
                    }
                    alarmRingtone.play();
                }
            } catch (RuntimeException ignored) {
                // The high-importance notification and vibration remain as fallbacks.
            }
        }

        if (firstPresentation) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 700, 300, 700, 800}, 0));
            }
            getSystemService(NotificationManager.class)
                    .notify(ALARM_NOTIFICATION_ID, buildAlarmNotification(snapshot()));
        }
    }

    private synchronized void stopAlarmPresentation() {
        alarmPresentationActive = false;
        if (alarmRingtone != null) {
            alarmRingtone.stop();
            alarmRingtone = null;
        }
        if (previousAlarmVolume >= 0) {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        Math.min(previousAlarmVolume,
                                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)),
                        0);
            } catch (RuntimeException ignored) {
                // The alarm is already stopped; do not let a device policy crash the service.
            }
            previousAlarmVolume = -1;
        }
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.cancel();
        getSystemService(NotificationManager.class).cancel(ALARM_NOTIFICATION_ID);
    }

    private void refreshNetworkAddresses() {
        List<BridgeState.NetworkAddress> addresses = NetworkAddressProvider.currentIpv4Addresses();
        synchronized (stateLock) {
            networkAddresses = addresses;
            lastNetworkRefreshElapsedMillis = SystemClock.elapsedRealtime();
        }
        emitState();
    }

    private BridgeState snapshot() {
        synchronized (stateLock) {
            Location location = latestLocation;
            return new BridgeState(
                    streaming,
                    location != null,
                    location == null ? 0.0 : location.getLatitude(),
                    location == null ? 0.0 : location.getLongitude(),
                    location == null || !location.hasAltitude() ? 0.0 : location.getAltitude(),
                    location == null || !location.hasAccuracy() ? -1.0 : location.getAccuracy(),
                    location == null || !location.hasSpeed() ? -1.0 : location.getSpeed(),
                    location == null || !location.hasBearing() ? -1.0 : location.getBearing(),
                    location == null ? Double.POSITIVE_INFINITY : fixAgeSeconds(location),
                    clientCount,
                    listenerDescription,
                    new ArrayList<>(networkAddresses),
                    lastSentences,
                    error,
                    anchorMode,
                    anchorAlarmArmed,
                    anchorAlarmTriggered,
                    anchorAlarmSilenced,
                    hasAnchorReference,
                    anchorReferenceLatitude,
                    anchorReferenceLongitude,
                    anchorRadiusMeters,
                    anchorDistanceMeters,
                    new ArrayList<>(anchorTrackPoints),
                    anchorAlertReason);
        }
    }

    private Location copyLatestLocation() {
        synchronized (stateLock) {
            return latestLocation == null ? null : new Location(latestLocation);
        }
    }

    private static double fixAgeSeconds(Location location) {
        long locationElapsedNanos = location.getElapsedRealtimeNanos();
        if (locationElapsedNanos > 0L) {
            return Math.max(0.0,
                    (SystemClock.elapsedRealtimeNanos() - locationElapsedNanos) / 1_000_000_000.0);
        }
        return Math.max(0.0, (System.currentTimeMillis() - location.getTime()) / 1000.0);
    }

    private void emitState() {
        BridgeState state = snapshot();
        for (StateListener listener : listeners) {
            listener.onBridgeStateChanged(state);
        }
    }

    private boolean isMonitoringRequired() {
        synchronized (stateLock) {
            return streaming || anchorAlarmArmed;
        }
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":GpsAndAnchorMonitoring");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createNotificationChannels() {
        NotificationChannel monitoringChannel = new NotificationChannel(
                MONITORING_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        monitoringChannel.setDescription(getString(R.string.notification_channel_description));

        NotificationChannel alarmChannel = new NotificationChannel(
                ALARM_CHANNEL_ID,
                getString(R.string.anchor_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        alarmChannel.setDescription(getString(R.string.anchor_alarm_channel_description));
        alarmChannel.setSound(null, null);
        alarmChannel.enableVibration(true);
        alarmChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(monitoringChannel);
        manager.createNotificationChannel(alarmChannel);
    }

    private Notification buildMonitoringNotification(BridgeState state) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, MONITORING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (state.anchorAlarmTriggered) {
            builder.setContentTitle("ANCHOR ALARM")
                    .setContentText(state.anchorAlertReason);
        } else if (state.anchorAlarmArmed) {
            builder.setContentTitle("Anchor alarm armed • " + state.anchorMode.displayName)
                    .setContentText(anchorNotificationText(state));
        } else {
            builder.setContentTitle("GPS to Navionics is running")
                    .setContentText(streamingNotificationText(state));
        }

        if (state.streaming) {
            Intent stopIntent = new Intent(this, BridgeService.class).setAction(ACTION_STOP);
            PendingIntent stopPendingIntent = PendingIntent.getService(
                    this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    null, "Stop streaming", stopPendingIntent).build());
        }
        if (state.anchorAlarmArmed) {
            Intent disarmIntent = new Intent(this, BridgeService.class).setAction(ACTION_DISARM_ANCHOR);
            PendingIntent disarmPendingIntent = PendingIntent.getService(
                    this, 2, disarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    null, "Disarm anchor", disarmPendingIntent).build());
        }
        return builder.build();
    }

    private Notification buildAlarmNotification(BridgeState state) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 3, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent silenceIntent = new Intent(this, BridgeService.class).setAction(ACTION_SILENCE_ANCHOR);
        PendingIntent silencePendingIntent = PendingIntent.getService(
                this, 4, silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disarmIntent = new Intent(this, BridgeService.class).setAction(ACTION_DISARM_ANCHOR);
        PendingIntent disarmPendingIntent = PendingIntent.getService(
                this, 5, disarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("⚠ ANCHOR ALARM")
                .setContentText(state.anchorAlertReason)
                .setStyle(new Notification.BigTextStyle().bigText(state.anchorAlertReason))
                .setContentIntent(openPendingIntent)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "Silence", silencePendingIntent).build())
                .addAction(new Notification.Action.Builder(
                        null, "Disarm", disarmPendingIntent).build())
                .build();
    }

    private String anchorNotificationText(BridgeState state) {
        if (state.anchorAlarmSilenced) return "Alarm silenced • monitoring continues";
        if (!state.hasLocation || state.fixAgeSeconds > STALE_FIX_SECONDS) {
            return String.format(Locale.US, "Acquiring GPS • limit %.0f m", state.anchorRadiusMeters);
        }
        if (Double.isFinite(state.anchorDistanceMeters)) {
            return String.format(Locale.US, "%.1f m from reference • limit %.0f m",
                    state.anchorDistanceMeters, state.anchorRadiusMeters);
        }
        return String.format(Locale.US, "Monitoring • limit %.0f m", state.anchorRadiusMeters);
    }

    private String streamingNotificationText(BridgeState state) {
        if (!state.hasLocation) return "Acquiring GPS fix • TCP " + PORT;
        if (state.fixAgeSeconds > STALE_FIX_SECONDS) return "GPS fix stale • TCP " + PORT;
        if (state.clientCount == 0) return "Waiting for Navionics • TCP " + PORT;
        return "Streaming to " + state.clientCount + " client"
                + (state.clientCount == 1 ? "" : "s") + " • TCP " + PORT;
    }

    private void updateMonitoringNotification() {
        if (!foregroundActive) return;
        getSystemService(NotificationManager.class)
                .notify(MONITORING_NOTIFICATION_ID, buildMonitoringNotification(snapshot()));
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && latitude >= -90.0 && latitude <= 90.0
                && Double.isFinite(longitude) && longitude >= -180.0 && longitude <= 180.0;
    }

}
