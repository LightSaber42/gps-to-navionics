import SwiftUI

struct ContentView: View {
    private enum DashboardSection: String, CaseIterable, Identifiable {
        case anchor = "Anchor"
        case bridge = "NMEA Stream"

        var id: String { rawValue }
    }

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var controller = BridgeController()
    @StateObject private var purchaseStore = PurchaseStore()
    @State private var selectedSection = DashboardSection.anchor
    @State private var showingAnchorPairing = false
    @State private var showingDisarmConfirmation = false
    @State private var showingBridgeSetup = false
    @State private var showingBridgeDetails = false
    @State private var showingAnchorDetails = false
    @State private var remoteCompanionConfirmed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    sectionSwitcher
#if DEBUG
                    debugEntitlementBanner
#endif

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
            .navigationTitle(
                selectedSection == .anchor ? "Anchor Watch" : "NMEA Stream"
            )
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
            .sheet(
                isPresented: Binding(
                    get: { controller.anchorReadinessPresented },
                    set: { presented in
                        if !presented {
                            controller.dismissAnchorReadiness()
                        }
                    }
                )
            ) {
                AnchorReadinessView(controller: controller)
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
                    await controller.refreshAnchorReadiness()
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
            sectionButton(
                .anchor,
                active: controller.anchorAlarmArmed || controller.isLayingAnchor
            )
            sectionButton(.bridge, active: controller.isStreaming)
        }
        .padding(4)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
    }

#if DEBUG
    private var debugEntitlementBanner: some View {
        Label(
            ProcessInfo.processInfo.arguments.contains("-UseStoreEntitlements")
                ? "StoreKit test mode · Features follow local test purchases"
                : "Debug build · Anchor Watch and Remote features unlocked",
            systemImage: "hammer.fill"
        )
        .font(.footnote.bold())
        .foregroundStyle(.orange)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .accessibilityLabel(
            "Debug build. Anchor Watch and Remote Anchor Watch features are unlocked."
        )
    }
#endif

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
                metric("Clients", controller.connectedClientCount == 1
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
            Label("NMEA Stream connection", systemImage: "network")
                .font(.headline)
            Text("Enter an address below and port " + String(controller.port)
                + " in your marine navigation app.")
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

            Text("Compatible with Navionics Boating via NMEA. This app is not affiliated "
                + "with or endorsed by Navionics.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .cardStyle()
    }

    private var bridgeSetupCard: some View {
        DisclosureGroup(isExpanded: $showingBridgeSetup) {
            VStack(alignment: .leading, spacing: 10) {
                instruction(1, "Put the iPhone and iPad on the same Wi‑Fi network.")
                instruction(2, "Start streaming and allow Precise Location and Local Network access.")
                instruction(3, "In Navionics Boating, open Menu → Connect a Device → Add Device.")
                instruction(4, "Choose TCP, then enter an address above and port \(controller.port).")
                instruction(5, "Keep the iPhone powered during navigation.")
            }
            .padding(.top, 12)
        } label: {
            Label("Set up a navigation app", systemImage: "list.number")
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
            if controller.canUseAnchorWatch {
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
            } else {
                anchorWatchIntroduction
            }
        }
    }

    private var anchorWatchIntroduction: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 14) {
                Image(systemName: "anchor.circle.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.blue)
                    .accessibilityHidden(true)
                Text("Anchor Watch")
                    .font(.largeTitle.bold())
                Text("Turn the boat phone into a local drift and GPS-loss alarm. Choose a shore-tied position or a free-swinging anchor circle, then keep the phone aboard and powered.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                purchaseBenefit(
                    "Two anchoring modes",
                    detail: "Shorelined watches the tied boat position; Free swinging follows a marked anchor-drop point.",
                    symbol: "point.topleft.down.to.point.bottomright.curvepath"
                )
                purchaseBenefit(
                    "Durable watch history",
                    detail: "See the current trail, alarm boundary, GPS accuracy, and durable alarm events.",
                    symbol: "chart.xyaxis.line"
                )
                purchaseBenefit(
                    "Local safety alarms",
                    detail: "Drift confirms for 5 seconds and GPS loss alarms after 30 seconds while the watch remains armed.",
                    symbol: "bell.and.waves.left.and.right.fill"
                )
            }
            .cardStyle()

            VStack(alignment: .leading, spacing: 12) {
                Text("Choose your level")
                    .font(.headline)
                tierRow("NMEA Stream", value: "Free", detail: "GPS-to-NMEA streaming stays free.")
                Divider()
                tierRow(
                    "Anchor Watch",
                    value: purchaseStore.anchorWatchPrice ?? "Price loading…",
                    detail: "One-time purchase on this Apple account."
                )
                Divider()
                tierRow(
                    "Remote Anchor Watch",
                    value: "Optional add-on",
                    detail: "Requires Anchor Watch, a separate Android shore phone, and a monthly or annual subscription."
                )
            }
            .cardStyle()

            VStack(alignment: .leading, spacing: 12) {
                Text("Unlock Anchor Watch")
                    .font(.headline)
                Text("This is a one-time, platform-specific purchase. It restores only with the same Apple account and does not transfer to Google Play.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button {
                    Task {
                        await purchaseStore.purchase(
                            productID: StoreProductIdentifiers.anchorWatchLifetime
                        )
                    }
                } label: {
                    Label(
                        anchorPurchaseButtonTitle,
                        systemImage: "lock.open.fill"
                    )
                    .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(purchaseActionInProgress || purchaseStore.anchorWatchPrice == nil)

                purchaseStatusView

                Button("Restore Purchases") {
                    Task { await purchaseStore.restorePurchases() }
                }
                .disabled(purchaseActionInProgress)

                releasePolicyLinks

                Text("Anchor Watch is an auxiliary aid, not a certified navigation or safety system. Test alarms before relying on it and keep an independent anchor watch and navigation backup.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .cardStyle()
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
                alarmTriggered: controller.anchorAlarmTriggered,
                displayUnitPreference: controller.displayUnitPreference
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
                    controller.formatDistance($0, fractionDigits: 1)
                } ?? "—")
                    .font(.system(size: 42, weight: .bold, design: .rounded))
                    .foregroundStyle(controller.anchorAlarmTriggered ? .red : .primary)
                    .contentTransition(.numericText())
                Text(
                    "distance from reference • "
                        + "\(controller.formatDistance(controller.anchorRadiusMeters)) limit"
                )
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

                Text(controller.formatDistance(controller.anchorRadiusMeters))
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
            return "Adjustable while anchored • "
                + "\(controller.formatDistance(controller.anchorMode.radiusStepMeters)) steps"
        }
        return controller.formatRadiusRange(for: controller.anchorMode)
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

            Text("Add a separate Android phone for read-only remote status, durable alarm replay, and connection-loss alerts.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if controller.canUseRemoteAnchorWatch {
                Button {
                    controller.refreshAnchorPairing()
                    showingAnchorPairing = true
                } label: {
                    Label(
                        controller.shoreMonitorConnected
                            ? "View pairing code"
                            : "Set up shore monitor",
                        systemImage: "qrcode"
                    )
                }
                .font(.subheadline)

                Link(
                    "Manage Subscription",
                    destination: URL(string: "https://apps.apple.com/account/subscriptions")!
                )
                .font(.subheadline)
                remoteSubscriptionStatusView
            } else if purchaseStore.entitlementDecision.featureLevel.rawValue
                        >= FeatureLevel.anchorWatch.rawValue {
                remoteAnchorWatchOffer
            } else {
                Text("Remote upgrades require currently validated Anchor Watch ownership. Your active local watch remains protected until it is disarmed.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            releasePolicyLinks
        }
        .cardStyle()
    }

    private var remoteAnchorWatchOffer: some View {
        VStack(alignment: .leading, spacing: 12) {
            Divider()
            Text("Add Remote Anchor Watch")
                .font(.headline)
            Text("Requires a separate Android phone; iPhone and iPad cannot act as the shore monitor.")
                .font(.subheadline.bold())
            Text("Both phones must be reachable on the same LAN or through your own Tailscale/equivalent VPN. The app provides no developer-operated internet relay.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Toggle(
                "I have a separate Android phone for shore monitoring",
                isOn: $remoteCompanionConfirmed
            )
            .font(.subheadline)

            remoteProductButton(
                title: "Monthly",
                price: purchaseStore.remoteMonthlyPrice,
                productID: StoreProductIdentifiers.remoteMonthly,
                highlighted: false
            )
            remoteProductButton(
                title: "Annual · Best value",
                price: purchaseStore.remoteAnnualPrice,
                productID: StoreProductIdentifiers.remoteAnnual,
                highlighted: true
            )

            purchaseStatusView

            Text("Subscriptions auto-renew for the displayed billing period unless cancelled at least 24 hours before renewal. Manage or cancel in App Store subscriptions. Access is platform-specific and does not transfer to Google Play.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            HStack {
                Button("Restore Purchases") {
                    Task { await purchaseStore.restorePurchases() }
                }
                .disabled(purchaseActionInProgress)
                Spacer()
                Link(
                    "Manage Subscription",
                    destination: URL(string: "https://apps.apple.com/account/subscriptions")!
                )
            }
            .font(.subheadline)
        }
    }

    private var releasePolicyLinks: some View {
        HStack(spacing: 14) {
            configuredReleaseLink("Privacy", destination: ReleaseLinks.privacy)
            configuredReleaseLink("Support", destination: ReleaseLinks.support)
            configuredReleaseLink("Terms", destination: ReleaseLinks.terms)
        }
        .font(.footnote)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func configuredReleaseLink(_ title: String, destination: URL?) -> some View {
        if let destination {
            Link(title, destination: destination)
        } else {
            Text(title)
                .foregroundStyle(.tertiary)
                .accessibilityLabel("\(title) link pending release configuration")
        }
    }

    private func remoteProductButton(
        title: String,
        price: String?,
        productID: String,
        highlighted: Bool
    ) -> some View {
        Button {
            Task { await purchaseStore.purchase(productID: productID) }
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(price ?? "Price loading…")
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity, minHeight: 42)
        }
        .buttonStyle(.borderedProminent)
        .tint(highlighted ? .blue : .secondary)
        .disabled(!remoteCompanionConfirmed || price == nil || purchaseActionInProgress)
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
                Divider()
                detailRow(
                    "Safety readiness",
                    controller.anchorReadinessComplete ? "Ready" : "Needs attention"
                )
                Button(
                    controller.anchorReadinessComplete
                        ? "Review readiness and test alarm"
                        : "Complete safety readiness",
                    action: controller.showAnchorReadinessSettings
                )
                .buttonStyle(.bordered)
                Picker(
                    "Display units",
                    selection: Binding(
                        get: { controller.displayUnitPreference },
                        set: controller.setDisplayUnitPreference
                    )
                ) {
                    ForEach(DisplayUnitPreference.allCases) { preference in
                        Text(preference.detail()).tag(preference)
                    }
                }
                .pickerStyle(.menu)
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

    private func purchaseBenefit(
        _ title: String,
        detail: String,
        symbol: String
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.blue)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.bold())
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func tierRow(_ title: String, value: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.subheadline.bold())
                Spacer()
                Text(value)
                    .font(.subheadline.bold())
                    .multilineTextAlignment(.trailing)
            }
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var purchaseStatusView: some View {
        switch purchaseStore.actionState {
        case .idle:
            EmptyView()
        case .loadingProducts:
            Label("Loading App Store products…", systemImage: "arrow.triangle.2.circlepath")
                .foregroundStyle(.secondary)
        case .purchasing:
            Label("Waiting for the App Store…", systemImage: "cart.fill")
                .foregroundStyle(.secondary)
        case .pending:
            Label(
                "Purchase pending. Access unlocks only after the App Store confirms payment.",
                systemImage: "clock.fill"
            )
            .foregroundStyle(.orange)
        case .cancelled:
            Label("Purchase cancelled. Nothing was charged.", systemImage: "xmark.circle")
                .foregroundStyle(.secondary)
        case .restoring:
            Label("Restoring purchases…", systemImage: "arrow.clockwise")
                .foregroundStyle(.secondary)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private var remoteSubscriptionStatusView: some View {
        switch purchaseStore.remoteSubscriptionState {
        case .unavailable, .notSubscribed:
            EmptyView()
        case .subscribed:
            Label("Remote subscription active", systemImage: "checkmark.seal.fill")
                .foregroundStyle(.green)
        case .inGracePeriod:
            Label(
                "Subscription is in a billing grace period. Update payment details in the App Store.",
                systemImage: "exclamationmark.circle.fill"
            )
            .foregroundStyle(.orange)
        case .inBillingRetry:
            Label(
                "The App Store is retrying billing. Manage the subscription to avoid losing new-session access.",
                systemImage: "arrow.clockwise.circle.fill"
            )
            .foregroundStyle(.orange)
        case .expired:
            Label("Remote subscription expired.", systemImage: "calendar.badge.exclamationmark")
                .foregroundStyle(.orange)
        case .revoked:
            Label("Remote subscription was revoked or refunded.", systemImage: "xmark.seal.fill")
                .foregroundStyle(.red)
        }
    }

    private var purchaseActionInProgress: Bool {
        switch purchaseStore.actionState {
        case .loadingProducts, .purchasing, .restoring:
            return true
        default:
            return false
        }
    }

    private var anchorPurchaseButtonTitle: String {
        guard let price = purchaseStore.anchorWatchPrice else {
            return "Loading App Store price…"
        }
        return "Unlock Anchor Watch · \(price)"
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
    let displayUnitPreference: DisplayUnitPreference

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
                Text("\(distanceFormatter.format(metres: limitMeters)) limit")
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
            return "Waiting for GPS points. Alarm limit "
                + "\(distanceFormatter.format(metres: limitMeters))."
        }
        return "Current drift "
            + "\(distanceFormatter.format(metres: hypot(current.eastMeters, current.northMeters), fractionDigits: 1)). "
            + "Alarm limit \(distanceFormatter.format(metres: limitMeters)). "
            + "\(points.count) GPS points plotted."
    }

    private var distanceFormatter: DistanceDisplayFormatter {
        DistanceDisplayFormatter(preference: displayUnitPreference)
    }
}

private struct AnchorReadinessView: View {
    @ObservedObject var controller: BridgeController

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Before the first watch", systemImage: "checkmark.shield")
                            .font(.title2.bold())
                        Text("Confirm the permissions and alarm sound this iPhone needs while it remains aboard. Permission changes are checked again before arming.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .cardStyle()

                    VStack(alignment: .leading, spacing: 14) {
                        readinessRow(
                            title: "Precise Location",
                            detail: controller.preciseLocationReadinessDescription,
                            ready: controller.preciseLocationReady,
                            actionTitle: locationActionTitle,
                            action: controller.requestReadinessLocationAccess
                        )
                        Divider()
                        readinessRow(
                            title: "Background Location",
                            detail: controller.backgroundLocationReadinessDescription,
                            ready: controller.backgroundLocationReady,
                            actionTitle: locationActionTitle,
                            action: controller.requestReadinessLocationAccess
                        )
                        Divider()
                        readinessRow(
                            title: "Notifications and sound",
                            detail: controller.notificationReadinessDescription,
                            ready: controller.notificationsReady,
                            actionTitle: notificationActionTitle,
                            action: controller.requestReadinessNotificationAccess
                        )
                    }
                    .cardStyle()

                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top, spacing: 12) {
                            readinessSymbol(ready: controller.readinessSirenConfirmed)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Alarm sound test")
                                    .font(.headline)
                                Text(
                                    controller.readinessSirenConfirmed
                                        ? "Siren confirmed on this iPhone"
                                        : "Play the two-second siren, then confirm that you heard it"
                                )
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            }
                        }

                        Button {
                            controller.playReadinessTestSiren()
                        } label: {
                            Label(
                                controller.readinessSirenPlaying
                                    ? "Siren playing…"
                                    : controller.readinessSirenConfirmed
                                        ? "Test siren again"
                                        : "Play two-second siren",
                                systemImage: "speaker.wave.3.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(controller.readinessSirenPlaying)

                        if controller.readinessSirenPlayedThisVisit
                            && !controller.readinessSirenConfirmed {
                            Button(
                                "I heard the siren",
                                action: controller.confirmReadinessSirenHeard
                            )
                            .buttonStyle(.borderedProminent)
                            .frame(maxWidth: .infinity)
                        }

                        Text("A notification cannot guarantee sound while the iPhone is muted or in Focus. Test the phone in the conditions you will use aboard.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .cardStyle()

                    VStack(alignment: .leading, spacing: 12) {
                        Toggle(
                            isOn: Binding(
                                get: { controller.readinessSafetyAcknowledged },
                                set: controller.setReadinessSafetyAcknowledged
                            )
                        ) {
                            Text("I understand Anchor Watch is an auxiliary aid. It does not replace a responsible anchor watch or independent navigation and safety equipment.")
                                .font(.subheadline)
                        }

                        Text("iOS cannot relaunch this app after a force-quit or restart. Reopen the app and re-arm Anchor Watch before leaving the phone aboard.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .cardStyle()

                    Button {
                        controller.completeAnchorReadinessAndContinue()
                    } label: {
                        Text(
                            controller.anchorReadinessWillResumeArming
                                ? "Continue and arm"
                                : "Save readiness"
                        )
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!controller.anchorReadinessRequirementsSatisfied)
                }
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Safety Readiness")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close", action: controller.dismissAnchorReadiness)
                }
            }
            .task {
                await controller.refreshAnchorReadiness()
            }
        }
    }

    private var locationActionTitle: String {
        switch controller.authorizationStatus {
        case .notDetermined:
            return "Allow Location"
        case .authorizedWhenInUse:
            return "Allow in Background"
        case .authorizedAlways:
            return controller.preciseLocationReady ? "Enabled" : "Open Settings"
        case .denied, .restricted:
            return "Open Settings"
        @unknown default:
            return "Open Settings"
        }
    }

    private var notificationActionTitle: String {
        switch controller.notificationAuthorizationStatus {
        case .notDetermined:
            return "Allow Notifications"
        case .authorized, .provisional, .ephemeral:
            return "Enabled"
        case .denied:
            return "Open Settings"
        @unknown default:
            return "Open Settings"
        }
    }

    private func readinessRow(
        title: String,
        detail: String,
        ready: Bool,
        actionTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            readinessSymbol(ready: ready)
            VStack(alignment: .leading, spacing: 7) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if !ready {
                    Button(actionTitle, action: action)
                        .buttonStyle(.bordered)
                }
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .contain)
    }

    private func readinessSymbol(ready: Bool) -> some View {
        Image(systemName: ready ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
            .font(.title2)
            .foregroundStyle(ready ? Color.green : Color.orange)
            .accessibilityLabel(ready ? "Ready" : "Action required")
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
