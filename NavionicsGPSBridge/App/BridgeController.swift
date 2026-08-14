import CoreLocation
import Foundation
import AVFoundation
import UIKit
import UserNotifications

@MainActor
final class BridgeController: NSObject, ObservableObject,
    @preconcurrency CLLocationManagerDelegate,
    UNUserNotificationCenterDelegate {
    @Published private(set) var isStreaming = false
    @Published private(set) var authorizationStatus: CLAuthorizationStatus
    @Published private(set) var latestLocation: CLLocation?
    @Published private(set) var connectedClientCount = 0
    @Published private(set) var listenerDescription = "Stopped"
    @Published private(set) var networkAddresses: [NetworkAddress] = []
    @Published private(set) var lastSentences = ""
    @Published private(set) var lastError: String?
    @Published private(set) var anchorMode = AnchorAlarmMode.shorelined
    @Published private(set) var anchorRadiusMeters = 15.0
    @Published private(set) var isLayingAnchor = false
    @Published private(set) var anchorAlarmArmed = false
    @Published private(set) var anchorAlarmTriggered = false
    @Published private(set) var anchorAlarmSilenced = false
    @Published private(set) var anchorReferenceCoordinate: CLLocationCoordinate2D?
    @Published private(set) var anchorDistanceMeters: Double?
    @Published private(set) var anchorTrackPoints: [AnchorTrackPoint] = []
    @Published private(set) var anchorAlertReason: String?
    @Published private(set) var anchorActionMessage: String?
    @Published private(set) var shoreMonitorConnected = false
    @Published private(set) var shoreMonitorSoundReady = false
    @Published private(set) var anchorPairingURL: URL?
    @Published private(set) var entitlementDecision = EntitlementDecision(featureLevel: .free)
    @Published private(set) var displayUnitPreference = DisplayUnitPreference.system
    @Published private(set) var notificationAuthorizationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var anchorReadinessPresented = false
    @Published private(set) var anchorReadinessWasCompleted = false
    @Published private(set) var readinessSirenConfirmed = false
    @Published private(set) var readinessSafetyAcknowledged = false
    @Published private(set) var readinessSirenPlaying = false
    @Published private(set) var readinessSirenPlayedThisVisit = false
    @Published private var clock = Date()

    let port: UInt16 = 11_123

    private enum PendingAnchorAction {
        case mark
        case arm
    }

    private enum DefaultsKey {
        static let mode = "anchor.mode"
        static let radius = "anchor.radius"
        static let hasReference = "anchor.hasReference"
        static let referenceLatitude = "anchor.referenceLatitude"
        static let referenceLongitude = "anchor.referenceLongitude"
        static let armed = "anchor.armed"
        static let sessionLeaseMigrationCompleted =
            "anchor.sessionEntitlementLeaseMigrationCompleted.v1"
        static let readinessCompleted = "anchor.readiness.completed.v1"
        static let readinessSirenConfirmed = "anchor.readiness.sirenConfirmed.v1"
        static let readinessSafetyAcknowledged =
            "anchor.readiness.safetyAcknowledged.v1"
        static let displayUnitPreference = "display.units.preference.v1"
    }

    private static let alarmCategoryIdentifier = "ANCHOR_ALARM"
    private static let silenceActionIdentifier = "SILENCE_ANCHOR_ALARM"
    private static let disarmActionIdentifier = "DISARM_ANCHOR_ALARM"
    private static let alarmNotificationPrefix = "anchor-alarm."
    private static let staleFixSeconds: TimeInterval = 10
    private static let maximumAnchorReferenceAccuracyMeters = 15.0
    private static let lostFixAlarmSeconds: TimeInterval = 30
    private static let breachConfirmationSeconds: TimeInterval = 5

    private static func effectiveEntitlementDecision(
        _ decision: EntitlementDecision
    ) -> EntitlementDecision {
#if DEBUG
        if !ProcessInfo.processInfo.arguments.contains("-UseStoreEntitlements") {
            return EntitlementDecision(featureLevel: .remoteAnchorWatch)
        }
#endif
        return decision
    }

    private let locationManager = CLLocationManager()
    private let server = NMEATCPServer()
    private let anchorSyncServer = AnchorSyncServer()
    private let notificationCenter = UNUserNotificationCenter.current()
    private let defaults = UserDefaults.standard
    private let pairingStore = AnchorPairingStore()
    private let anchorHistoryStore = AnchorHistoryStore()
    private let anchorEventLog = AnchorEventLog()
    private let sessionLeaseStore = SessionEntitlementLeaseStore()
    private var monitorTimer: Timer?
    private var locationUpdatesActive = false
    private var pendingAnchorAction: PendingAnchorAction?
    private var pendingAnchorActionStartedAt: Date?
    private var anchorArmedAt: Date?
    private var breachStartedAt: Date?
    private var alarmAudioPlayer: AVAudioPlayer?
    private var readinessSirenTask: Task<Void, Never>?
    private var resumeArmingAfterReadiness = false
    private var pairingConfiguration: AnchorPairingConfiguration?
    private var sessionEntitlementLease: SessionEntitlementLease?
    private var breachEventIssued = false
    private var lostFixEventIssued = false
    private var lowBatteryEventIssued = false
    private var criticalBatteryEventIssued = false

    override init() {
        authorizationStatus = locationManager.authorizationStatus
        anchorReadinessWasCompleted = defaults.bool(forKey: DefaultsKey.readinessCompleted)
        readinessSirenConfirmed = defaults.bool(forKey: DefaultsKey.readinessSirenConfirmed)
        readinessSafetyAcknowledged = defaults.bool(
            forKey: DefaultsKey.readinessSafetyAcknowledged
        )
        displayUnitPreference = DisplayUnitPreference(
            rawValue: defaults.string(forKey: DefaultsKey.displayUnitPreference) ?? ""
        ) ?? .system
        let entitlementCache = EntitlementCacheStore().load()
        entitlementDecision = Self.effectiveEntitlementDecision(
            EntitlementPolicy.evaluate(
                anchorWatch: entitlementCache.unavailableEvidence(for: .anchorWatch),
                remoteAnchorWatch: entitlementCache.unavailableEvidence(for: .remoteAnchorWatch),
                at: Date()
            )
        )
        super.init()

        loadAnchorConfiguration()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.activityType = .otherNavigation
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true
        UIDevice.current.isBatteryMonitoringEnabled = true

        notificationCenter.delegate = self
        registerAlarmNotificationCategory()

        server.onStateChange = { [weak self] state in
            DispatchQueue.main.async {
                self?.handleServerState(state)
            }
        }
        server.onClientCountChange = { [weak self] count in
            DispatchQueue.main.async {
                self?.connectedClientCount = count
            }
        }

        anchorSyncServer.onMonitorStatusChange = { [weak self] status in
            DispatchQueue.main.async {
                self?.shoreMonitorConnected = status.connected
                self?.shoreMonitorSoundReady = status.soundReady
            }
        }
        anchorSyncServer.onError = { [weak self] message in
            DispatchQueue.main.async {
                self?.lastError = message
            }
        }
        pairingConfiguration = pairingStore.load()

        if defaults.bool(forKey: DefaultsKey.armed), anchorReferenceCoordinate != nil {
            if restoreSessionEntitlementLease() {
                anchorAlarmArmed = true
                anchorArmedAt = Date()
                anchorTrackPoints = anchorHistoryStore.renderedPoints
                updateLocationMonitoring()
                updateMonitorTimer()
            } else {
                resetPersistedAnchorAfterMissingLease()
            }
        } else {
            sessionLeaseStore.clear()
            anchorReferenceCoordinate = nil
            defaults.set(false, forKey: DefaultsKey.hasReference)
            defaults.set(false, forKey: DefaultsKey.armed)
        }
        defaults.set(true, forKey: DefaultsKey.sessionLeaseMigrationCompleted)
        reconcileAnchorSyncServer()
        refreshNetworkAddresses()
        publishAnchorSyncState()
        Task { @MainActor [weak self] in
            await self?.refreshAnchorReadiness()
        }
    }

    deinit {
        readinessSirenTask?.cancel()
    }

    var canUseAnchorWatch: Bool {
        EntitlementSessionAccess.authorizes(
            .anchorWatch,
            decision: entitlementDecision,
            activeSessionID: activeEntitledSessionID,
            lease: sessionEntitlementLease
        )
    }

    var canUseRemoteAnchorWatch: Bool {
        EntitlementSessionAccess.authorizes(
            .remoteAnchorWatch,
            decision: entitlementDecision,
            activeSessionID: activeEntitledSessionID,
            lease: sessionEntitlementLease
        )
    }

    var statusTitle: String {
        guard isStreaming else { return "Stopped" }

        switch authorizationStatus {
        case .denied, .restricted:
            return "Location permission needed"
        case .notDetermined:
            return "Waiting for permission"
        default:
            break
        }

        guard let latestLocation else { return "Acquiring GPS fix" }
        guard fixAge(for: latestLocation) <= Self.staleFixSeconds else {
            return "GPS fix is stale"
        }
        guard connectedClientCount > 0 else { return "Waiting for Navionics" }
        return "Streaming to Navionics"
    }

    var statusColorName: String {
        if !isStreaming { return "secondary" }
        if authorizationStatus == .denied || authorizationStatus == .restricted { return "red" }
        if latestLocation == nil || currentFixAge > Self.staleFixSeconds { return "orange" }
        return connectedClientCount > 0 ? "green" : "blue"
    }

    var anchorStatusTitle: String {
        if anchorAlarmTriggered { return "ANCHOR ALARM" }
        if anchorAlarmSilenced { return "Alarm silenced • still monitoring" }
        if anchorAlarmArmed { return "Armed • \(anchorMode.displayName)" }
        if isLayingAnchor, anchorReferenceCoordinate == nil {
            return "Acquiring anchor position"
        }
        if isLayingAnchor { return "Laying anchor" }
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            return "Record the anchor position"
        }
        return "Anchor alarm ready"
    }

    var anchorStatusColorName: String {
        if anchorAlarmTriggered { return "red" }
        if anchorAlarmSilenced { return "orange" }
        if anchorAlarmArmed { return "green" }
        if isLayingAnchor { return "blue" }
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil { return "blue" }
        return "blue"
    }

    var anchorPrimaryButtonTitle: String {
        if anchorAlarmArmed { return "Disarm anchor alarm" }
        if isAcquiringAnchorReference { return "Acquiring GPS position…" }
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            return "Drop anchor"
        }
        return "Arm anchor alarm"
    }

    var anchorReferenceDescription: String {
        guard let coordinate = anchorReferenceCoordinate else { return "Not set" }
        return String(format: "%.6f, %.6f", coordinate.latitude, coordinate.longitude)
    }

    var anchorDistanceDescription: String {
        guard let anchorDistanceMeters else { return "—" }
        return "\(formatDistance(anchorDistanceMeters, fractionDigits: 1)) / "
            + "\(formatDistance(anchorRadiusMeters)) limit"
    }

    var authorizationDescription: String {
        switch authorizationStatus {
        case .notDetermined: return "Not requested"
        case .restricted: return "Restricted"
        case .denied: return "Denied"
        case .authorizedAlways: return "Always"
        case .authorizedWhenInUse: return "While Using"
        @unknown default: return "Unknown"
        }
    }

    var preciseLocationReady: Bool {
        let locationAuthorized = authorizationStatus == .authorizedAlways
            || authorizationStatus == .authorizedWhenInUse
        return locationAuthorized && locationManager.accuracyAuthorization == .fullAccuracy
    }

    var backgroundLocationReady: Bool {
        authorizationStatus == .authorizedAlways
    }

    var notificationsReady: Bool {
        switch notificationAuthorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined, .denied:
            return false
        @unknown default:
            return false
        }
    }

    var anchorReadinessRequirementsSatisfied: Bool {
        preciseLocationReady
            && backgroundLocationReady
            && notificationsReady
            && readinessSirenConfirmed
            && readinessSafetyAcknowledged
    }

    var anchorReadinessComplete: Bool {
        anchorReadinessWasCompleted && anchorReadinessRequirementsSatisfied
    }

    var anchorReadinessWillResumeArming: Bool {
        resumeArmingAfterReadiness
    }

    var preciseLocationReadinessDescription: String {
        if !preciseLocationReady {
            return authorizationStatus == .denied || authorizationStatus == .restricted
                ? "Enable Precise Location in Settings"
                : "Precise Location is required"
        }
        return "Precise Location enabled"
    }

    var backgroundLocationReadinessDescription: String {
        switch authorizationStatus {
        case .authorizedAlways:
            return "Always Allow enabled"
        case .authorizedWhenInUse:
            return "Change permission to Always Allow"
        case .notDetermined:
            return "Location permission not requested"
        case .denied, .restricted:
            return "Background location unavailable"
        @unknown default:
            return "Location permission unavailable"
        }
    }

    var notificationReadinessDescription: String {
        switch notificationAuthorizationStatus {
        case .authorized:
            return "Alerts and sound authorized"
        case .provisional, .ephemeral:
            return "Notifications authorized provisionally"
        case .notDetermined:
            return "Notification permission not requested"
        case .denied:
            return "Enable notifications and sound in Settings"
        @unknown default:
            return "Notification status unavailable"
        }
    }

    var currentFixAge: TimeInterval {
        guard let latestLocation else { return .infinity }
        return fixAge(for: latestLocation)
    }

    var fixAgeDescription: String {
        guard currentFixAge.isFinite else { return "—" }
        return String(format: "%.1f s", max(0, currentFixAge))
    }

    var coordinateDescription: String {
        guard let latestLocation else { return "—" }
        return String(
            format: "%.6f, %.6f",
            latestLocation.coordinate.latitude,
            latestLocation.coordinate.longitude
        )
    }

    var accuracyDescription: String {
        guard let latestLocation, latestLocation.horizontalAccuracy >= 0 else { return "—" }
        return distanceDisplayFormatter.formatAccuracy(
            metres: latestLocation.horizontalAccuracy
        )
    }

    var degradedAnchorGPSWarning: String? {
        guard currentFixAge <= Self.staleFixSeconds else { return nil }
        return AnchorGPSQuality.degradedWarning(
            accuracyMeters: latestLocation?.horizontalAccuracy,
            radiusMeters: anchorRadiusMeters,
            displayFormatter: distanceDisplayFormatter
        )
    }

    var displayUnitPreferenceDetail: String {
        displayUnitPreference.detail()
    }

    func formatDistance(_ metres: Double, fractionDigits: Int = 0) -> String {
        distanceDisplayFormatter.format(
            metres: metres,
            fractionDigits: fractionDigits
        )
    }

    func formatAccuracy(_ metres: Double) -> String {
        distanceDisplayFormatter.formatAccuracy(metres: metres)
    }

    func formatRadiusRange(for mode: AnchorAlarmMode) -> String {
        distanceDisplayFormatter.range(
            minimumMetres: mode.minimumRadiusMeters,
            maximumMetres: mode.maximumRadiusMeters,
            stepMetres: mode.radiusStepMeters
        )
    }

    func setDisplayUnitPreference(_ preference: DisplayUnitPreference) {
        displayUnitPreference = preference
        defaults.set(preference.rawValue, forKey: DefaultsKey.displayUnitPreference)
    }

    private var distanceDisplayFormatter: DistanceDisplayFormatter {
        DistanceDisplayFormatter(preference: displayUnitPreference)
    }

    var speedDescription: String {
        guard let latestLocation, latestLocation.speed >= 0 else { return "—" }
        return String(format: "%.1f kn", latestLocation.speed * 1.943_844_492_4)
    }

    var courseDescription: String {
        guard let latestLocation, latestLocation.course >= 0 else { return "—" }
        return String(format: "%.1f°", latestLocation.course)
    }

    func toggleStreaming() {
        isStreaming ? stopStreaming() : startStreaming()
    }

    func startStreaming() {
        guard !isStreaming else { return }
        isStreaming = true
        lastError = nil
        updateIdleTimer()

        server.start(port: port)
        updateMonitorTimer()
        refreshNetworkAddresses()

        switch authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            updateLocationMonitoring()
        case .denied, .restricted:
            lastError = "Enable Precise Location in Settings to transmit a usable marine position."
        @unknown default:
            lastError = "The current location authorization state is unsupported."
        }
    }

    func stopStreaming() {
        guard isStreaming else { return }
        server.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        connectedClientCount = 0
        listenerDescription = "Stopped"
        isStreaming = false
        updateIdleTimer()
        updateMonitorTimer()
        updateLocationMonitoring()
    }

    func updateEntitlementDecision(_ decision: EntitlementDecision) {
        let effectiveDecision = Self.effectiveEntitlementDecision(decision)
        entitlementDecision = effectiveDecision
        if anchorAlarmArmed,
           let sessionID = anchorHistoryStore.sessionID,
           let currentLease = sessionEntitlementLease,
           let upgradedLease = EntitlementSessionAccess.upgradedLease(
                currentLease,
                activeSessionID: sessionID,
                decision: effectiveDecision,
                at: Date()
           ),
           sessionLeaseStore.save(upgradedLease) {
            sessionEntitlementLease = upgradedLease
        }
        reconcileAnchorSyncServer()
    }

    func setAnchorMode(_ mode: AnchorAlarmMode) {
        guard canUseAnchorWatch,
              !anchorAlarmArmed,
              !isLayingAnchor,
              anchorMode != mode else { return }
        anchorMode = mode
        anchorRadiusMeters = mode.defaultRadiusMeters
        anchorReferenceCoordinate = nil
        anchorDistanceMeters = nil
        clearAnchorTrack()
        anchorActionMessage = nil
        pendingAnchorAction = nil
        pendingAnchorActionStartedAt = nil
        persistAnchorConfiguration()
    }

    func setAnchorRadius(_ radiusMeters: Double) {
        guard canUseAnchorWatch else { return }
        let adjustedRadius = anchorMode.adjustedRadius(radiusMeters)
        guard adjustedRadius != anchorRadiusMeters else { return }
        anchorRadiusMeters = adjustedRadius
        persistAnchorConfiguration()
        if anchorAlarmArmed {
            if let latestLocation,
               fixAge(for: latestLocation) <= Self.staleFixSeconds {
                evaluateAnchorPosition(latestLocation)
            }
        }
    }

    func toggleAnchorAlarm() {
        anchorActionMessage = nil
        if anchorAlarmArmed {
            disarmAnchorAlarm()
        } else if !hasCurrentAnchorEntitlement {
            anchorActionMessage = "Unlock Anchor Watch before starting a new watch."
        } else if isLayingAnchor {
            guard anchorReferenceCoordinate != nil else { return }
            guard ensureReadinessBeforeArming() else { return }
            prepareForAnchorAction(.arm)
        } else if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            markAnchorPosition()
        } else {
            guard ensureReadinessBeforeArming() else { return }
            prepareForAnchorAction(.arm)
        }
    }

    func markAnchorPosition() {
        guard hasCurrentAnchorEntitlement,
              !anchorAlarmArmed,
              anchorMode == .freeSwinging else {
            anchorActionMessage = "Unlock Anchor Watch before recording an anchor position."
            return
        }
        isLayingAnchor = true
        anchorReferenceCoordinate = nil
        anchorDistanceMeters = nil
        clearAnchorTrack()
        _ = anchorHistoryStore.startNewSession()
        anchorActionMessage = nil
        persistAnchorConfiguration(armed: false)
        updateIdleTimer()
        prepareForAnchorAction(.mark)
    }

    func armAnchorAlarm() {
        guard hasCurrentAnchorEntitlement,
              !anchorAlarmArmed,
              anchorReferenceCoordinate != nil else {
            if !hasCurrentAnchorEntitlement {
                anchorActionMessage = "Unlock Anchor Watch before starting a new watch."
            }
            return
        }
        guard ensureReadinessBeforeArming() else { return }
        anchorActionMessage = nil
        prepareForAnchorAction(.arm)
    }

    func cancelAnchorSetup() {
        guard isLayingAnchor else { return }
        isLayingAnchor = false
        pendingAnchorAction = nil
        pendingAnchorActionStartedAt = nil
        anchorReferenceCoordinate = nil
        anchorDistanceMeters = nil
        anchorActionMessage = nil
        clearAnchorTrack()
        _ = anchorHistoryStore.startNewSession()
        persistAnchorConfiguration(armed: false)
        updateIdleTimer()
        updateLocationMonitoring()
        publishAnchorSyncState(immediately: true)
    }

    func silenceAnchorAlarm() {
        guard anchorAlarmTriggered else { return }
        anchorEventLog.acknowledgeLocally()
        anchorAlarmTriggered = false
        anchorAlarmSilenced = true
        anchorAlertReason = "Alarm silenced — monitoring continues until the boat is back inside the limit."
        breachStartedAt = nil
        stopAlarmAudio()
        cancelAlarmNotifications()
        publishAnchorSyncState(immediately: true)
    }

    func disarmAnchorAlarm() {
        isLayingAnchor = false
        anchorAlarmArmed = false
        anchorAlarmTriggered = false
        anchorAlarmSilenced = false
        anchorAlertReason = nil
        anchorActionMessage = nil
        pendingAnchorAction = nil
        pendingAnchorActionStartedAt = nil
        anchorReferenceCoordinate = nil
        anchorDistanceMeters = nil
        clearAnchorTrack()
        anchorEventLog.acknowledgeLocally()
        anchorArmedAt = nil
        breachStartedAt = nil
        stopAlarmAudio()
        sessionEntitlementLease = nil
        sessionLeaseStore.clear()
        persistAnchorConfiguration(armed: false)
        cancelAlarmNotifications()
        updateIdleTimer()
        updateMonitorTimer()
        updateLocationMonitoring()
        publishAnchorSyncState(immediately: true)
        reconcileAnchorSyncServer()
    }

    func rotateAnchorPairing() {
        guard canUseRemoteAnchorWatch else {
            lastError = "Remote Anchor Watch is required to create a pairing code."
            return
        }
        do {
            let configuration = try pairingStore.rotate()
            pairingConfiguration = configuration
            anchorSyncServer.stop()
            anchorSyncServer.start(pairingKey: configuration.pairingKey)
            shoreMonitorConnected = false
            shoreMonitorSoundReady = false
            updateAnchorPairingURL()
        } catch {
            lastError = "A new pairing code could not be created: \(error.localizedDescription)"
        }
    }

    func refreshAnchorPairing() {
        guard canUseRemoteAnchorWatch else {
            anchorPairingURL = nil
            return
        }
        refreshNetworkAddresses()
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    func showAnchorReadinessSettings() {
        presentAnchorReadiness(resumeArming: false)
    }

    func dismissAnchorReadiness() {
        stopReadinessTestSiren()
        anchorReadinessPresented = false
        resumeArmingAfterReadiness = false
    }

    func requestReadinessLocationAccess() {
        switch authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            locationManager.requestAlwaysAuthorization()
        case .authorizedAlways:
            if locationManager.accuracyAuthorization != .fullAccuracy {
                openSettings()
            }
        case .denied, .restricted:
            openSettings()
        @unknown default:
            openSettings()
        }
    }

    func requestReadinessNotificationAccess() {
        if notificationAuthorizationStatus == .denied {
            openSettings()
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                _ = try await notificationCenter.requestAuthorization(
                    options: [.alert, .sound, .badge]
                )
                await refreshAnchorReadiness()
            } catch {
                lastError = "Anchor alarm notifications could not be enabled: \(error.localizedDescription)"
            }
        }
    }

    func refreshAnchorReadiness() async {
        let settings = await notificationCenter.notificationSettings()
        notificationAuthorizationStatus = settings.authorizationStatus
        invalidateAnchorReadinessIfNeeded()
    }

    func playReadinessTestSiren() {
        guard !anchorAlarmArmed else { return }
        readinessSirenTask?.cancel()
        readinessSirenPlayedThisVisit = true
        startAlarmAudio()
        readinessSirenPlaying = alarmAudioPlayer != nil
        guard readinessSirenPlaying else { return }
        readinessSirenTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            self?.stopReadinessTestSiren()
        }
    }

    func confirmReadinessSirenHeard() {
        guard readinessSirenPlayedThisVisit else { return }
        stopReadinessTestSiren()
        readinessSirenConfirmed = true
        defaults.set(true, forKey: DefaultsKey.readinessSirenConfirmed)
    }

    func setReadinessSafetyAcknowledged(_ acknowledged: Bool) {
        readinessSafetyAcknowledged = acknowledged
        defaults.set(acknowledged, forKey: DefaultsKey.readinessSafetyAcknowledged)
        if !acknowledged {
            anchorReadinessWasCompleted = false
            defaults.set(false, forKey: DefaultsKey.readinessCompleted)
        }
    }

    func completeAnchorReadinessAndContinue() {
        guard anchorReadinessRequirementsSatisfied else { return }
        stopReadinessTestSiren()
        anchorReadinessWasCompleted = true
        defaults.set(true, forKey: DefaultsKey.readinessCompleted)
        let shouldResumeArming = resumeArmingAfterReadiness
        anchorReadinessPresented = false
        resumeArmingAfterReadiness = false
        if shouldResumeArming {
            toggleAnchorAlarm()
        }
    }

    func refreshNetworkAddresses() {
        networkAddresses = NetworkAddressProvider.currentIPv4Addresses()
        updateAnchorPairingURL()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        invalidateAnchorReadinessIfNeeded()

        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            lastError = nil
            updateLocationMonitoring()
            tryCompletePendingAnchorAction()
        case .denied, .restricted:
            locationUpdatesActive = false
            manager.stopUpdatingLocation()
            lastError = "Enable Precise Location in Settings for GPS streaming and the anchor alarm."
            if isLayingAnchor {
                anchorActionMessage = "Enable Precise Location in Settings to record the anchor position."
            } else {
                pendingAnchorAction = nil
                pendingAnchorActionStartedAt = nil
            }
            if anchorAlarmArmed {
                triggerAnchorAlarm(
                    reason: "Location permission is unavailable, so the anchor position cannot be monitored.",
                    kind: "location_unavailable"
                )
            }
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        latestLocation = location
        clock = Date()
        evaluateAnchorPosition(location)
        lostFixEventIssued = false
        tryCompletePendingAnchorAction()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let locationError = error as? CLError, locationError.code == .locationUnknown {
            return
        }
        lastError = error.localizedDescription
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let actionIdentifier = response.actionIdentifier
        await MainActor.run { [weak self] in
            if actionIdentifier == Self.silenceActionIdentifier {
                self?.silenceAnchorAlarm()
            } else if actionIdentifier == Self.disarmActionIdentifier {
                self?.disarmAnchorAlarm()
            }
        }
    }

    private func ensureReadinessBeforeArming() -> Bool {
        guard !anchorReadinessComplete else { return true }
        presentAnchorReadiness(resumeArming: true)
        return false
    }

    private func presentAnchorReadiness(resumeArming: Bool) {
        stopReadinessTestSiren()
        readinessSirenPlayedThisVisit = false
        resumeArmingAfterReadiness = resumeArming
        anchorReadinessPresented = true
        Task { @MainActor [weak self] in
            await self?.refreshAnchorReadiness()
        }
    }

    private func invalidateAnchorReadinessIfNeeded() {
        guard anchorReadinessWasCompleted,
              !anchorReadinessRequirementsSatisfied else { return }
        anchorReadinessWasCompleted = false
        defaults.set(false, forKey: DefaultsKey.readinessCompleted)
    }

    private func stopReadinessTestSiren() {
        readinessSirenTask?.cancel()
        readinessSirenTask = nil
        if !anchorAlarmTriggered {
            stopAlarmAudio()
        }
        readinessSirenPlaying = false
    }

    private func prepareForAnchorAction(_ action: PendingAnchorAction) {
        guard hasCurrentAnchorEntitlement else {
            pendingAnchorAction = nil
            pendingAnchorActionStartedAt = nil
            anchorActionMessage = "Unlock Anchor Watch before starting a new watch."
            return
        }
        pendingAnchorAction = action
        if actionRequiresNewAnchorReference(action) {
            pendingAnchorActionStartedAt = Date()
            anchorActionMessage = "Acquiring a new GPS position…"
        } else {
            pendingAnchorActionStartedAt = nil
        }
        switch authorizationStatus {
        case .notDetermined:
            anchorActionMessage = "Allow Precise Location, then wait for a fresh GPS fix."
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            updateLocationMonitoring()
            tryCompletePendingAnchorAction()
        case .denied, .restricted:
            anchorActionMessage = "Enable Precise Location in Settings before using the anchor alarm."
            if !isLayingAnchor {
                pendingAnchorAction = nil
                pendingAnchorActionStartedAt = nil
            }
        @unknown default:
            pendingAnchorAction = nil
            pendingAnchorActionStartedAt = nil
            anchorActionMessage = "The current location authorization state is unsupported."
        }
    }

    private func tryCompletePendingAnchorAction() {
        guard let action = pendingAnchorAction else { return }
        guard hasCurrentAnchorEntitlement else {
            pendingAnchorAction = nil
            pendingAnchorActionStartedAt = nil
            anchorActionMessage = "Anchor Watch access is required before this watch can start."
            return
        }
        guard let latestLocation,
              latestLocation.horizontalAccuracy >= 0,
              fixAge(for: latestLocation) <= Self.staleFixSeconds else {
            anchorActionMessage = "Acquiring a fresh GPS position…"
            return
        }

        if actionRequiresNewAnchorReference(action) {
            guard let startedAt = pendingAnchorActionStartedAt,
                  latestLocation.timestamp > startedAt else {
                anchorActionMessage = "Acquiring a new GPS position…"
                return
            }
            guard latestLocation.horizontalAccuracy
                    <= Self.maximumAnchorReferenceAccuracyMeters else {
                anchorActionMessage = "Improving GPS accuracy… currently "
                    + "\(formatAccuracy(latestLocation.horizontalAccuracy)); need "
                    + "\(formatAccuracy(Self.maximumAnchorReferenceAccuracyMeters))."
                return
            }
        }

        pendingAnchorAction = nil
        pendingAnchorActionStartedAt = nil
        switch action {
        case .mark:
            anchorReferenceCoordinate = latestLocation.coordinate
            anchorDistanceMeters = 0
            anchorActionMessage = "Anchor-drop position recorded. Arm after paying out the rode."
            appendAnchorTrackPoint(latestLocation, relativeTo: latestLocation.coordinate)
            persistAnchorConfiguration()
            updateLocationMonitoring()
        case .arm:
            let wasLayingAnchor = isLayingAnchor
            guard anchorReadinessComplete else {
                pendingAnchorAction = nil
                pendingAnchorActionStartedAt = nil
                anchorActionMessage = "Complete the safety readiness check before arming."
                presentAnchorReadiness(resumeArming: true)
                updateLocationMonitoring()
                return
            }
            guard anchorMode == .shorelined || anchorReferenceCoordinate != nil else {
                anchorActionMessage = "Record the anchor-drop position before arming free-swinging mode."
                updateLocationMonitoring()
                return
            }
            let sessionID: String
            if wasLayingAnchor, let existingSessionID = anchorHistoryStore.sessionID {
                sessionID = existingSessionID
            } else {
                anchorDistanceMeters = nil
                clearAnchorTrack()
                sessionID = anchorHistoryStore.startNewSession()
            }
            guard let lease = EntitlementSessionAccess.leaseForStartingSession(
                    sessionID: sessionID,
                    decision: entitlementDecision,
                    at: Date()
                  ),
                  sessionLeaseStore.save(lease) else {
                anchorActionMessage = "Anchor Watch access could not be saved safely. Try arming again."
                updateLocationMonitoring()
                return
            }
            sessionEntitlementLease = lease
            if anchorMode == .shorelined {
                anchorReferenceCoordinate = latestLocation.coordinate
            }
            isLayingAnchor = false
            anchorAlarmArmed = true
            anchorAlarmTriggered = false
            anchorAlarmSilenced = false
            anchorAlertReason = nil
            anchorActionMessage = nil
            anchorArmedAt = Date()
            breachStartedAt = nil
            breachEventIssued = false
            lostFixEventIssued = false
            lowBatteryEventIssued = false
            criticalBatteryEventIssued = false
            persistAnchorConfiguration(armed: true)
            requestAlarmNotificationAuthorization()
            updateIdleTimer()
            updateLocationMonitoring()
            updateMonitorTimer()
            evaluateAnchorPosition(latestLocation)
            reconcileAnchorSyncServer()
            publishAnchorSyncState(immediately: true)
            if authorizationStatus == .authorizedWhenInUse {
                locationManager.requestAlwaysAuthorization()
            }
        }
    }

    private func updateLocationMonitoring() {
        let authorized = authorizationStatus == .authorizedAlways
            || authorizationStatus == .authorizedWhenInUse
        let monitoringRequired = isStreaming || anchorAlarmArmed
        let locationRequired = monitoringRequired || isLayingAnchor || pendingAnchorAction != nil

        guard authorized, locationRequired else {
            if locationUpdatesActive {
                locationManager.stopUpdatingLocation()
                locationUpdatesActive = false
            }
            return
        }

        locationManager.allowsBackgroundLocationUpdates = monitoringRequired
        if !locationUpdatesActive {
            locationManager.startUpdatingLocation()
            locationUpdatesActive = true
        }
    }

    private var isAcquiringAnchorReference: Bool {
        guard let pendingAnchorAction else { return false }
        return actionRequiresNewAnchorReference(pendingAnchorAction)
    }

    private func actionRequiresNewAnchorReference(_ action: PendingAnchorAction) -> Bool {
        switch action {
        case .mark:
            return true
        case .arm:
            return anchorMode == .shorelined
        }
    }

    private func updateMonitorTimer() {
        let shouldRun = isStreaming || anchorAlarmArmed
        if !shouldRun {
            monitorTimer?.invalidate()
            monitorTimer = nil
            return
        }
        guard monitorTimer == nil else { return }
        monitorTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.monitorTick()
            }
        }
        if let monitorTimer {
            RunLoop.main.add(monitorTimer, forMode: .common)
        }
    }

    private func monitorTick() {
        clock = Date()
        if isStreaming {
            refreshNetworkAddresses()
            transmitLatestFix()
        }
        evaluateAnchorHealth()
        evaluateBatteryHealth()
        publishAnchorSyncState()
    }

    private func transmitLatestFix() {
        guard let latestLocation else { return }
        let age = fixAge(for: latestLocation)
        let fix = NMEAFix(
            timestamp: latestLocation.timestamp,
            latitude: latestLocation.coordinate.latitude,
            longitude: latestLocation.coordinate.longitude,
            altitudeMeters: latestLocation.altitude,
            speedMetersPerSecond: latestLocation.speed,
            courseDegrees: latestLocation.course,
            horizontalAccuracyMeters: latestLocation.horizontalAccuracy,
            isValid: latestLocation.horizontalAccuracy >= 0 && age <= Self.staleFixSeconds
        )
        let sentences = NMEAEncoder.sentences(for: fix)
        lastSentences = sentences.joined()
        server.send(sentences: sentences)
    }

    private func evaluateAnchorPosition(_ location: CLLocation) {
        guard (anchorAlarmArmed || isLayingAnchor),
              let reference = anchorReferenceCoordinate else { return }
        appendAnchorTrackPoint(location, relativeTo: reference)
        let assessment = AnchorAlarmGeometry.assess(
            referenceLatitude: reference.latitude,
            referenceLongitude: reference.longitude,
            currentLatitude: location.coordinate.latitude,
            currentLongitude: location.coordinate.longitude,
            currentAccuracyMeters: location.horizontalAccuracy,
            radiusMeters: anchorRadiusMeters
        )
        anchorDistanceMeters = assessment.distanceMeters

        guard anchorAlarmArmed else { return }

        guard assessment.isDefinitelyOutside else {
            breachStartedAt = nil
            breachEventIssued = false
            if anchorAlarmSilenced {
                anchorAlarmSilenced = false
                anchorAlertReason = nil
            }
            return
        }
        guard !breachEventIssued else { return }

        let now = Date()
        if breachStartedAt == nil { breachStartedAt = now }
        guard let breachStartedAt,
              now.timeIntervalSince(breachStartedAt) >= Self.breachConfirmationSeconds else {
            return
        }

        let reason: String
        switch anchorMode {
        case .shorelined:
            reason = "Boat moved "
                + "\(formatDistance(assessment.distanceMeters, fractionDigits: 1)) "
                + "from the shore-tied position "
                + "(limit \(formatDistance(anchorRadiusMeters)))."
        case .freeSwinging:
            reason = "Boat moved "
                + "\(formatDistance(assessment.distanceMeters, fractionDigits: 1)) "
                + "from the anchor (swing limit \(formatDistance(anchorRadiusMeters)))."
        }
        breachEventIssued = true
        triggerAnchorAlarm(reason: reason, kind: "drift")
    }

    private func evaluateAnchorHealth() {
        guard anchorAlarmArmed else { return }
        let age: TimeInterval
        if let latestLocation {
            age = fixAge(for: latestLocation)
        } else if let anchorArmedAt {
            age = Date().timeIntervalSince(anchorArmedAt)
        } else {
            age = 0
        }
        if age >= Self.lostFixAlarmSeconds {
            guard !lostFixEventIssued else { return }
            lostFixEventIssued = true
            triggerAnchorAlarm(
                reason: String(format: "GPS position has been unavailable for %.0f seconds.", age),
                kind: "gps_unavailable"
            )
        } else {
            lostFixEventIssued = false
        }
    }

    private func evaluateBatteryHealth() {
        guard anchorAlarmArmed else { return }
        let device = UIDevice.current
        guard device.batteryLevel >= 0 else { return }
        switch device.batteryState {
        case .charging, .full:
            return
        default:
            break
        }
        let percent = Int((device.batteryLevel * 100).rounded())
        if percent <= 10, !criticalBatteryEventIssued {
            criticalBatteryEventIssued = true
            triggerAnchorAlarm(
                reason: "The authoritative boat phone battery is critical at \(percent)% and is not charging.",
                kind: "battery_critical"
            )
        } else if percent <= 20, !lowBatteryEventIssued {
            lowBatteryEventIssued = true
            triggerAnchorAlarm(
                reason: "The authoritative boat phone battery is low at \(percent)% and is not charging.",
                kind: "battery_low"
            )
        }
    }

    private func triggerAnchorAlarm(reason: String, kind: String = "anchor") {
        guard anchorAlarmArmed else { return }
        _ = anchorEventLog.append(
            sessionID: anchorHistoryStore.sessionID,
            kind: kind,
            reason: reason
        )
        anchorAlarmTriggered = true
        anchorAlarmSilenced = false
        anchorAlertReason = reason
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        startAlarmAudio()
        scheduleAlarmNotifications(reason: reason)
        publishAnchorSyncState(immediately: true)
    }

    private func appendAnchorTrackPoint(
        _ location: CLLocation,
        relativeTo reference: CLLocationCoordinate2D
    ) {
        let offset = AnchorAlarmGeometry.plotOffsetMeters(
            referenceLatitude: reference.latitude,
            referenceLongitude: reference.longitude,
            pointLatitude: location.coordinate.latitude,
            pointLongitude: location.coordinate.longitude
        )
        guard anchorHistoryStore.append(
            eastMeters: offset.eastMeters,
            northMeters: offset.northMeters,
            accuracyMeters: location.horizontalAccuracy,
            recordedAt: location.timestamp
        ) != nil else { return }
        anchorTrackPoints = anchorHistoryStore.renderedPoints
    }

    private func clearAnchorTrack() {
        anchorTrackPoints.removeAll(keepingCapacity: true)
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = isStreaming
            || anchorAlarmArmed
            || isLayingAnchor
    }

    private func startAlarmAudio() {
        guard alarmAudioPlayer == nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(data: Self.makeAlarmSirenData())
            player.numberOfLoops = -1
            player.volume = 1
            player.prepareToPlay()
            player.play()
            alarmAudioPlayer = player
        } catch {
            lastError = "The in-app alarm siren could not start: \(error.localizedDescription)"
        }
    }

    private func stopAlarmAudio() {
        alarmAudioPlayer?.stop()
        alarmAudioPlayer = nil
        try? AVAudioSession.sharedInstance().setActive(
            false,
            options: [.notifyOthersOnDeactivation]
        )
    }

    private static func makeAlarmSirenData() -> Data {
        let sampleRate = 22_050
        let durationSeconds = 1.6
        let sampleCount = Int(Double(sampleRate) * durationSeconds)
        var samples = Data(capacity: sampleCount * 2)
        for index in 0..<sampleCount {
            let time = Double(index) / Double(sampleRate)
            let segmentDuration = 0.4
            let segment = Int(time / segmentDuration)
            let segmentTime = time.truncatingRemainder(dividingBy: segmentDuration)
            let frequency = segment.isMultiple(of: 2) ? 880.0 : 660.0
            let attack = min(1, segmentTime / 0.015)
            let release = min(1, (segmentDuration - segmentTime) / 0.015)
            let envelope = min(attack, release)
            let fundamental = sin(2 * .pi * frequency * time)
            let harmonic = 0.25 * sin(2 * .pi * frequency * 2 * time)
            let value = max(-1, min(1, (fundamental + harmonic) * envelope * 0.82))
            var sample = Int16(value * Double(Int16.max)).littleEndian
            withUnsafeBytes(of: &sample) { samples.append(contentsOf: $0) }
        }

        var wav = Data()
        func appendASCII(_ value: String) {
            wav.append(contentsOf: value.utf8)
        }
        func appendUInt16(_ value: UInt16) {
            var littleEndian = value.littleEndian
            withUnsafeBytes(of: &littleEndian) { wav.append(contentsOf: $0) }
        }
        func appendUInt32(_ value: UInt32) {
            var littleEndian = value.littleEndian
            withUnsafeBytes(of: &littleEndian) { wav.append(contentsOf: $0) }
        }

        appendASCII("RIFF")
        appendUInt32(UInt32(36 + samples.count))
        appendASCII("WAVEfmt ")
        appendUInt32(16)
        appendUInt16(1)
        appendUInt16(1)
        appendUInt32(UInt32(sampleRate))
        appendUInt32(UInt32(sampleRate * 2))
        appendUInt16(2)
        appendUInt16(16)
        appendASCII("data")
        appendUInt32(UInt32(samples.count))
        wav.append(samples)
        return wav
    }

    private func registerAlarmNotificationCategory() {
        let silence = UNNotificationAction(
            identifier: Self.silenceActionIdentifier,
            title: "Silence",
            options: []
        )
        let disarm = UNNotificationAction(
            identifier: Self.disarmActionIdentifier,
            title: "Disarm",
            options: [.destructive]
        )
        let category = UNNotificationCategory(
            identifier: Self.alarmCategoryIdentifier,
            actions: [silence, disarm],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        notificationCenter.setNotificationCategories([category])
    }

    private func requestAlarmNotificationAuthorization() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let granted = try await notificationCenter.requestAuthorization(
                    options: [.alert, .sound, .badge]
                )
                if !granted {
                    lastError = "Enable notifications and sound in Settings so the anchor alarm can wake you."
                }
            } catch {
                lastError = "Anchor alarm notifications could not be enabled: \(error.localizedDescription)"
            }
        }
    }

    private func scheduleAlarmNotifications(reason: String) {
        cancelAlarmNotifications()

        let immediateContent = alarmNotificationContent(reason: reason)
        let immediate = UNNotificationRequest(
            identifier: Self.alarmNotificationPrefix + "immediate",
            content: immediateContent,
            trigger: nil
        )
        notificationCenter.add(immediate)

        let repeatingContent = alarmNotificationContent(reason: reason)
        let repeating = UNNotificationRequest(
            identifier: Self.alarmNotificationPrefix + "repeating",
            content: repeatingContent,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 60, repeats: true)
        )
        notificationCenter.add(repeating)
    }

    private func alarmNotificationContent(reason: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = "⚠ ANCHOR ALARM"
        content.body = reason
        content.sound = .default
        content.categoryIdentifier = Self.alarmCategoryIdentifier
        content.interruptionLevel = .timeSensitive
        return content
    }

    private func cancelAlarmNotifications() {
        let identifiers = [
            Self.alarmNotificationPrefix + "immediate",
            Self.alarmNotificationPrefix + "repeating"
        ]
        notificationCenter.removePendingNotificationRequests(withIdentifiers: identifiers)
        notificationCenter.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    private func loadAnchorConfiguration() {
        if let rawMode = defaults.string(forKey: DefaultsKey.mode),
           let storedMode = AnchorAlarmMode(rawValue: rawMode) {
            anchorMode = storedMode
        }
        let storedRadius = defaults.double(forKey: DefaultsKey.radius)
        anchorRadiusMeters = storedRadius > 0
            ? anchorMode.adjustedRadius(storedRadius)
            : anchorMode.defaultRadiusMeters

        if defaults.bool(forKey: DefaultsKey.hasReference) {
            let latitude = defaults.double(forKey: DefaultsKey.referenceLatitude)
            let longitude = defaults.double(forKey: DefaultsKey.referenceLongitude)
            if (-90...90).contains(latitude), (-180...180).contains(longitude) {
                anchorReferenceCoordinate = CLLocationCoordinate2D(
                    latitude: latitude,
                    longitude: longitude
                )
            }
        }
    }

    private var hasCurrentAnchorEntitlement: Bool {
        entitlementDecision.featureLevel.rawValue >= FeatureLevel.anchorWatch.rawValue
    }

    private var activeEntitledSessionID: String? {
        anchorAlarmArmed ? anchorHistoryStore.sessionID : nil
    }

    private func restoreSessionEntitlementLease() -> Bool {
        var sessionID = anchorHistoryStore.sessionID
        if let storedLease = sessionLeaseStore.load(),
           let sessionID,
           storedLease.authorizes(.anchorWatch, for: sessionID) {
            sessionEntitlementLease = storedLease
            return true
        }

        guard !defaults.bool(forKey: DefaultsKey.sessionLeaseMigrationCompleted) else {
            sessionLeaseStore.clear()
            return false
        }
        if sessionID == nil {
            sessionID = anchorHistoryStore.startNewSession()
        }
        let legacyDecision = EntitlementDecision(featureLevel: .remoteAnchorWatch)
        guard let sessionID,
              let migrationLease = EntitlementSessionAccess.leaseForStartingSession(
                sessionID: sessionID,
                decision: legacyDecision,
                at: Date()
              ),
              sessionLeaseStore.save(migrationLease) else {
            return false
        }
        sessionEntitlementLease = migrationLease
        return true
    }

    private func resetPersistedAnchorAfterMissingLease() {
        sessionEntitlementLease = nil
        sessionLeaseStore.clear()
        anchorReferenceCoordinate = nil
        defaults.set(false, forKey: DefaultsKey.armed)
        defaults.set(false, forKey: DefaultsKey.hasReference)
        lastError = "The previous anchor watch could not be resumed safely. Arm a new watch."
    }

    private func reconcileAnchorSyncServer() {
        guard canUseRemoteAnchorWatch else {
            anchorSyncServer.stop()
            shoreMonitorConnected = false
            shoreMonitorSoundReady = false
            anchorPairingURL = nil
            return
        }
        do {
            let configuration: AnchorPairingConfiguration
            if let pairingConfiguration {
                configuration = pairingConfiguration
            } else {
                configuration = try pairingStore.loadOrCreate()
                pairingConfiguration = configuration
            }
            anchorSyncServer.start(pairingKey: configuration.pairingKey)
            updateAnchorPairingURL()
        } catch {
            lastError = "Secure shore-monitor pairing could not be initialized: \(error.localizedDescription)"
            anchorPairingURL = nil
        }
    }

    private func persistAnchorConfiguration(armed: Bool? = nil) {
        defaults.set(anchorMode.rawValue, forKey: DefaultsKey.mode)
        defaults.set(anchorRadiusMeters, forKey: DefaultsKey.radius)
        if let coordinate = anchorReferenceCoordinate {
            defaults.set(true, forKey: DefaultsKey.hasReference)
            defaults.set(coordinate.latitude, forKey: DefaultsKey.referenceLatitude)
            defaults.set(coordinate.longitude, forKey: DefaultsKey.referenceLongitude)
        } else {
            defaults.set(false, forKey: DefaultsKey.hasReference)
        }
        if let armed {
            defaults.set(armed, forKey: DefaultsKey.armed)
        }
    }

    private func updateAnchorPairingURL() {
        guard canUseRemoteAnchorWatch, let pairingConfiguration else {
            anchorPairingURL = nil
            return
        }
        anchorPairingURL = pairingStore.pairingURL(
            configuration: pairingConfiguration,
            addresses: AnchorPairingAddressProvider.currentIPv4Addresses()
        )
    }

    private func publishAnchorSyncState(immediately: Bool = false) {
        let device = UIDevice.current
        let batteryPercent = device.batteryLevel >= 0
            ? Int((device.batteryLevel * 100).rounded())
            : -1
        let charging = device.batteryState == .charging || device.batteryState == .full
        let accuracy: Double?
        if let latestLocation, latestLocation.horizontalAccuracy >= 0 {
            accuracy = latestLocation.horizontalAccuracy
        } else {
            accuracy = nil
        }
        let sessionID = anchorHistoryStore.sessionID
        let state = AnchorSyncState(
            sessionID: sessionID,
            armed: anchorAlarmArmed,
            mode: anchorMode.rawValue,
            radiusMeters: anchorRadiusMeters,
            distanceMeters: anchorDistanceMeters,
            accuracyMeters: accuracy,
            latestSequence: anchorHistoryStore.points.last?.sequence ?? 0,
            batteryPercent: batteryPercent,
            charging: charging,
            serverTimeMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000),
            events: anchorEventLog.events.filter { $0.sessionID == sessionID }
        )
        anchorSyncServer.publish(
            state: state,
            history: anchorHistoryStore.points,
            sendImmediately: immediately
        )
    }

    private func fixAge(for location: CLLocation) -> TimeInterval {
        max(0, clock.timeIntervalSince(location.timestamp))
    }

    private func handleServerState(_ state: NMEATCPServer.ListenerState) {
        switch state {
        case .stopped:
            listenerDescription = "Stopped"
        case .starting:
            listenerDescription = "Starting TCP server…"
        case .ready(let port):
            listenerDescription = "Listening on TCP port \(port)"
        case .failed(let message):
            listenerDescription = "TCP server failed"
            lastError = message
        }
    }
}
