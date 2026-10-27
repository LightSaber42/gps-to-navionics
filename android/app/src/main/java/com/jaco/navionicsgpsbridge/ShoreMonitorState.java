package com.jaco.navionicsgpsbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable UI snapshot for the read-only shore monitor. */
final class ShoreMonitorState {
    final boolean paired;
    final boolean enabled;
    final boolean connected;
    final boolean hasBoatState;
    final boolean soundReady;
    final boolean armed;
    final boolean alarmActive;
    final String alarmReason;
    final String mode;
    final double radiusMeters;
    final double distanceMeters;
    final double accuracyMeters;
    final int boatBatteryPercent;
    final boolean boatCharging;
    final String sessionId;
    final long lastHeartbeatAtMillis;
    final List<BridgeState.AnchorTrackPoint> history;
    final String statusMessage;

    ShoreMonitorState(boolean paired, boolean enabled, boolean connected, boolean hasBoatState,
            boolean soundReady,
            boolean armed, boolean alarmActive, String alarmReason, String mode,
            double radiusMeters, double distanceMeters, double accuracyMeters,
            int boatBatteryPercent, boolean boatCharging, String sessionId,
            long lastHeartbeatAtMillis, List<BridgeState.AnchorTrackPoint> history,
            String statusMessage) {
        this.paired = paired;
        this.enabled = enabled;
        this.connected = connected;
        this.hasBoatState = hasBoatState;
        this.soundReady = soundReady;
        this.armed = armed;
        this.alarmActive = alarmActive;
        this.alarmReason = alarmReason;
        this.mode = mode;
        this.radiusMeters = radiusMeters;
        this.distanceMeters = distanceMeters;
        this.accuracyMeters = accuracyMeters;
        this.boatBatteryPercent = boatBatteryPercent;
        this.boatCharging = boatCharging;
        this.sessionId = sessionId;
        this.lastHeartbeatAtMillis = lastHeartbeatAtMillis;
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
        this.statusMessage = statusMessage;
    }
}
