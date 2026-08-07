package com.jaco.navionicsgpsbridge;

import java.util.Collections;
import java.util.List;

final class BridgeState {
    static final class AnchorTrackPoint {
        final double eastMeters;
        final double northMeters;
        final double accuracyMeters;

        AnchorTrackPoint(double eastMeters, double northMeters, double accuracyMeters) {
            this.eastMeters = eastMeters;
            this.northMeters = northMeters;
            this.accuracyMeters = accuracyMeters;
        }
    }

    static final class NetworkAddress {
        final String interfaceName;
        final String address;

        NetworkAddress(String interfaceName, String address) {
            this.interfaceName = interfaceName;
            this.address = address;
        }
    }

    final boolean streaming;
    final boolean hasLocation;
    final double latitude;
    final double longitude;
    final double altitudeMeters;
    final double accuracyMeters;
    final double speedMetersPerSecond;
    final double courseDegrees;
    final double fixAgeSeconds;
    final int clientCount;
    final String listenerDescription;
    final List<NetworkAddress> networkAddresses;
    final String lastSentences;
    final String error;
    final AnchorAlarm.Mode anchorMode;
    final boolean anchorAlarmArmed;
    final boolean anchorAlarmTriggered;
    final boolean anchorAlarmSilenced;
    final boolean hasAnchorReference;
    final double anchorReferenceLatitude;
    final double anchorReferenceLongitude;
    final double anchorRadiusMeters;
    final double anchorDistanceMeters;
    final List<AnchorTrackPoint> anchorTrackPoints;
    final String anchorAlertReason;

    BridgeState(
            boolean streaming,
            boolean hasLocation,
            double latitude,
            double longitude,
            double altitudeMeters,
            double accuracyMeters,
            double speedMetersPerSecond,
            double courseDegrees,
            double fixAgeSeconds,
            int clientCount,
            String listenerDescription,
            List<NetworkAddress> networkAddresses,
            String lastSentences,
            String error,
            AnchorAlarm.Mode anchorMode,
            boolean anchorAlarmArmed,
            boolean anchorAlarmTriggered,
            boolean anchorAlarmSilenced,
            boolean hasAnchorReference,
            double anchorReferenceLatitude,
            double anchorReferenceLongitude,
            double anchorRadiusMeters,
            double anchorDistanceMeters,
            List<AnchorTrackPoint> anchorTrackPoints,
            String anchorAlertReason
    ) {
        this.streaming = streaming;
        this.hasLocation = hasLocation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.accuracyMeters = accuracyMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.courseDegrees = courseDegrees;
        this.fixAgeSeconds = fixAgeSeconds;
        this.clientCount = clientCount;
        this.listenerDescription = listenerDescription;
        this.networkAddresses = Collections.unmodifiableList(networkAddresses);
        this.lastSentences = lastSentences;
        this.error = error;
        this.anchorMode = anchorMode;
        this.anchorAlarmArmed = anchorAlarmArmed;
        this.anchorAlarmTriggered = anchorAlarmTriggered;
        this.anchorAlarmSilenced = anchorAlarmSilenced;
        this.hasAnchorReference = hasAnchorReference;
        this.anchorReferenceLatitude = anchorReferenceLatitude;
        this.anchorReferenceLongitude = anchorReferenceLongitude;
        this.anchorRadiusMeters = anchorRadiusMeters;
        this.anchorDistanceMeters = anchorDistanceMeters;
        this.anchorTrackPoints = Collections.unmodifiableList(new java.util.ArrayList<>(anchorTrackPoints));
        this.anchorAlertReason = anchorAlertReason;
    }
}
