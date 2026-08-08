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
    @Published private(set) var anchorAlarmArmed = false
    @Published private(set) var anchorAlarmTriggered = false
    @Published private(set) var anchorAlarmSilenced = false
    @Published private(set) var anchorReferenceCoordinate: CLLocationCoordinate2D?
    @Published private(set) var anchorDistanceMeters: Double?
    @Published private(set) var anchorTrackPoints: [AnchorTrackPoint] = []
    @Published private(set) var anchorAlertReason: String?
    @Published private(set) var anchorActionMessage: String?
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
    }

    private static let alarmCategoryIdentifier = "ANCHOR_ALARM"
    private static let silenceActionIdentifier = "SILENCE_ANCHOR_ALARM"
    private static let disarmActionIdentifier = "DISARM_ANCHOR_ALARM"
    private static let alarmNotificationPrefix = "anchor-alarm."
    private static let staleFixSeconds: TimeInterval = 10
    private static let lostFixAlarmSeconds: TimeInterval = 30
    private static let breachConfirmationSeconds: TimeInterval = 5
    private static let maximumAnchorTrackPoints = 1_200

    private let locationManager = CLLocationManager()
    private let server = NMEATCPServer()
    private let notificationCenter = UNUserNotificationCenter.current()
    private let defaults = UserDefaults.standard
    private var monitorTimer: Timer?
    private var locationUpdatesActive = false
    private var pendingAnchorAction: PendingAnchorAction?
    private var anchorArmedAt: Date?
    private var breachStartedAt: Date?
    private var nextAnchorTrackPointID = 0
    private var alarmAudioPlayer: AVAudioPlayer?

    override init() {
        authorizationStatus = locationManager.authorizationStatus
        super.init()

        loadAnchorConfiguration()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.activityType = .otherNavigation
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true

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

        if defaults.bool(forKey: DefaultsKey.armed), anchorReferenceCoordinate != nil {
            anchorAlarmArmed = true
            anchorArmedAt = Date()
            updateLocationMonitoring()
            updateMonitorTimer()
        }
        refreshNetworkAddresses()
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
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            return "Record the anchor position"
        }
        return "Anchor alarm ready"
    }

    var anchorStatusColorName: String {
        if anchorAlarmTriggered { return "red" }
        if anchorAlarmSilenced { return "orange" }
        if anchorAlarmArmed { return "green" }
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil { return "orange" }
        return "blue"
    }

    var anchorPrimaryButtonTitle: String {
        if anchorAlarmArmed { return "Disarm anchor alarm" }
        if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            return "Drop anchor here"
        }
        return "Arm anchor alarm"
    }

    var anchorReferenceDescription: String {
        guard let coordinate = anchorReferenceCoordinate else { return "Not set" }
        return String(format: "%.6f, %.6f", coordinate.latitude, coordinate.longitude)
    }

    var anchorDistanceDescription: String {
        guard let anchorDistanceMeters else { return "—" }
        return String(
            format: "%.1f m / %.0f m limit",
            anchorDistanceMeters,
            anchorRadiusMeters
        )
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
        return String(format: "±%.0f m", latestLocation.horizontalAccuracy)
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
        UIApplication.shared.isIdleTimerDisabled = true

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
        updateMonitorTimer()
        updateLocationMonitoring()
    }

    func setAnchorMode(_ mode: AnchorAlarmMode) {
        guard !anchorAlarmArmed, anchorMode != mode else { return }
        anchorMode = mode
        anchorRadiusMeters = mode.defaultRadiusMeters
        anchorReferenceCoordinate = nil
        anchorDistanceMeters = nil
        clearAnchorTrack()
        anchorActionMessage = nil
        persistAnchorConfiguration()
    }

    func setAnchorRadius(_ radiusMeters: Double) {
        guard !anchorAlarmArmed || anchorMode == .freeSwinging else { return }
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
        } else if anchorMode == .freeSwinging, anchorReferenceCoordinate == nil {
            markAnchorPosition()
        } else {
            prepareForAnchorAction(.arm)
        }
    }

    func markAnchorPosition() {
        guard !anchorAlarmArmed else { return }
        anchorActionMessage = nil
        prepareForAnchorAction(.mark)
    }

    func silenceAnchorAlarm() {
        guard anchorAlarmTriggered else { return }
        anchorAlarmTriggered = false
        anchorAlarmSilenced = true
        anchorAlertReason = "Alarm silenced — monitoring continues until the boat is back inside the limit."
        breachStartedAt = nil
        stopAlarmAudio()
        cancelAlarmNotifications()
    }

    func disarmAnchorAlarm() {
        anchorAlarmArmed = false
        anchorAlarmTriggered = false
        anchorAlarmSilenced = false
        anchorAlertReason = nil
        anchorDistanceMeters = nil
        clearAnchorTrack()
        anchorArmedAt = nil
        breachStartedAt = nil
        stopAlarmAudio()
        defaults.set(false, forKey: DefaultsKey.armed)
        cancelAlarmNotifications()
        updateMonitorTimer()
        updateLocationMonitoring()
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    func refreshNetworkAddresses() {
        networkAddresses = NetworkAddressProvider.currentIPv4Addresses()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus

        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            lastError = nil
            updateLocationMonitoring()
            tryCompletePendingAnchorAction()
        case .denied, .restricted:
            locationUpdatesActive = false
            manager.stopUpdatingLocation()
            lastError = "Enable Precise Location in Settings for GPS streaming and the anchor alarm."
            pendingAnchorAction = nil
            if anchorAlarmArmed {
                triggerAnchorAlarm(reason: "Location permission is unavailable, so the anchor position cannot be monitored.")
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

    private func prepareForAnchorAction(_ action: PendingAnchorAction) {
        pendingAnchorAction = action
        switch authorizationStatus {
        case .notDetermined:
            anchorActionMessage = "Allow Precise Location, then wait for a fresh GPS fix."
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            updateLocationMonitoring()
            tryCompletePendingAnchorAction()
        case .denied, .restricted:
            pendingAnchorAction = nil
            anchorActionMessage = "Enable Precise Location in Settings before using the anchor alarm."
        @unknown default:
            pendingAnchorAction = nil
            anchorActionMessage = "The current location authorization state is unsupported."
        }
    }

    private func tryCompletePendingAnchorAction() {
        guard let action = pendingAnchorAction else { return }
        guard let latestLocation,
              latestLocation.horizontalAccuracy >= 0,
              fixAge(for: latestLocation) <= Self.staleFixSeconds else {
            anchorActionMessage = "Waiting for a fresh GPS fix…"
            return
        }

        pendingAnchorAction = nil
        switch action {
        case .mark:
            anchorReferenceCoordinate = latestLocation.coordinate
            anchorDistanceMeters = 0
            anchorActionMessage = "Anchor-drop position recorded. Arm after paying out the rode."
            persistAnchorConfiguration()
            updateLocationMonitoring()
        case .arm:
            if anchorMode == .shorelined {
                anchorReferenceCoordinate = latestLocation.coordinate
            }
            guard anchorReferenceCoordinate != nil else {
                anchorActionMessage = "Record the anchor-drop position before arming free-swinging mode."
                updateLocationMonitoring()
                return
            }
            anchorAlarmArmed = true
            anchorAlarmTriggered = false
            anchorAlarmSilenced = false
            anchorAlertReason = nil
            anchorActionMessage = nil
            anchorDistanceMeters = nil
            clearAnchorTrack()
            anchorArmedAt = Date()
            breachStartedAt = nil
            persistAnchorConfiguration(armed: true)
            requestAlarmNotificationAuthorization()
            updateLocationMonitoring()
            updateMonitorTimer()
            evaluateAnchorPosition(latestLocation)
            if authorizationStatus == .authorizedWhenInUse {
                locationManager.requestAlwaysAuthorization()
            }
        }
    }

    private func updateLocationMonitoring() {
        let authorized = authorizationStatus == .authorizedAlways
            || authorizationStatus == .authorizedWhenInUse
        let monitoringRequired = isStreaming || anchorAlarmArmed
        let locationRequired = monitoringRequired || pendingAnchorAction != nil

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
        guard anchorAlarmArmed, let reference = anchorReferenceCoordinate else { return }
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

        guard assessment.isDefinitelyOutside else {
            breachStartedAt = nil
            if anchorAlarmSilenced {
                anchorAlarmSilenced = false
                anchorAlertReason = nil
            }
            return
        }
        guard !anchorAlarmTriggered, !anchorAlarmSilenced else { return }

        let now = Date()
        if breachStartedAt == nil { breachStartedAt = now }
        guard let breachStartedAt,
              now.timeIntervalSince(breachStartedAt) >= Self.breachConfirmationSeconds else {
            return
        }

        let reason: String
        switch anchorMode {
        case .shorelined:
            reason = String(
                format: "Boat moved %.1f m from the shore-tied position (limit %.0f m).",
                assessment.distanceMeters,
                anchorRadiusMeters
            )
        case .freeSwinging:
            reason = String(
                format: "Boat moved %.1f m from the anchor (swing limit %.0f m).",
                assessment.distanceMeters,
                anchorRadiusMeters
            )
        }
        triggerAnchorAlarm(reason: reason)
    }

    private func evaluateAnchorHealth() {
        guard anchorAlarmArmed, !anchorAlarmTriggered, !anchorAlarmSilenced else { return }
        let age: TimeInterval
        if let latestLocation {
            age = fixAge(for: latestLocation)
        } else if let anchorArmedAt {
            age = Date().timeIntervalSince(anchorArmedAt)
        } else {
            age = 0
        }
        if age >= Self.lostFixAlarmSeconds {
            triggerAnchorAlarm(
                reason: String(format: "GPS position has been unavailable for %.0f seconds.", age)
            )
        }
    }

    private func triggerAnchorAlarm(reason: String) {
        guard anchorAlarmArmed, !anchorAlarmTriggered, !anchorAlarmSilenced else { return }
        anchorAlarmTriggered = true
        anchorAlertReason = reason
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        startAlarmAudio()
        scheduleAlarmNotifications(reason: reason)
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
        let point = AnchorTrackPoint(
            id: nextAnchorTrackPointID,
            eastMeters: offset.eastMeters,
            northMeters: offset.northMeters,
            accuracyMeters: max(0, location.horizontalAccuracy)
        )
        nextAnchorTrackPointID += 1
        anchorTrackPoints.append(point)
        if anchorTrackPoints.count > Self.maximumAnchorTrackPoints {
            anchorTrackPoints.removeFirst(anchorTrackPoints.count - Self.maximumAnchorTrackPoints)
        }
    }

    private func clearAnchorTrack() {
        anchorTrackPoints.removeAll(keepingCapacity: true)
        nextAnchorTrackPointID = 0
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
