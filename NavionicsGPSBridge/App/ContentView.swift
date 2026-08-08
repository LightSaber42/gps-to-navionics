import SwiftUI

struct ContentView: View {
    @StateObject private var controller = BridgeController()
    @State private var showingAnchorPairing = false
    @State private var showingDegradedArmWarning = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if controller.anchorAlarmArmed {
                        armedAnchorDashboard
                    } else {
                        statusCard
                        anchorAlarmCard
                        anchorPairingCard
                        gpsCard
                        networkCard
                        instructionsCard
                        diagnosticsCard
                    }
                    safetyNote
                }
                .padding()
            }
            .navigationTitle(controller.anchorAlarmArmed ? "Anchor watch" : "GPS → Navionics")
            .background(Color(uiColor: .systemGroupedBackground))
            .sheet(isPresented: $showingAnchorPairing) {
                AnchorPairingView(
                    pairingURL: controller.anchorPairingURL,
                    monitorConnected: controller.shoreMonitorConnected,
                    monitorSoundReady: controller.shoreMonitorSoundReady,
                    rotatePairing: controller.rotateAnchorPairing
                )
            }
            .alert("Shore alarm is not ready", isPresented: $showingDegradedArmWarning) {
                Button("Cancel", role: .cancel) {}
                Button("Arm anyway", role: .destructive) {
                    controller.toggleAnchorAlarm()
                }
            } message: {
                Text("You can arm in degraded mode, but the shore phone may not receive or sound alarms. Pair it and complete its alarm setup when possible.")
            }
        }
    }

    private var armedAnchorDashboard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                Circle()
                    .fill(anchorStatusColor)
                    .frame(width: 14, height: 14)
                Text(controller.anchorStatusTitle)
                    .font(.title3.bold())
                Spacer()
                Text(controller.anchorMode.displayName)
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
            }

            VStack(spacing: 2) {
                Text(controller.anchorDistanceMeters.map {
                    String(format: "%.1f m", $0)
                } ?? "—")
                    .font(.system(size: 42, weight: .bold, design: .rounded))
                    .foregroundStyle(controller.anchorAlarmTriggered ? .red : .primary)
                    .contentTransition(.numericText())
                Text(String(format: "distance from reference • %.0f m limit", controller.anchorRadiusMeters))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)

            if controller.anchorMode == .freeSwinging {
                armedSwingRadiusControls
            }

            AnchorDriftPlot(
                points: controller.anchorTrackPoints,
                limitMeters: controller.anchorRadiusMeters,
                alarmTriggered: controller.anchorAlarmTriggered
            )
            .frame(height: 360)

            HStack(spacing: 16) {
                plotLegend(color: .blue, label: "GPS trail")
                plotLegend(color: .green, label: "Boat now")
                plotLegend(color: .orange, label: "Alarm limit")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)

            HStack {
                Label("Reference", systemImage: "anchor.fill")
                Spacer()
                Text("\(controller.anchorTrackPoints.count) plotted fixes")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Label(
                controller.shoreMonitorConnected
                    ? (controller.shoreMonitorSoundReady
                        ? "Shore monitor connected and sound-ready"
                        : "Shore monitor connected but sound is not ready")
                    : "Shore monitor disconnected",
                systemImage: controller.shoreMonitorConnected
                    ? "antenna.radiowaves.left.and.right"
                    : "wifi.slash"
            )
            .font(.caption.bold())
            .foregroundStyle(
                controller.shoreMonitorConnected && controller.shoreMonitorSoundReady
                    ? Color.green : Color.orange
            )

            Button {
                controller.refreshAnchorPairing()
                showingAnchorPairing = true
            } label: {
                Label("Show shore pairing", systemImage: "qrcode")
            }
            .font(.subheadline)

            if let message = controller.anchorAlertReason {
                Text(message)
                    .font(.footnote.bold())
                    .foregroundStyle(controller.anchorAlarmTriggered ? .red : .orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if controller.anchorAlarmTriggered {
                Button(action: controller.silenceAnchorAlarm) {
                    Label("Silence alarm", systemImage: "speaker.slash.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                .controlSize(.large)
            }

            Button(action: controller.disarmAnchorAlarm) {
                Label("Disarm anchor alarm", systemImage: "stop.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .controlSize(.large)

            Text("The plot is north-up and automatically zooms to keep the complete recent trail and alarm boundary visible.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .cardStyle()
    }

    private var armedSwingRadiusControls: some View {
        VStack(spacing: 6) {
            HStack(spacing: 12) {
                Text("Swing radius")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    controller.setAnchorRadius(
                        controller.anchorRadiusMeters
                            - controller.anchorMode.radiusStepMeters
                    )
                } label: {
                    Image(systemName: "minus")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.bordered)
                .disabled(
                    controller.anchorRadiusMeters
                        <= controller.anchorMode.minimumRadiusMeters
                )
                .accessibilityLabel("Decrease swing radius")

                Text(String(format: "%.0f m", controller.anchorRadiusMeters))
                    .font(.headline.monospacedDigit())
                    .frame(minWidth: 56)
                    .contentTransition(.numericText())

                Button {
                    controller.setAnchorRadius(
                        controller.anchorRadiusMeters
                            + controller.anchorMode.radiusStepMeters
                    )
                } label: {
                    Image(systemName: "plus")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.bordered)
                .disabled(
                    controller.anchorRadiusMeters
                        >= controller.anchorMode.maximumRadiusMeters
                )
                .accessibilityLabel("Increase swing radius")
            }

            Text(String(
                format: "Adjustable while anchored • %.0f m steps",
                controller.anchorMode.radiusStepMeters
            ))
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var anchorAlarmCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Circle()
                    .fill(anchorStatusColor)
                    .frame(width: 12, height: 12)
                Text(controller.anchorStatusTitle)
                    .font(.headline)
                Spacer()
            }

            Text(controller.anchorMode.setupDescription)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Picker(
                "Anchor mode",
                selection: Binding(
                    get: { controller.anchorMode },
                    set: controller.setAnchorMode
                )
            ) {
                ForEach(AnchorAlarmMode.allCases) { mode in
                    Text(mode.displayName).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .disabled(controller.anchorAlarmArmed)

            Stepper(
                value: Binding(
                    get: { controller.anchorRadiusMeters },
                    set: controller.setAnchorRadius
                ),
                in: controller.anchorMode.minimumRadiusMeters...controller.anchorMode.maximumRadiusMeters,
                step: controller.anchorMode.radiusStepMeters
            ) {
                HStack {
                    Text("Alarm radius")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(String(format: "%.0f m", controller.anchorRadiusMeters))
                        .fontWeight(.semibold)
                }
                .font(.subheadline)
            }
            .disabled(controller.anchorAlarmArmed)

            Slider(
                value: Binding(
                    get: { controller.anchorRadiusMeters },
                    set: controller.setAnchorRadius
                ),
                in: controller.anchorMode.minimumRadiusMeters...controller.anchorMode.maximumRadiusMeters,
                step: controller.anchorMode.radiusStepMeters
            )
            .disabled(controller.anchorAlarmArmed)
            .accessibilityLabel("Anchor alarm radius")
            .accessibilityValue(String(format: "%.0f metres", controller.anchorRadiusMeters))

            Text(
                String(
                    format: "%.0f–%.0f m range • %.0f m steps",
                    controller.anchorMode.minimumRadiusMeters,
                    controller.anchorMode.maximumRadiusMeters,
                    controller.anchorMode.radiusStepMeters
                )
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            detailRow(
                "Reference position",
                controller.anchorReferenceDescription,
                monospaced: true
            )
            detailRow("Distance from reference", controller.anchorDistanceDescription)

            Button(action: handleAnchorPrimaryAction) {
                Label(
                    controller.anchorPrimaryButtonTitle,
                    systemImage: controller.anchorAlarmArmed ? "stop.fill" : "anchor"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(controller.anchorAlarmArmed ? .red : .blue)
            .controlSize(.large)

            if controller.anchorMode == .freeSwinging,
               !controller.anchorAlarmArmed,
               controller.anchorReferenceCoordinate != nil {
                Button("Update anchor position here", action: controller.markAnchorPosition)
                    .font(.subheadline)
            }

            if controller.anchorAlarmTriggered {
                Button(action: controller.silenceAnchorAlarm) {
                    Label("Silence alarm", systemImage: "speaker.slash.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                .controlSize(.large)
            }

            if let message = controller.anchorAlertReason ?? controller.anchorActionMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(controller.anchorAlarmTriggered ? .red : .orange)
            }

            Text("A drift must remain outside the radius for 5 seconds. GPS loss alerts after 30 seconds; while locked, the alarm notification repeats every minute until silenced.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .cardStyle()
    }

    private var anchorPairingCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Circle()
                    .fill(controller.shoreMonitorConnected ? Color.green : Color.blue)
                    .frame(width: 12, height: 12)
                Text("Shore monitor")
                    .font(.headline)
                Spacer()
                Text(controller.shoreMonitorConnected ? "Connected" : "Not connected")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
            }

            Text("Pair one Android phone to receive heartbeat, alarm events, and plot history over Wi‑Fi or Tailscale.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Button {
                controller.refreshAnchorPairing()
                showingAnchorPairing = true
            } label: {
                Label("Pair Android shore monitor", systemImage: "qrcode")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .cardStyle()
    }

    private func handleAnchorPrimaryAction() {
        let actionWillArm = !controller.anchorAlarmArmed
            && (controller.anchorMode == .shorelined
                || controller.anchorReferenceCoordinate != nil)
        if actionWillArm
            && (!controller.shoreMonitorConnected || !controller.shoreMonitorSoundReady) {
            showingDegradedArmWarning = true
        } else {
            controller.toggleAnchorAlarm()
        }
    }

    private var statusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 12, height: 12)
                Text(controller.statusTitle)
                    .font(.headline)
                Spacer()
            }

            Button(action: controller.toggleStreaming) {
                Label(
                    controller.isStreaming ? "Stop Streaming" : "Start Streaming",
                    systemImage: controller.isStreaming ? "stop.fill" : "location.fill"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(controller.isStreaming ? .red : .blue)
            .controlSize(.large)

            if let error = controller.lastError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if controller.authorizationStatus == .denied || controller.authorizationStatus == .restricted {
                    Button("Open Location Settings", action: controller.openSettings)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .cardStyle()
    }

    private var gpsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("GPS fix", systemImage: "location.circle")
                .font(.headline)
            detailRow("Permission", controller.authorizationDescription)
            detailRow("Position", controller.coordinateDescription, monospaced: true)
            detailRow("Accuracy", controller.accuracyDescription)
            detailRow("Fix age", controller.fixAgeDescription)
            detailRow("Speed", controller.speedDescription)
            detailRow("Course over ground", controller.courseDescription)
        }
        .cardStyle()
    }

    private var networkCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Navionics connection", systemImage: "network")
                .font(.headline)
            detailRow("Protocol", "TCP")
            detailRow("Port", String(controller.port), monospaced: true)
            detailRow("Clients", String(controller.connectedClientCount))
            detailRow("Server", controller.listenerDescription)

            Divider()

            Text("Enter one of these addresses in Navionics:")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if controller.networkAddresses.isEmpty {
                Text("No Wi‑Fi or hotspot address found")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            } else {
                ForEach(controller.networkAddresses) { item in
                    HStack {
                        Text(item.interfaceName)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(item.address)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
            }

            Button("Refresh Addresses", action: controller.refreshNetworkAddresses)
                .font(.subheadline)
        }
        .cardStyle()
    }

    private var instructionsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Connect in Navionics", systemImage: "list.number")
                .font(.headline)
            instruction(1, "Put the iPhone and iPad on the same Wi‑Fi network.")
            instruction(2, "Tap Start Streaming and allow Precise Location and Local Network access.")
            instruction(3, "In Navionics: Menu → Connect a Device → Add Device.")
            instruction(4, "Choose TCP, then enter the address above and port \(controller.port).")
            instruction(5, "Keep the iPhone powered during navigation.")
        }
        .cardStyle()
    }

    private var diagnosticsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Last NMEA transmission", systemImage: "waveform.path.ecg")
                .font(.headline)

            if controller.lastSentences.isEmpty {
                Text("Sentences appear here after the first GPS fix.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal) {
                    Text(controller.lastSentences)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
        }
        .cardStyle()
    }

    private var safetyNote: some View {
        Label {
            Text("Test the alarm, notification sound, and volume before relying on it. GPS, battery, and iOS settings can fail; keep a physical anchor watch and an independent navigation backup.")
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 4)
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(String(number))
                .font(.caption.bold())
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(.blue))
            Text(text)
                .font(.subheadline)
            Spacer(minLength: 0)
        }
    }

    private func detailRow(_ label: String, _ value: String, monospaced: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(monospaced ? .system(.body, design: .monospaced) : .body)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
        .font(.subheadline)
    }

    private func plotLegend(color: Color, label: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label)
        }
    }

    private var statusColor: Color {
        switch controller.statusColorName {
        case "red": return .red
        case "orange": return .orange
        case "green": return .green
        case "blue": return .blue
        default: return .secondary
        }
    }

    private var anchorStatusColor: Color {
        switch controller.anchorStatusColorName {
        case "red": return .red
        case "orange": return .orange
        case "green": return .green
        case "blue": return .blue
        default: return .secondary
        }
    }
}

private struct AnchorDriftPlot: View {
    let points: [AnchorTrackPoint]
    let limitMeters: Double
    let alarmTriggered: Bool

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let padding = 30.0
            let drawableRadius = max(1, min(size.width, size.height) / 2 - padding)
            let furthestPoint = points.map {
                hypot($0.eastMeters, $0.northMeters) + $0.accuracyMeters
            }.max() ?? 0
            let extentMeters = max(5, limitMeters * 1.25, furthestPoint * 1.15)
            let pixelsPerMeter = drawableRadius / extentMeters

            func plotPoint(east: Double, north: Double) -> CGPoint {
                CGPoint(
                    x: center.x + east * pixelsPerMeter,
                    y: center.y - north * pixelsPerMeter
                )
            }

            var axes = Path()
            axes.move(to: CGPoint(x: padding / 2, y: center.y))
            axes.addLine(to: CGPoint(x: size.width - padding / 2, y: center.y))
            axes.move(to: CGPoint(x: center.x, y: padding / 2))
            axes.addLine(to: CGPoint(x: center.x, y: size.height - padding / 2))
            context.stroke(axes, with: .color(.secondary.opacity(0.18)), lineWidth: 1)

            let limitRadius = limitMeters * pixelsPerMeter
            let limitRect = CGRect(
                x: center.x - limitRadius,
                y: center.y - limitRadius,
                width: limitRadius * 2,
                height: limitRadius * 2
            )
            context.fill(Path(ellipseIn: limitRect), with: .color(.blue.opacity(0.07)))
            context.stroke(
                Path(ellipseIn: limitRect),
                with: .color(alarmTriggered ? .red : .orange),
                style: StrokeStyle(lineWidth: 3, dash: [9, 6])
            )

            if points.count > 1 {
                var trail = Path()
                for (index, point) in points.enumerated() {
                    let location = plotPoint(east: point.eastMeters, north: point.northMeters)
                    index == 0 ? trail.move(to: location) : trail.addLine(to: location)
                }
                context.stroke(
                    trail,
                    with: .color(.blue.opacity(0.72)),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                )
            }

            let pointStride = max(1, points.count / 160)
            for (index, point) in points.enumerated() where index.isMultiple(of: pointStride) {
                let location = plotPoint(east: point.eastMeters, north: point.northMeters)
                let rect = CGRect(x: location.x - 1.5, y: location.y - 1.5, width: 3, height: 3)
                context.fill(Path(ellipseIn: rect), with: .color(.blue.opacity(0.55)))
            }

            if let current = points.last {
                let currentLocation = plotPoint(
                    east: current.eastMeters,
                    north: current.northMeters
                )
                let accuracyRadius = current.accuracyMeters * pixelsPerMeter
                if accuracyRadius > 1 {
                    let accuracyRect = CGRect(
                        x: currentLocation.x - accuracyRadius,
                        y: currentLocation.y - accuracyRadius,
                        width: accuracyRadius * 2,
                        height: accuracyRadius * 2
                    )
                    context.fill(
                        Path(ellipseIn: accuracyRect),
                        with: .color(.green.opacity(0.09))
                    )
                    context.stroke(
                        Path(ellipseIn: accuracyRect),
                        with: .color(.green.opacity(0.3)),
                        lineWidth: 1
                    )
                }
                let boatRect = CGRect(
                    x: currentLocation.x - 7,
                    y: currentLocation.y - 7,
                    width: 14,
                    height: 14
                )
                context.fill(
                    Path(ellipseIn: boatRect),
                    with: .color(alarmTriggered ? .red : .green)
                )
                context.stroke(Path(ellipseIn: boatRect), with: .color(.white), lineWidth: 2)
            }

            let referenceRect = CGRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10)
            context.fill(Path(ellipseIn: referenceRect), with: .color(.primary))
            var referenceCross = Path()
            referenceCross.move(to: CGPoint(x: center.x - 10, y: center.y))
            referenceCross.addLine(to: CGPoint(x: center.x + 10, y: center.y))
            referenceCross.move(to: CGPoint(x: center.x, y: center.y - 10))
            referenceCross.addLine(to: CGPoint(x: center.x, y: center.y + 10))
            context.stroke(referenceCross, with: .color(.primary), lineWidth: 2)

            context.draw(
                Text("N").font(.caption.bold()).foregroundColor(.secondary),
                at: CGPoint(x: center.x, y: 12)
            )
            context.draw(
                Text(String(format: "%.0f m limit", limitMeters))
                    .font(.caption2.bold())
                    .foregroundColor(alarmTriggered ? .red : .orange),
                at: CGPoint(x: center.x, y: center.y - limitRadius),
                anchor: .bottom
            )
        }
        .background(Color(uiColor: .tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Anchor drift plot")
        .accessibilityValue(accessibilityValue)
    }

    private var accessibilityValue: String {
        guard let current = points.last else {
            return String(format: "Waiting for GPS points. Alarm limit %.0f metres.", limitMeters)
        }
        return String(
            format: "Current drift %.1f metres. Alarm limit %.0f metres. %d GPS points plotted.",
            hypot(current.eastMeters, current.northMeters),
            limitMeters,
            points.count
        )
    }
}

private extension View {
    func cardStyle() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
