import CoreImage.CIFilterBuiltins
import SwiftUI

struct AnchorPairingView: View {
    let pairingURL: URL?
    let monitorConnected: Bool
    let monitorSoundReady: Bool
    let refreshPairing: () -> Void
    let rotatePairing: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var showingRotateConfirmation = false
    @State private var showingMoreOptions = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    remoteAccessWarning

                    Text("On the Android shore phone, scan this code with its Camera app. Anchor Watch opens automatically in read-only monitor mode.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Label(statusText, systemImage: statusSymbol)
                        .font(.headline)
                        .foregroundStyle(statusColor)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    if let pairingURL, let image = qrImage(for: pairingURL.absoluteString) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 300)
                            .padding(16)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 16))

                        ShareLink(item: pairingURL) {
                            Label("Share pairing link", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)

                        DisclosureGroup("More options", isExpanded: $showingMoreOptions) {
                            VStack(alignment: .leading, spacing: 14) {
                                Text(pairingURL.absoluteString)
                                    .font(.caption.monospaced())
                                    .textSelection(.enabled)
                                    .foregroundStyle(.secondary)

                                Button("Replace paired monitor", role: .destructive) {
                                    showingRotateConfirmation = true
                                }
                            }
                            .padding(.top, 10)
                        }
                        .font(.subheadline)
                    } else {
                        ContentUnavailableView(
                            "No reachable address",
                            systemImage: "wifi.slash",
                            description: Text("Connect Wi‑Fi or Tailscale, then reopen this screen.")
                        )
                    }

                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .padding()
            }
            .navigationTitle("Pair shore monitor")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog(
                "Replace the paired monitor?",
                isPresented: $showingRotateConfirmation,
                titleVisibility: .visible
            ) {
                Button("Create new pairing code", role: .destructive) {
                    rotatePairing()
                }
            } message: {
                Text("The previous shore phone will no longer authenticate.")
            }
        }
    }

    private var remoteAccessWarning: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Remote monitoring requires a VPN", systemImage: "lock.shield.fill")
                .font(.headline)
                .foregroundStyle(.orange)

            Text("Before pairing for remote use, install Tailscale on both phones, sign in to the same Tailscale network, and connect it. Pairing alone does not provide internet access. Without Tailscale or an equivalent VPN setup, monitoring works only on the same local network.")
                .font(.subheadline)

            Label(remoteAccessStatusText, systemImage: remoteAccessStatusSymbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(includesTailscaleAddress ? .green : .orange)

            Link(destination: Self.tailscaleSetupURL) {
                Label("Tailscale setup instructions", systemImage: "arrow.up.right.square")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button(action: refreshPairing) {
                Label("Refresh after connecting VPN", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.orange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.orange.opacity(0.45), lineWidth: 1)
        }
    }

    private var includesTailscaleAddress: Bool {
        guard let pairingURL,
              let components = URLComponents(url: pairingURL, resolvingAgainstBaseURL: false),
              let hosts = components.queryItems?.first(where: { $0.name == "hosts" })?.value else {
            return false
        }
        return hosts.split(separator: ",").contains { $0.hasPrefix("100.") }
    }

    private var remoteAccessStatusText: String {
        if includesTailscaleAddress {
            return "Tailscale address included. Keep Tailscale connected on both phones."
        }
        return "No Tailscale address detected. Unless another included address is from your VPN, this code is local-network only."
    }

    private var remoteAccessStatusSymbol: String {
        includesTailscaleAddress ? "checkmark.circle.fill" : "exclamationmark.triangle.fill"
    }

    private static let tailscaleSetupURL = URL(string: "https://tailscale.com/docs/how-to/quickstart")!

    private var statusText: String {
        if !monitorConnected { return "Not paired · Waiting for shore monitor" }
        if monitorSoundReady { return "Connected · Sound ready" }
        return "Connected · Setup needed"
    }

    private var statusSymbol: String {
        if !monitorConnected { return "antenna.radiowaves.left.and.right.slash" }
        return monitorSoundReady ? "checkmark.shield.fill" : "exclamationmark.triangle.fill"
    }

    private var statusColor: Color {
        monitorConnected && monitorSoundReady ? .green : .orange
    }

    private func qrImage(for value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
