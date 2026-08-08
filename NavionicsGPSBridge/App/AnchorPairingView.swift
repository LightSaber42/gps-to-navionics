import CoreImage.CIFilterBuiltins
import SwiftUI

struct AnchorPairingView: View {
    let pairingURL: URL?
    let monitorConnected: Bool
    let monitorSoundReady: Bool
    let rotatePairing: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var showingRotateConfirmation = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text("On the Android shore phone, scan this code with its Camera app. The GPS bridge opens automatically in read-only monitor mode.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
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

                        Text(pairingURL.absoluteString)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                            .foregroundStyle(.secondary)
                    } else {
                        ContentUnavailableView(
                            "No reachable address",
                            systemImage: "wifi.slash",
                            description: Text("Connect Wi‑Fi or Tailscale, then reopen this screen.")
                        )
                    }

                    Label(statusText, systemImage: statusSymbol)
                        .font(.headline)
                        .foregroundStyle(statusColor)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Button("Replace paired monitor", role: .destructive) {
                        showingRotateConfirmation = true
                    }
                    .disabled(monitorConnected)
                }
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

    private var statusText: String {
        if !monitorConnected { return "Waiting for the shore monitor" }
        if monitorSoundReady { return "Connected • alarm sound ready" }
        return "Connected • alarm sound needs attention"
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
