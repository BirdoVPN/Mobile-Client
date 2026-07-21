import SwiftUI

/// Home (Connect) screen — Android HomeScreen parity per
/// spec-home-servers-consent.md §3, emerald visual identity per
/// spec-pixelcanvas-design.md §4.
///
/// Structure, top to bottom: HomeTopBar (brand lockup + identity + logout) →
/// StatusPill hero (+ kill-switch / quantum chips) → breathing space (the
/// app-root PixelCanvas shows through — tab roots stay transparent, per the
/// placement contract) → bottom action panel (connected stats, banners,
/// server selector, connect CTA).
///
/// THE GREEN BUDGET (privacy invariant, do not "fix"): connection state is
/// separated by LUMINANCE, never hue — idle CTA is DEEP emerald
/// (`Gradients.connectIdle`), connected is luminous mint + glow + pulse.
struct HomeView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var settingsVM: SettingsViewModel

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var showLogoutConfirm = false
    // Haptic triggers — `.sensoryFeedback` fires on value CHANGE only, so a
    // bumped counter plays exactly one tap's worth of feedback.
    @State private var connectHapticTick = 0
    @State private var disconnectHapticTick = 0

    var body: some View {
        VStack(spacing: 0) {
            topBar

            statusArea
                .padding(.top, 12)

            // The globe hero was not ported (no iOS WorldGlobe); the root
            // PixelCanvas twinkles through this gap instead.
            Spacer(minLength: 0)

            bottomPanel
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Tab-root screens are transparent over the ONE app-root PixelCanvas.
        .background(Color.clear)
        // The screen draws its own top bar; an empty system bar would push
        // everything down and paint over the canvas.
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            // Defensive kick — cheap thanks to the 60 s TTL + in-flight guard.
            // The app root owns the real login-flip / cold-start load.
            vpnVM.loadServers()
        }
        .sensoryFeedback(.impact(weight: .heavy), trigger: connectHapticTick)
        .sensoryFeedback(.warning, trigger: disconnectHapticTick)
        // Android parity: a Confirm haptic the moment the state becomes
        // Connected (not when the user taps — when the tunnel actually is up).
        .sensoryFeedback(.success, trigger: vpnVM.isConnected) { _, nowConnected in
            nowConnected
        }
        .birdoConfirmDialog(
            isPresented: $showLogoutConfirm,
            title: "Log Out?",
            message: vpnVM.isConnected
                ? "You are still connected. Logging out will disconnect."
                : "Are you sure you want to log out?",
            confirmLabel: "Log Out",
            onConfirm: {
                Task { @MainActor in
                    // Order is load-bearing: disconnectForSignOut() tears the
                    // tunnel down and AWAITS the server-side slot release
                    // BEFORE logout() wipes the keychain the extension reads.
                    await vpnVM.disconnectForSignOut()
                    authVM.logout()
                }
            }
        )
    }

    /// Reduce Motion: return nil so state changes land instantly.
    private func anim(_ animation: Animation) -> Animation? {
        reduceMotion ? nil : animation
    }

    // MARK: - Top Bar (Android §3.2)

    private var topBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                // Real brand asset — the SF-symbol bird placeholder is dead.
                BirdoLogo.topBar

                Text("Birdo VPN")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(BirdoTheme.onBackground)
                    .lineLimit(1)

                Spacer(minLength: BirdoTheme.Spacing.md)

                if let identity = accountIdentity {
                    Text(identity)
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: 120, alignment: .trailing)
                }

                Button {
                    showLogoutConfirm = true
                } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(BirdoTheme.white05))
                }
                .buttonStyle(PressScaleButtonStyle())
                .frame(minWidth: BirdoTheme.Spacing.minTouch,
                       minHeight: BirdoTheme.Spacing.minTouch)
                .accessibilityLabel("Logout")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            // minHeight, not height: a hard 48pt clipped the row once Dynamic
            // Type went above the default sizes.
            .frame(minHeight: 48)

            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
        }
        .background(
            BirdoTheme.surface.opacity(0.78)
                .ignoresSafeArea(edges: .top)
        )
    }

    /// Android shows the email here; for anonymous accounts it shows the
    /// 24-digit anonymous ID instead.
    private var accountIdentity: String? {
        if let email = authVM.userEmail { return email }
        if authVM.isAnonymousAccount { return authVM.anonymousAccountNumber }
        return nil
    }

    // MARK: - Status Hero (Android §3.3, desktop §4.2–4.4)

    /// Android only pattern-matches Connected/Connecting/Error — everything
    /// else falls through to the neutral pill (spec warning 4). iOS cannot
    /// observe "Disconnecting…" (the VM keeps `isConnected` until the drop
    /// actually lands), so that state renders as Protected until it does.
    private var statusText: String {
        if vpnVM.isConnected { return "Protected" }
        if vpnVM.isConnecting { return "Connecting…" }
        if vpnVM.error != nil { return "Connection Error" }
        return "Not Connected"
    }

    private var statusTone: StatusBadgePill.Tone {
        if vpnVM.isConnected { return .success }
        if vpnVM.isConnecting { return .warning }
        if vpnVM.error != nil { return .danger }
        return .neutral
    }

    private var statusIcon: String? {
        if vpnVM.isConnected { return nil } // pulse dot instead
        if vpnVM.isConnecting { return "arrow.triangle.2.circlepath" }
        if vpnVM.error != nil { return "exclamationmark.circle" }
        return "wifi.slash"
    }

    private var statusArea: some View {
        VStack(spacing: 8) {
            ZStack {
                StatusBadgePill(statusText,
                                tone: statusTone,
                                icon: statusIcon,
                                pulse: vpnVM.isConnected)
                    // Recreate the pill per state: the crossfade replaces
                    // Android's AnimatedContent, and a fresh PulsingDot replays
                    // its finite 3-burst on every re-entry into Connected.
                    .id(statusText)
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .move(edge: .top)),
                        removal: .opacity))
            }
            .animation(anim(BirdoTheme.Motion.decel(BirdoTheme.Motion.standard)),
                       value: statusText)

            // Feature chips (desktop §4.4 pattern — under the pill, brand
            // tone). Kill Switch reflects the standing setting (iOS has no
            // reliable "blocking now" signal); Quantum is the HONEST indicator
            // — lit only when a bilateral ML-KEM PSK was derived.
            if settingsVM.killSwitchEnabled || vpnVM.quantumActive {
                HStack(spacing: 8) {
                    if settingsVM.killSwitchEnabled {
                        StatusBadgePill("Kill Switch",
                                        tone: .brand,
                                        icon: "checkmark.shield.fill")
                            .accessibilityLabel("Kill switch enabled")
                    }
                    if vpnVM.quantumActive {
                        StatusBadgePill("Quantum",
                                        tone: .brand,
                                        icon: "lock.fill")
                            .accessibilityLabel("Quantum protection active")
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(anim(BirdoTheme.Motion.easeStandard(0.2)),
                   value: vpnVM.quantumActive)
        .animation(anim(BirdoTheme.Motion.easeStandard(0.2)),
                   value: settingsVM.killSwitchEnabled)
    }

    // MARK: - Bottom Action Panel (Android §3.4)

    private var bottomPanel: some View {
        VStack(spacing: 10) {
            if vpnVM.isConnected {
                statsRow
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            if vpnVM.isReapplyingSettings {
                reapplyBanner
                    .transition(.opacity.combined(with: .scale(scale: 0.95)))
            }

            if let message = vpnVM.error {
                // Backend refusals arrive verbatim in `error` (device cap,
                // plan gate, node offline/maintenance, 426 version floor,
                // heartbeat revocation) — render the server's own words,
                // never rephrase. ErrorBanner announces assertively.
                ErrorBanner(message, icon: "exclamationmark.circle")
            }

            serverSelector

            connectButton
        }
        .frame(maxWidth: 480) // AdaptiveContainer parity (Android §8)
        .padding(.horizontal, BirdoTheme.Spacing.screenH)
        .padding(.vertical, BirdoTheme.Spacing.screenV)
        .frame(maxWidth: .infinity)
        .background(panelBackground)
        // Stats reveal: fade + slide, Standard/Decel, 80 ms delay (§3.4 item 1).
        .animation(anim(BirdoTheme.Motion.decel(BirdoTheme.Motion.standard).delay(0.08)),
                   value: vpnVM.isConnected)
        .animation(anim(BirdoTheme.Motion.easeStandard()), value: vpnVM.error)
        .animation(anim(BirdoTheme.Motion.easeStandard()),
                   value: vpnVM.isReapplyingSettings)
    }

    /// Sheet-look panel: top corners 24, near-opaque surface fill (no blur —
    /// materials smear over the moving pixel grid), 1pt top hairline.
    private var panelBackground: some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: BirdoTheme.Radius.xl,
            bottomLeadingRadius: 0,
            bottomTrailingRadius: 0,
            topTrailingRadius: BirdoTheme.Radius.xl,
            style: .continuous
        )
        return ZStack(alignment: .top) {
            shape.fill(BirdoTheme.surface.opacity(0.92))
            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
        }
        .clipShape(shape)
    }

    // MARK: - Connected Stats (Android §3.5)

    private var statsRow: some View {
        HStack(spacing: 10) {
            // Duration derives from `connectedSince` inside a TimelineView so
            // ONLY this tile re-renders each second — the per-second tick must
            // never invalidate the whole screen (spec perf contract, and the
            // byte counters alone won't redraw the clock when IPC values
            // repeat).
            TimelineView(.periodic(from: .now, by: 1)) { context in
                StatCard(icon: "clock",
                         iconColor: BirdoTheme.accentSoft,
                         value: durationText(at: context.date),
                         label: "Duration")
            }
            StatCard(icon: "arrow.down",
                     iconColor: BirdoTheme.greenLight,
                     value: Self.formatBytes(vpnVM.bytesReceived),
                     label: "Download")
            StatCard(icon: "arrow.up",
                     iconColor: BirdoTheme.blue,
                     value: Self.formatBytes(vpnVM.bytesSent),
                     label: "Upload")
        }
    }

    /// "MM:SS" or "H:MM:SS" — hours unpadded, min/sec zero-padded, "00:00"
    /// when idle (Android format, exactly).
    private func durationText(at date: Date) -> String {
        guard let since = vpnVM.connectedSince else { return "00:00" }
        let elapsed = max(0, Int(date.timeIntervalSince(since)))
        let hours = elapsed / 3600
        let minutes = (elapsed % 3600) / 60
        let seconds = elapsed % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }

    /// Android byte format: "N B" below 1024; KB/MB 1 decimal; GB 2 decimals
    /// (e.g. "45.3 MB", "1.20 GB").
    private static func formatBytes(_ bytes: Int64) -> String {
        let b = Double(max(0, bytes))
        if b < 1024 { return "\(Int(b)) B" }
        let kb = b / 1024
        if kb < 1024 { return String(format: "%.1f KB", kb) }
        let mb = kb / 1024
        if mb < 1024 { return String(format: "%.1f MB", mb) }
        return String(format: "%.2f GB", mb / 1024)
    }

    // MARK: - Settings-Blip Banner

    /// Shown while `reapplySettings()` bounces the tunnel (apply-on-change).
    private var reapplyBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 18))
            Text("Applying settings — reconnecting…")
                .font(.system(size: 13))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(BirdoTheme.accentSoft)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .fill(BirdoTheme.accentBg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .strokeBorder(BirdoTheme.accentA(0.3), lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
    }

    // MARK: - Server Selector (Android §3.7)

    private var serverSelector: some View {
        NavigationLink {
            ServerListView()
        } label: {
            BirdoCard(horizontalPadding: 14, verticalPadding: 14) {
                HStack(spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub,
                                         style: .continuous)
                            .fill(BirdoTheme.white05)
                        RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub,
                                         style: .continuous)
                            .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
                        Text(vpnVM.selectedServer?.flag ?? "🌐")
                            .font(.system(size: 22))
                    }
                    .frame(width: 44, height: 44)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(vpnVM.selectedServer?.name ?? "Select Server")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onSurface)
                            .lineLimit(1)
                        if let server = vpnVM.selectedServer {
                            // Two spaces around the middle dot — Android
                            // renders "%s  ·  %d%% load" exactly.
                            Text("\(server.city.isEmpty ? server.country : server.city)  ·  \(server.load)% load")
                                .font(BirdoTheme.Fonts.bodySmall)
                                .foregroundStyle(BirdoTheme.white60)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Image(systemName: "chevron.right")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(BirdoTheme.white40)
                        .accessibilityHidden(true)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        // Android disables the selector only while connecting/disconnecting;
        // browsing while CONNECTED stays allowed (live server switch).
        .disabled(vpnVM.isConnecting)
        .opacity(vpnVM.isConnecting ? 0.5 : 1)
        .accessibilityHint("Select server")
    }

    // MARK: - Connect CTA (Android §3.4 CompactConnectButton)

    private enum ConnectCTA: Equatable { case idle, busy, connected }

    private var cta: ConnectCTA {
        if vpnVM.isConnected { return .connected }
        if vpnVM.isConnecting { return .busy }
        return .idle
    }

    private var ctaLabel: String {
        switch cta {
        case .connected: return "Disconnect"
        case .busy:      return "Connecting…"
        case .idle:      return "Connect"
        }
    }

    private var connectButton: some View {
        Button(action: handleConnectTap) {
            HStack(spacing: 10) {
                if cta == .busy {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                } else {
                    Image(systemName: "power")
                        .font(.system(size: 22, weight: .semibold))
                }
                Text(ctaLabel)
                    .font(.system(size: 16, weight: .semibold))
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(
                // Gradients can't tween in SwiftUI, and the spec forbids a
                // hard cut — crossfade three cheap layers instead. Idle stays
                // DEEP emerald, connected is the mint fill (green budget).
                ZStack {
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.card,
                                     style: .continuous)
                        .fill(BirdoTheme.Gradients.connectIdle)
                        .opacity(cta == .idle ? 1 : 0)
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.card,
                                     style: .continuous)
                        .fill(BirdoTheme.Gradients.connectBusy)
                        .opacity(cta == .busy ? 1 : 0)
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.card,
                                     style: .continuous)
                        .fill(BirdoTheme.Gradients.connectConnected)
                        .opacity(cta == .connected ? 1 : 0)
                }
            )
            .overlay(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.card,
                                 style: .continuous)
                    .strokeBorder(Color.white.opacity(0.16), lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: BirdoTheme.Radius.card,
                                           style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(cta == .busy)
        .shadow(color: cta == .connected ? BirdoTheme.greenShadow : BirdoTheme.accentA(0.45),
                radius: 16, x: 0, y: 14)
        .animation(anim(BirdoTheme.Motion.decel(BirdoTheme.Motion.emphasis)), value: cta)
        .accessibilityLabel(ctaLabel)
    }

    private func handleConnectTap() {
        if vpnVM.isConnected {
            disconnectHapticTick += 1
            vpnVM.disconnect()
        } else if !vpnVM.isConnecting {
            connectHapticTick += 1
            // Refusals ("Select a server first", device caps, plan gates,
            // offline nodes, 426) land in vpnVM.error → banner above.
            vpnVM.connect()
        }
    }
}

/// Convert a 2-letter country code to a flag emoji.
/// Module-level on purpose: `ServerInfo.flag` (ServerListView.swift) calls
/// this too — do not move or duplicate it.
func flagEmoji(_ code: String) -> String {
    guard code.count == 2 else { return "🌐" }
    let base: UInt32 = 0x1F1E6
    let chars = code.uppercased().unicodeScalars.compactMap {
        UnicodeScalar(base + $0.value - UnicodeScalar("A").value)
    }
    return String(chars.map { Character($0) })
}
