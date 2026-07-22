import SwiftUI

/// Speed test screen — latency, jitter, download, upload.
///
/// No Android counterpart exists (the feature is iOS/desktop-only), so this
/// screen just follows the shared visual language: pushed sub-screen =
/// opaque black base + own PixelCanvas, cards on `surface` with the glass
/// stroke, emerald accent.
struct SpeedTestView: View {
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var latencyMs: Int?
    @State private var jitterMs: Int?
    @State private var downloadMbps: Double?
    @State private var uploadMbps: Double?
    @State private var phase: TestPhase = .idle
    @State private var progress: Double = 0

    private var isTesting: Bool { phase != .idle && phase != .complete }

    var body: some View {
        ZStack {
            // Pushed sub-screen contract: opaque black base + own canvas.
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            ScrollView {
                VStack(spacing: BirdoTheme.Spacing.xxl) {
                    progressRing

                    // Results grid
                    LazyVGrid(columns: [
                        GridItem(.flexible(), spacing: BirdoTheme.Spacing.md),
                        GridItem(.flexible(), spacing: BirdoTheme.Spacing.md),
                    ], spacing: BirdoTheme.Spacing.md) {
                        resultCard(
                            icon: "waveform.path",
                            label: "Latency",
                            value: latencyMs.map { "\($0) ms" } ?? "—",
                            color: BirdoTheme.blue
                        )
                        resultCard(
                            icon: "waveform",
                            label: "Jitter",
                            value: jitterMs.map { "\($0) ms" } ?? "—",
                            color: BirdoTheme.accent
                        )
                        resultCard(
                            icon: "arrow.down.circle.fill",
                            label: "Download",
                            value: downloadMbps.map { String(format: "%.1f Mbps", $0) } ?? "—",
                            color: BirdoTheme.green
                        )
                        resultCard(
                            icon: "arrow.up.circle.fill",
                            label: "Upload",
                            value: uploadMbps.map { String(format: "%.1f Mbps", $0) } ?? "—",
                            color: BirdoTheme.yellow
                        )
                    }

                    PrimaryButton(
                        phase == .complete ? "Run Again" : "Start Speed Test",
                        loadingTitle: "Testing…",
                        variant: .brand,
                        isLoading: isTesting
                    ) {
                        startTest()
                    }

                    // Error banner — surfaced when runSpeedTest() fails so the
                    // UI doesn't appear hung mid-phase with a disabled button.
                    if let error = vpnVM.error {
                        ErrorBanner(error, icon: "exclamationmark.circle")
                    }

                    // Server info
                    if vpnVM.isConnected, let server = vpnVM.selectedServer {
                        HStack(spacing: BirdoTheme.Spacing.sm) {
                            Image(systemName: "info.circle")
                                .foregroundColor(BirdoTheme.white40)
                                .font(.caption)
                            Text("Testing through \(server.flag) \(server.name)")
                                .font(BirdoTheme.Fonts.bodySmall)
                                .foregroundColor(BirdoTheme.onSurfaceMuted)
                        }
                    } else {
                        HStack(spacing: BirdoTheme.Spacing.sm) {
                            Image(systemName: "exclamationmark.triangle")
                                .foregroundColor(BirdoTheme.yellow)
                                .font(.caption)
                            Text("Connect to a VPN server first for accurate results.")
                                .font(BirdoTheme.Fonts.bodySmall)
                                .foregroundColor(BirdoTheme.onSurfaceMuted)
                        }
                    }
                }
                .padding(.horizontal, BirdoTheme.Spacing.screenH)
                .padding(.vertical, BirdoTheme.Spacing.screenV)
            }
        }
        .navigationTitle("Speed Test")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: vpnVM.error) { _, newError in
            // runSpeedTest() reports failures via vpnVM.error without invoking
            // the completion/progress callbacks. Without this, the view would
            // stay frozen on a mid-test phase with the button disabled.
            // Reset to idle so the user can read the error and retry.
            if newError != nil, isTesting {
                withAnimation(BirdoTheme.Motion.easeStandard()) {
                    phase = .idle
                    progress = 0
                }
            }
        }
    }

    // MARK: - Progress Ring

    private var progressRing: some View {
        ZStack {
            Circle()
                .stroke(BirdoTheme.white10, lineWidth: 8)
                .frame(width: 140, height: 140)

            Circle()
                .trim(from: 0, to: progress)
                .stroke(phaseColor, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .frame(width: 140, height: 140)
                .rotationEffect(.degrees(-90))
                .animation(BirdoTheme.Motion.easeStandard(), value: progress)

            VStack(spacing: 4) {
                if phase == .idle {
                    Image(systemName: "speedometer")
                        .font(.title)
                        .foregroundColor(BirdoTheme.white40)
                } else if phase == .complete {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title)
                        .foregroundColor(BirdoTheme.green)
                } else {
                    Text(phaseLabel)
                        .font(.caption2)
                        .foregroundColor(BirdoTheme.white55)
                    Text("\(Int(progress * 100))%")
                        .font(.title3.weight(.bold))
                        .monospacedDigit()
                        .foregroundColor(BirdoTheme.onSurface)
                }
            }
        }
        .padding(.top, BirdoTheme.Spacing.sm)
    }

    // MARK: - Result Card

    private func resultCard(icon: String, label: String, value: String, color: Color) -> some View {
        BirdoCard {
            VStack(spacing: BirdoTheme.Spacing.sm) {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundColor(color)
                    .accessibilityHidden(true)
                Text(label)
                    .font(BirdoTheme.Fonts.labelMedium)
                    .foregroundColor(BirdoTheme.white55)
                Text(value)
                    .font(.title3.weight(.bold))
                    .monospacedDigit()
                    .foregroundColor(BirdoTheme.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Test Logic

    private var phaseLabel: String {
        switch phase {
        case .latency: return "Latency"
        case .download: return "Download"
        case .upload: return "Upload"
        default: return ""
        }
    }

    private var phaseColor: Color {
        switch phase {
        case .latency: return BirdoTheme.blue
        case .download: return BirdoTheme.green
        case .upload: return BirdoTheme.yellow
        case .complete: return BirdoTheme.green
        default: return BirdoTheme.white40
        }
    }

    private func startTest() {
        // Reset
        latencyMs = nil
        jitterMs = nil
        downloadMbps = nil
        uploadMbps = nil
        progress = 0
        phase = .latency
        vpnVM.error = nil

        // Delegate to ViewModel — phases update via callback
        vpnVM.runSpeedTest { result in
            withAnimation {
                self.latencyMs = result.latencyMs
                self.jitterMs = result.jitterMs
                self.downloadMbps = result.downloadMbps
                self.uploadMbps = result.uploadMbps
                self.phase = .complete
                self.progress = 1.0
            }
        } onProgress: { currentPhase, currentProgress in
            withAnimation {
                self.phase = currentPhase
                self.progress = currentProgress
            }
        }
    }
}

// MARK: - Types
// Referenced by VpnViewModel.runSpeedTest — keep the definitions here in sync
// with its callback signatures.

enum TestPhase: Sendable {
    case idle, latency, download, upload, complete
}

struct SpeedTestResult: Sendable {
    let latencyMs: Int
    let jitterMs: Int
    let downloadMbps: Double
    let uploadMbps: Double
}
