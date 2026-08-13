import SwiftUI

struct ContentView: View {
    private enum DashboardSection: String, CaseIterable, Identifiable {
        case bridge = "Bridge"
        case anchor = "Anchor"

        var id: String { rawValue }
    }

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var controller = BridgeController()
    @StateObject private var purchaseStore = PurchaseStore()
    @State private var selectedSection = DashboardSection.bridge
    @State private var showingAnchorPairing = false
    @State private var showingDisarmConfirmation = false
    @State private var showingBridgeSetup = false
    @State private var showingBridgeDetails = false
    @State private var showingAnchorDetails = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    sectionSwitcher

                    if selectedSection == .bridge {
                        bridgeDashboard
                    } else {
                        anchorDashboard
                    }
                }
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                .padding()
            }
            .navigationTitle("GPS Bridge")
            .background(Color(uiColor: .systemGroupedBackground))
            .sheet(isPresented: $showingAnchorPairing) {
                AnchorPairingView(
                    pairingURL: controller.anchorPairingURL,
                    monitorConnected: controller.shoreMonitorConnected,
                    monitorSoundReady: controller.shoreMonitorSoundReady,
                    refreshPairing: controller.refreshAnchorPairing,
                    rotatePairing: controller.rotateAnchorPairing
                )
            }
            .alert("Disarm anchor watch?", isPresented: $showingDisarmConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Disarm", role: .destructive, action: controller.disarmAnchorAlarm)
            } message: {
                Text("The boat will no longer be monitored for drift or GPS loss.")
            }
            .onAppear {
                if controller.anchorAlarmArmed || controller.isLayingAnchor {
                    selectedSection = .anchor
                }
            }
            .task {
                controller.updateEntitlementDecision(purchaseStore.entitlementDecision)
                purchaseStore.start()
            }
            .onChange(of: purchaseStore.entitlementDecision) { _, decision in
                controller.updateEntitlementDecision(decision)
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                Task {
                    await purchaseStore.refresh()
                }
            }
            .onChange(of: controller.anchorAlarmTriggered) { _, triggered in
                if triggered {
                    selectedSection = .anchor
                }
            }
        }
    }

    private var sectionSwitcher: some View {
        HStack(spacing: 6) {
            sectionButton(.bridge, active: controller.isStreaming)
            sectionButton(
                .anchor,
                active: controller.anchorAlarmArmed || controller.isLayingAnchor
            )
        }
        .padding(4)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private func sectionButton(
        _ section: DashboardSection,
        active: Bool
    ) -> some View {
        Button {
            withAnimation(.snappy) {
                selectedSection = section
            }
        } label: {
            HStack(spacing: 8) {
                Text(section.rawValue)
                    .fontWeight(.semibold)
                if active {
                    Circle()
                        .fill(selectedSection == section ? Color.white : Color.green)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(selectedSection == section ? Color.white : Color.primary)
            .background(selectedSection == section ? Color.accentColor : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityValue(active ? "Active" : "Inactive")
    }

    private var bridgeDashboard: some View {
        VStack(spacing: 16) {
            bridgeStatusCard
            connectionCard
            bridgeSetupCard
            bridgeDetailsCard
            safetyNote
        }
    }

    private var bridgeStatusCard: some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 12, height: 12)
                    .accessibilityHidden(true)
                Text(controller.statusTitle)
                    .font(.title3.bold())
                Spacer()
            }

            HStack(spacing: 0) {
                metric("GPS", controller.accuracyDescription)
                Divider().frame(height: 34)
                metric("Navionics", controller.connectedClientCount == 1
                    ? "1 client" : "\(controller.connectedClientCount) clients")
                Divider().frame(height: 34)
                metric("Port", String(controller.port), monospaced: true)
            }

            Button(action: controller.toggleStreaming) {
                Label(
                    controller.isStreaming ? "Stop streaming" : "Start streaming",
                    systemImage: controller.isStreaming ? "stop.fill" : "location.fill"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(controller.isStreaming ? .red : .blue)
            .controlSize(.large)

            if let error = controller.lastError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if controller.authorizationStatus == .denied
                    || controller.authorizationStatus == .restricted {
                    Button("Open Location Settings", action: controller.openSettings)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .cardStyle()
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Navionics connection", systemImage: "network")
                .font(.headline)
            Text("Enter an address below and port " + String(controller.port) + " in Navionics.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            detailRow("Protocol", "TCP")
            detailRow("Port", String(controller.port), monospaced: true)

            if controller.networkAddresses.isEmpty {
                Label("No Wi‑Fi address found", systemImage: "wifi.slash")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            } else {
                ForEach(controller.networkAddresses) { item in
                    ViewThatFits(in: .horizontal) {
                        HStack {
                            Text(item.interfaceName)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(verbatim: item.address)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.interfaceName)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(verbatim: item.address)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            Button("Refresh addresses", action: controller.refreshNetworkAddresses)
                .font(.subheadline)
        }
        .cardStyle()
    }

    private var bridgeSetupCard: some View {
        DisclosureGroup(isExpanded: $showingBridgeSetup) {
            VStack(alignment: .leading, spacing: 10) {
                instruction(1, "Put the iPhone and iPad on the same Wi‑Fi network.")
                instruction(2, "Start streaming and allow Precise Location and Local Network access.")
                instruction(3, "In Navionics, open Menu → Connect a Device → Add Device.")
                instruction(4, "Choose TCP, then enter an address above and port \(controller.port).")
                instruction(5, "Keep the iPhone powered during navigation.")
            }
            .padding(.top, 12)
        } label: {
            Label("Setup in Navionics", systemImage: "list.number")
                .font(.headline)
        }
        .cardStyle()
    }

    private var bridgeDetailsCard: some View {
        DisclosureGroup(isExpanded: $showingBridgeDetails) {
            VStack(alignment: .leading, spacing: 11) {
                Divider()
                detailRow("Permission", controller.authorizationDescription)
                detailRow("Position", controller.coordinateDescription, monospaced: true)
                detailRow("Accuracy", controller.accuracyDescription)
                detailRow("Fix age", controller.fixAgeDescription)
                detailRow("Speed", controller.speedDescription)
                detailRow("Course over ground", controller.courseDescription)
                detailRow("Server", controller.listenerDescription)

                Divider()
                Text("Last NMEA transmission")
                    .font(.subheadline.bold())
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
            .padding(.top, 8)
        } label: {
            Label("GPS and diagnostics", systemImage: "waveform.path.ecg")
                .font(.headline)
        }
        .cardStyle()
    }

    private var anchorDashboard: some View {
        VStack(spacing: 16) {
            if controller.anchorAlarmArmed || controller.isLayingAnchor {
                armedAnchorDashboard
            } else {
                anchorSetupCard
            }
            anchorDetailsCard
            shoreMonitorCard
            if controller.anchorAlarmArmed {
                safetyNote
            }
        }
    }

    private var anchorSetupCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            statusHeader(controller.anchorStatusTitle, color: anchorStatusColor)

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

            radiusControl(title: "Alarm radius", adjustableWhileArmed: false)

            if controller.anchorMode == .freeSwinging {
                Divider()
                workflowStep(
                    1,
                    title: "Mark anchor position",
                    detail: controller.anchorReferenceCoordinate == nil
                        ? "Do this while the boat is over the anchor."
                        : "Position recorded.",
                    complete: controller.anchorReferenceCoordinate != nil
                )
                workflowStep(
                    2,
                    title: "Arm after paying out the rode",
                    detail: "The watch uses the marked point as its centre.",
                    complete: false
                )
            }

            Button(action: handleAnchorPrimaryAction) {
                Label(controller.anchorPrimaryButtonTitle, systemImage: "anchor")
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            if let message = controller.anchorActionMessage {
                Label(message, systemImage: "info.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .cardStyle()
    }

    private var armedAnchorDashboard: some View {
        VStack(alignment: .leading, spacing: 16) {
            AnchorDriftPlot(
                points: controller.anchorTrackPoints,
                limitMeters: controller.anchorRadiusMeters,
                alarmTriggered: controller.anchorAlarmTriggered
            )
            .aspectRatio(1, contentMode: .fit)
            .frame(maxHeight: 420)
            .frame(maxWidth: .infinity)

            if controller.isLayingAnchor {
                Button(action: controller.armAnchorAlarm) {
                    Label(
                        controller.anchorReferenceCoordinate == nil
                            ? "Acquiring anchor position…"
                            : "Arm anchor alarm",
                        systemImage: "bell.and.waves.left.and.right.fill"
                    )
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(controller.anchorReferenceCoordinate == nil)
            } else {
                Button {
                    showingDisarmConfirmation = true
                } label: {
                    Label("Disarm anchor watch", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(.large)
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

            radiusControl(
                title: controller.anchorMode == .freeSwinging
                    ? "Swing radius"
                    : "Alarm radius",
                adjustableWhileArmed: true
            )

            if let warning = controller.degradedAnchorGPSWarning {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Degraded GPS precision", systemImage: "location.slash.fill")
                        .font(.footnote.bold())
                    Text(warning)
                        .font(.footnote)
                }
                .foregroundStyle(.orange)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .overlay {
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(.orange.opacity(0.65), lineWidth: 1)
                }
                .accessibilityElement(children: .combine)
            }

            if controller.isLayingAnchor {
                Button(role: .cancel, action: controller.cancelAnchorSetup) {
                    Label("Cancel anchoring", systemImage: "xmark.circle")
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.bordered)
            }

            HStack(spacing: 10) {
                statusHeader(controller.anchorStatusTitle, color: anchorStatusColor)
                Spacer()
                Text(controller.anchorMode.displayName)
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
            }

            if controller.isLayingAnchor,
               let message = controller.anchorActionMessage {
                Label(message, systemImage: "info.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

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

            if let message = controller.anchorAlertReason {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote.bold())
                    .foregroundStyle(controller.anchorAlarmTriggered ? .red : .orange)
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

            Text(controller.isLayingAnchor
                ? "North-up • the trail begins at the anchor-drop point and updates while you pay out the rode. The alarm is not armed yet."
                : "North-up • focused on the alarm boundary, current position, and recent trail. Isolated older GPS outliers do not shrink the plot.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .cardStyle()
    }

    private func radiusControl(title: String, adjustableWhileArmed: Bool) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    controller.setAnchorRadius(
                        controller.anchorRadiusMeters - controller.anchorMode.radiusStepMeters
                    )
                } label: {
                    Image(systemName: "minus")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.bordered)
                .disabled(controller.anchorRadiusMeters <= controller.anchorMode.minimumRadiusMeters)
                .accessibilityLabel("Decrease \(title.lowercased())")

                Text(String(format: "%.0f m", controller.anchorRadiusMeters))
                    .font(.headline.monospacedDigit())
                    .frame(minWidth: 56)
                    .contentTransition(.numericText())

                Button {
                    controller.setAnchorRadius(
                        controller.anchorRadiusMeters + controller.anchorMode.radiusStepMeters
                    )
                } label: {
                    Image(systemName: "plus")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.bordered)
                .disabled(controller.anchorRadiusMeters >= controller.anchorMode.maximumRadiusMeters)
                .accessibilityLabel("Increase \(title.lowercased())")
            }

            Text(radiusHint(adjustableWhileArmed: adjustableWhileArmed))
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(12)
        .background(Color(uiColor: .tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func radiusHint(adjustableWhileArmed: Bool) -> String {
        if adjustableWhileArmed {
            return String(
                format: "Adjustable while anchored • %.0f m steps",
                controller.anchorMode.radiusStepMeters
            )
        }
        return String(
            format: "%.0f–%.0f m range • %.0f m steps",
            controller.anchorMode.minimumRadiusMeters,
            controller.anchorMode.maximumRadiusMeters,
            controller.anchorMode.radiusStepMeters
        )
    }

    private var shoreMonitorCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: shoreMonitorSymbol)
                    .foregroundStyle(shoreMonitorColor)
                    .accessibilityHidden(true)
                Text("Optional shore monitor")
                    .font(.headline)
                Spacer()
                Text(shoreMonitorStatus)
                    .font(.caption.bold())
                    .foregroundStyle(shoreMonitorColor)
                    .multilineTextAlignment(.trailing)
            }

            Text("Add an Android phone for backup remote and connection-loss alerts. Remote use requires Tailscale or another VPN on both phones.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Button {
                controller.refreshAnchorPairing()
                showingAnchorPairing = true
            } label: {
                Label(
                    controller.shoreMonitorConnected ? "View pairing code" : "Set up shore monitor",
                    systemImage: "qrcode"
                )
            }
            .font(.subheadline)
        }
        .cardStyle()
    }

    private var anchorDetailsCard: some View {
        DisclosureGroup(isExpanded: $showingAnchorDetails) {
            VStack(alignment: .leading, spacing: 11) {
                Divider()
                if controller.anchorMode == .freeSwinging {
                    detailRow(
                        "Reference position",
                        controller.anchorReferenceDescription,
                        monospaced: true
                    )
                    detailRow("Distance from reference", controller.anchorDistanceDescription)
                }
                Text("A drift must remain outside the radius for 5 seconds. GPS loss alerts after 30 seconds; while locked, the alarm notification repeats every minute until silenced.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text("After force-quitting or restarting this iPhone, reopen the app and re-arm the watch. Test alarm sound with your mute and Focus settings before relying on it.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 8)
        } label: {
            Label("Watch details", systemImage: "info.circle")
                .font(.headline)
        }
        .cardStyle()
    }

    private var safetyNote: some View {
        Label {
            Text("Test the alarm and phone volume before relying on it. Keep a physical anchor watch and an independent navigation backup.")
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 4)
    }

    private func statusHeader(_ title: String, color: Color) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(color)
                .frame(width: 12, height: 12)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
        }
        .accessibilityElement(children: .combine)
    }

    private func metric(_ label: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(monospaced ? .system(.subheadline, design: .monospaced) : .subheadline)
                .fontWeight(.semibold)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private func workflowStep(
        _ number: Int,
        title: String,
        detail: String,
        complete: Bool
    ) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: complete ? "checkmark.circle.fill" : "\(number).circle.fill")
                .font(.title3)
                .foregroundStyle(complete ? .green : .blue)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.bold())
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func handleAnchorPrimaryAction() {
        controller.toggleAnchorAlarm()
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
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline) {
                Text(label)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(value)
                    .font(monospaced ? .system(.body, design: .monospaced) : .body)
                    .multilineTextAlignment(.trailing)
                    .textSelection(.enabled)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(monospaced ? .system(.body, design: .monospaced) : .body)
                    .textSelection(.enabled)
            }
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

    private var shoreMonitorStatus: String {
        if !controller.shoreMonitorConnected { return "Not paired · Optional" }
        if controller.shoreMonitorSoundReady { return "Connected · Sound ready" }
        return controller.anchorAlarmArmed ? "Connected · Setup needed" : "Connected"
    }

    private var shoreMonitorSymbol: String {
        if !controller.shoreMonitorConnected { return "iphone" }
        return controller.shoreMonitorSoundReady
            ? "checkmark.shield.fill" : "speaker.slash.fill"
    }

    private var shoreMonitorColor: Color {
        if !controller.shoreMonitorConnected { return .secondary }
        if controller.shoreMonitorSoundReady { return .green }
        return controller.anchorAlarmArmed ? .orange : .secondary
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
            let recentExtents = points.suffix(120).map { point in
                hypot(point.eastMeters, point.northMeters)
                    + min(max(0, point.accuracyMeters), limitMeters)
            }.sorted()
            let representativeExtent: Double
            if recentExtents.isEmpty {
                representativeExtent = 0
            } else {
                let percentileIndex = Int(Double(recentExtents.count - 1) * 0.95)
                representativeExtent = recentExtents[percentileIndex]
            }
            let currentExtent = points.last.map { point in
                hypot(point.eastMeters, point.northMeters)
                    + min(max(0, point.accuracyMeters), limitMeters)
            } ?? 0
            let extentMeters = max(
                5,
                limitMeters * 1.2,
                representativeExtent * 1.12,
                currentExtent * 1.12
            )
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
                var segmentStarted = false
                for point in points {
                    guard hypot(point.eastMeters, point.northMeters) <= extentMeters * 1.05 else {
                        segmentStarted = false
                        continue
                    }
                    let location = plotPoint(east: point.eastMeters, north: point.northMeters)
                    if segmentStarted {
                        trail.addLine(to: location)
                    } else {
                        trail.move(to: location)
                        segmentStarted = true
                    }
                }
                context.stroke(
                    trail,
                    with: .color(.blue.opacity(0.72)),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                )
            }

            let pointStride = max(1, points.count / 160)
            for (index, point) in points.enumerated() where index.isMultiple(of: pointStride) {
                guard hypot(point.eastMeters, point.northMeters) <= extentMeters * 1.05 else {
                    continue
                }
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
