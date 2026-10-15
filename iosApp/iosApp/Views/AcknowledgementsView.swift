import SwiftUI

/// MR-1688: the open-source acknowledgements, pushed from Settings → About.
///
/// The App Store app (iOS and macOS) ships WireGuardKit and wireguard-go (MIT)
/// compiled in, with the Go runtime and golang.org/x (BSD 3-Clause), and
/// BirdoPQNative statically links more open-source code. Those
/// licences require their notices to travel with the app; until this screen the
/// app carried none.
///
/// The text is `Acknowledgements.txt`, bundled as a resource (the target's
/// `sources: - path: iosApp` copies it) and built from the vendored licence
/// files — WireGuardKit's notice is `iosApp/Vendor/wireguard-apple/COPYING`
/// verbatim. `IosRequiredRowsGuardTest` on the Android CI checks that it still
/// is, and that this screen stays reachable from Settings.
///
/// Pushed-screen contract, as VpnSettingsView: opaque base, own glass top bar
/// with a back chip, system bars hidden.
@MainActor
struct AcknowledgementsView: View {
    @Environment(\.dismiss) private var dismiss

    /// Paragraphs rather than one Text, so the long licence texts render lazily.
    @State private var paragraphs: [String] = []

    /// The bundled text split into paragraphs, or one line saying it is missing.
    nonisolated static func loadParagraphs() -> [String] {
        guard let url = Bundle.main.url(forResource: "Acknowledgements", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return ["The acknowledgements could not be loaded."]
        }
        return text.components(separatedBy: "\n\n")
    }

    var body: some View {
        ZStack {
            BirdoTheme.black.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(paragraphs.indices, id: \.self) { index in
                            Text(paragraphs[index])
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        // Android parity: sub-screens hide the bottom tab bar too.
        .modifier(HideNavigationAndTabBar())
        .onAppear {
            if paragraphs.isEmpty { paragraphs = Self.loadParagraphs() }
        }
    }

    // MARK: - Top bar (Android BirdoTopBar with back chip)

    private var topBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    dismiss()
                } label: {
                    ZStack {
                        Circle()
                            .fill(BirdoTheme.white05)
                            .frame(width: 40, height: 40)
                        Image(systemName: "chevron.left")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onSurface)
                    }
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel("Back")

                Text("Acknowledgements")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(BirdoTheme.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 56)

            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
        }
        .background(BirdoTheme.glassStrong.ignoresSafeArea(edges: .top))
    }
}
