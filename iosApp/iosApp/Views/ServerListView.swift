import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Server browser — pushed from Home (Android route "servers").
///
/// Layout, strings and sorting follow spec-home-servers-consent.md §4: glass
/// top bar with the FILTERED count, search field, filter pills, first-load
/// skeleton, per-filter empty states, and flat server rows. Deliberate
/// divergences from the Android reference, each flagged inline:
/// - Pull-to-refresh is real here (Android ships only the hint string).
/// - Fetch failures render on THIS screen with a Retry (S2 fix) instead of
///   leaking to the Home banner.
/// - Locked rows carry a plan badge + tap-to-upsell notice, and rows show
///   small high-speed / port-forwarding capability glyphs.
struct ServerListView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var searchQuery = ""
    @State private var activeFilter: ServerFilter = .all
    /// Transient snackbar shown when a plan-locked row is tapped. Navigation
    /// to the Subscription screen is intentionally NOT wired here — the nav
    /// graph is owned elsewhere; this surfaces the required plan by name.
    @State private var upsellNotice: String?

    /// The old "Streaming" / "P2P" pills matched NOTHING in production — both
    /// flags were cosmetic and false on every node. They are replaced by the
    /// two capabilities Birdo actually ships: a low-load / high-throughput
    /// node, and inbound port forwarding. Birdo offers no streaming-unblocking
    /// and no P2P servers, so no pill may imply otherwise.
    enum ServerFilter: CaseIterable {
        case all, favorites, highSpeed, portForwarding

        var baseLabel: String {
            switch self {
            case .all:            return "All"
            case .favorites:      return "★ Favorites"
            case .highSpeed:      return "⚡ High-Speed"
            case .portForwarding: return "⇄ Port Forwarding"
            }
        }
    }

    private var trimmedQuery: String {
        searchQuery.trimmingCharacters(in: .whitespaces)
    }

    private var favoriteCount: Int {
        vpnVM.servers.reduce(0) { $0 + (vpnVM.favoriteIds.contains($1.id) ? 1 : 0) }
    }

    private var filteredServers: [ServerInfo] {
        let query = trimmedQuery
        return vpnVM.servers
            .filter { server in
                let matchesSearch = query.isEmpty
                    || server.name.localizedCaseInsensitiveContains(query)
                    || server.country.localizedCaseInsensitiveContains(query)
                    || server.city.localizedCaseInsensitiveContains(query)

                let matchesFilter: Bool = {
                    switch activeFilter {
                    case .all:            return true
                    case .favorites:      return vpnVM.favoriteIds.contains(server.id)
                    case .highSpeed:      return server.isHighSpeed
                    case .portForwarding: return server.isPortForwarding
                    }
                }()

                return matchesSearch && matchesFilter
            }
            .sorted {
                let aFav = vpnVM.favoriteIds.contains($0.id)
                let bFav = vpnVM.favoriteIds.contains($1.id)
                if aFav != bFav { return aFav }
                // Locked nodes (plan too low) sink below usable ones — shown,
                // not hidden, so the user can see what a plan unlocks.
                if $0.accessible != $1.accessible { return $0.accessible }
                if $0.isOnline != $1.isOnline { return $0.isOnline }
                if $0.load != $1.load { return $0.load < $1.load }
                return $0.name < $1.name
            }
    }

    var body: some View {
        let filtered = filteredServers
        ZStack {
            // Pushed-screen contract: opaque black base + own pixel canvas
            // (the root canvas is hidden behind the push transition).
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            VStack(spacing: 0) {
                topBar(filteredCount: filtered.count)

                VStack(spacing: 0) {
                    searchField
                    filterPills

                    if let error = vpnVM.serversError {
                        // S2 fix: this screen renders its OWN fetch errors —
                        // backend/transport message verbatim, never rephrased.
                        ErrorBanner(error, icon: "exclamationmark.circle")
                            .padding(.horizontal, BirdoTheme.Spacing.lg)
                            .padding(.top, BirdoTheme.Spacing.sm)
                    }

                    if vpnVM.isLoadingServers && !vpnVM.servers.isEmpty {
                        // Refresh-with-content: thin progress bar, list stays.
                        ThinRefreshBar()
                            .padding(.top, BirdoTheme.Spacing.xs)
                    }

                    serverList(filtered)
                }
                // AdaptiveContainer parity (§8): full width on phones,
                // centered column on wide layouts (Stage Manager / iPad).
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .animation(BirdoTheme.Motion.easeStandard(), value: vpnVM.serversError)
            .animation(BirdoTheme.Motion.easeStandard(), value: vpnVM.isLoadingServers)
        }
        .modifier(HideNavigationBar())
        .overlay(alignment: .bottom) {
            if let notice = upsellNotice {
                upsellSnackbar(notice)
            }
        }
        // Auto-dismiss the upsell notice; .task(id:) cancels cleanly on
        // re-trigger and on screen exit — no dangling Task to tear down.
        .task(id: upsellNotice) {
            guard upsellNotice != nil else { return }
            try? await Task.sleep(for: .seconds(3.2))
            guard !Task.isCancelled else { return }
            withAnimation(BirdoTheme.Motion.accel()) { upsellNotice = nil }
        }
        // Belt-and-braces for S1: entering the screen kicks a load. The 60 s
        // TTL cache in the VM makes revisits free; the app root owns the
        // canonical login/cold-start trigger.
        .task { vpnVM.loadServers() }
    }

    // MARK: - Top bar (spec §4.1 item 1)

    private func topBar(filteredCount: Int) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: BirdoTheme.Spacing.sm) {
                Button(action: { dismiss() }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(BirdoTheme.white05))
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel("Back")

                VStack(alignment: .leading, spacing: 1) {
                    Text("Servers")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                        .accessibilityAddTraits(.isHeader)
                    // Filtered count, not the total — Android parity (§4.1).
                    Text("\(filteredCount) servers")
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: { vpnVM.loadServers(forceRefresh: true) }) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(BirdoTheme.white05))
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel("Refresh")
            }
            .padding(.horizontal, BirdoTheme.Spacing.md)
            .padding(.vertical, 6)
            .frame(minHeight: 56)

            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
        }
        .background(BirdoTheme.glassStrong.ignoresSafeArea(edges: .top))
    }

    // MARK: - Search (spec §4.1 item 2)

    @FocusState private var searchFocused: Bool

    private var searchField: some View {
        HStack(spacing: BirdoTheme.Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 18))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)

            TextField(
                "",
                text: $searchQuery,
                prompt: Text("Search servers…")
                    .font(.system(size: 14))
                    .foregroundStyle(BirdoTheme.white40)
            )
            .font(.system(size: 14))
            .foregroundStyle(.white)
            .tint(BirdoTheme.accentSoft)
            .autocorrectionDisabled()
            .modifier(SearchFieldInput())
            .focused($searchFocused)

            if !searchQuery.isEmpty {
                Button(action: { searchQuery = "" }) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(BirdoTheme.white40)
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 48)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                .fill(BirdoTheme.glassInput)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                .strokeBorder(
                    searchFocused ? BirdoTheme.accentSoft.opacity(0.6) : BirdoTheme.hairlineSoft,
                    lineWidth: 1
                )
        )
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick), value: searchFocused)
        .padding(.horizontal, BirdoTheme.Spacing.lg)
        .padding(.vertical, 10)
    }

    // MARK: - Filter pills (spec §4.1 item 3)

    private var filterPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BirdoTheme.Spacing.sm) {
                ForEach(ServerFilter.allCases, id: \.self) { filter in
                    let isActive = filter == activeFilter
                    Button {
                        Haptics.selectionChanged()
                        withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
                            activeFilter = filter
                        }
                    } label: {
                        Text(pillLabel(for: filter))
                            .font(.system(size: 12, weight: isActive ? .semibold : .medium))
                            .foregroundStyle(isActive ? BirdoTheme.accent : BirdoTheme.onSurfaceMuted)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .frame(minHeight: 40)
                            .background(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .fill(isActive ? BirdoTheme.accentA(0.22) : BirdoTheme.surface)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .strokeBorder(
                                        isActive ? BirdoTheme.accentA(0.55) : BirdoTheme.hairlineSoft,
                                        lineWidth: 1
                                    )
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isActive ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, BirdoTheme.Spacing.lg)
        }
        .padding(.vertical, BirdoTheme.Spacing.xs)
    }

    private func pillLabel(for filter: ServerFilter) -> String {
        // Favorites appends " (N)" when N > 0 — Android parity.
        if filter == .favorites, favoriteCount > 0 {
            return "\(filter.baseLabel) (\(favoriteCount))"
        }
        return filter.baseLabel
    }

    // MARK: - List (spec §4.1 items 4-5)

    private func serverList(_ filtered: [ServerInfo]) -> some View {
        ScrollView {
            LazyVStack(spacing: 6) {
                if vpnVM.isLoadingServers && vpnVM.servers.isEmpty {
                    SkeletonRows()
                } else if filtered.isEmpty {
                    ServersEmptyState(
                        title: emptyTitle,
                        description: emptyDescription,
                        showRetry: vpnVM.servers.isEmpty,
                        onRetry: { vpnVM.loadServers(forceRefresh: true) }
                    )
                } else {
                    ForEach(filtered) { server in
                        ServerRow(
                            server: server,
                            isSelected: server.id == vpnVM.selectedServer?.id,
                            // Connected / Switching bind to the node the tunnel
                            // is ACTUALLY on (captured at connect), never to
                            // selectedServer — otherwise tapping a new server
                            // moved the mint dot before the tunnel switched.
                            isConnected: vpnVM.isConnected
                                && server.id == vpnVM.connectedServerId,
                            isSwitching: vpnVM.isSwitching
                                && server.id == vpnVM.selectedServer?.id,
                            isFavorite: vpnVM.favoriteIds.contains(server.id),
                            // Live switch: tapping a different node while
                            // connected reconnects the tunnel to it rather than
                            // just relabelling the UI.
                            onSelect: { vpnVM.selectServerLive(server) },
                            onToggleFavorite: { vpnVM.toggleFavorite(server.id) },
                            onLockedTap: { showUpsell(for: server) }
                        )
                    }
                }
            }
            .padding(.horizontal, BirdoTheme.Spacing.lg)
            .padding(.vertical, BirdoTheme.Spacing.sm)
        }
        // Real pull-to-refresh. The Android empty-state copy already promises
        // it ("Pull down to refresh or tap retry.") — iOS delivers.
        .refreshable { [vpnVM] in
            await Self.awaitServerRefresh(vpnVM)
        }
    }

    /// Kick a forced reload and hold the refresh spinner until it lands.
    /// `loadServers` flips `isLoadingServers` synchronously before its inner
    /// task runs, so polling it cannot miss the fetch.
    private nonisolated static func awaitServerRefresh(_ vm: VpnViewModel) async {
        await vm.loadServers(forceRefresh: true)
        while await vm.isLoadingServers {
            if Task.isCancelled { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

    // MARK: - Empty state copy (spec §4.1 item 5; strings verbatim)

    private var emptyTitle: String {
        if activeFilter == .favorites { return "No favorite servers yet" }
        if !trimmedQuery.isEmpty { return "No servers match \"\(trimmedQuery)\"" }
        switch activeFilter {
        case .highSpeed:      return "No high-speed servers right now"
        case .portForwarding: return "No port-forwarding servers right now"
        default:              return "No servers available"
        }
    }

    private var emptyDescription: String? {
        if activeFilter == .favorites { return "Tap the ★ on any server to add it" }
        if vpnVM.servers.isEmpty { return "Pull down to refresh or tap retry." }
        return nil
    }

    // MARK: - Locked-node upsell

    private func showUpsell(for server: ServerInfo) {
        Haptics.notify(.error)
        let message = "This server requires the \(server.minPlan.uppercased()) plan. Upgrade to unlock."
        withAnimation(BirdoTheme.Motion.decel()) { upsellNotice = message }
        AccessibilityNotification.Announcement(message).post()
    }

    private func upsellSnackbar(_ notice: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "lock.fill")
                .font(.system(size: 14))
                .foregroundStyle(BirdoTheme.accentSoft)
            Text(notice)
                .font(.system(size: 13))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .fill(BirdoTheme.surfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
        )
        .padding(.horizontal, BirdoTheme.Spacing.lg)
        .padding(.bottom, BirdoTheme.Spacing.md)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

// MARK: - Server row (spec §4.4)

private struct ServerRow: View {
    let server: ServerInfo
    let isSelected: Bool
    /// Tunnel is up AND this is the active node — small mint dot by the name.
    let isConnected: Bool
    /// A live switch to this node is in flight — shows "Switching…" instead of
    /// the connected dot, so the row never claims Protected mid-switch.
    let isSwitching: Bool
    let isFavorite: Bool
    let onSelect: () -> Void
    let onToggleFavorite: () -> Void
    let onLockedTap: () -> Void

    /// A node the current plan can't reach is shown locked — selecting it
    /// would only earn a server-side refusal. `accessible` is server-computed
    /// and authoritative; the client never derives access from minPlan itself.
    private var isLocked: Bool { !server.accessible }
    private var isSelectable: Bool { server.isOnline && !isLocked }

    var body: some View {
        HStack(spacing: 0) {
            // Flag badge
            Text(server.flag)
                .font(.system(size: 20))
                .frame(width: 40, height: 40)
                .background(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                        .fill(BirdoTheme.surfaceRaised)
                )
                .accessibilityHidden(true)

            Spacer().frame(width: 12)

            // Name + location
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(server.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(isSelectable ? Color.white : BirdoTheme.onSurfaceFaint)
                        .lineLimit(1)

                    if isSwitching {
                        Text("Switching…")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(BirdoTheme.accentSoft)
                            .accessibilityLabel("Switching to this server")
                    } else if isConnected {
                        Circle()
                            .fill(BirdoTheme.green)
                            .frame(width: 6, height: 6)
                            .accessibilityLabel("Connected")
                    }

                    if isLocked {
                        // The badge names the required plan and carries the
                        // "Locked — requires the X plan" a11y label itself.
                        PlanBadge(server.minPlan, locked: true)
                            .fixedSize()
                    }
                }

                HStack(spacing: 6) {
                    Text(locationText)
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                        .lineLimit(1)

                    if server.isHighSpeed {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(BirdoTheme.onSurfaceFaint)
                            .accessibilityLabel("High-speed server")
                    }
                    if server.isPortForwarding {
                        Image(systemName: "arrow.left.arrow.right")
                            .font(.system(size: 10))
                            .foregroundStyle(BirdoTheme.onSurfaceFaint)
                            .accessibilityLabel("Supports port forwarding")
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer().frame(width: 8)

            // Load readout — or offline marker: a stale load % on a downed
            // node is noise, so the column says what actually matters.
            if server.isOnline {
                VStack(alignment: .trailing, spacing: 3) {
                    Text("\(server.load)%")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(LoadBar.color(for: Int(server.load)))
                    LoadBar(load: Int(server.load))
                }
            } else {
                Text("Offline")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(BirdoTheme.white40)
            }

            Spacer().frame(width: 6)

            // Favorite star (48pt hit target, 36pt visual)
            Button {
                Haptics.selectionChanged()
                onToggleFavorite()
            } label: {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .font(.system(size: 18))
                    .foregroundStyle(isFavorite ? BirdoTheme.yellowLight : BirdoTheme.onSurfaceFaint)
                    .symbolEffect(.bounce, value: isFavorite)
                    .frame(width: 36, height: 36)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isFavorite ? "Remove from favorites" : "Add to favorites")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .fill(isSelected ? BirdoTheme.surfaceRaised : BirdoTheme.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                .strokeBorder(
                    isSelected ? BirdoTheme.accentSoft : BirdoTheme.hairlineSoft,
                    lineWidth: isSelected ? 1.5 : 1
                )
        )
        // 50%-alpha whole row when locked/offline (star stays tappable —
        // favoriting an out-of-plan node is allowed, matching Android).
        .opacity(isSelectable ? 1.0 : 0.5)
        .contentShape(RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous))
        .onTapGesture {
            if isSelectable {
                Haptics.selectionChanged()
                onSelect()
            } else if isLocked {
                onLockedTap()
            }
        }
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick), value: isSelected)
        .accessibilityAddTraits(isSelectable ? .isButton : [])
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var locationText: String {
        server.city.isEmpty ? server.country : "\(server.city), \(server.country)"
    }
}

// MARK: - First-load skeleton (spec §4.1 item 5)

private struct SkeletonRows: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        VStack(spacing: 6) {
            ForEach(0..<6, id: \.self) { _ in
                GhostRow()
            }
        }
        // One shared pulse so the ghosts breathe in unison (0.4 → 0.9, 900 ms).
        .opacity(reduceMotion ? 0.65 : (pulse ? 0.9 : 0.4))
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading servers…")
    }
}

private struct GhostRow: View {
    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
                .frame(width: 44, height: 44)

            // Explicit height: a GeometryReader in a scroll-driven row gets a
            // nil height proposal and would collapse to its ideal size.
            GeometryReader { geo in
                VStack(alignment: .leading, spacing: 8) {
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(BirdoTheme.surfaceRaised)
                        .frame(width: geo.size.width * 0.55, height: 14)
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(BirdoTheme.surfaceRaised)
                        .frame(width: geo.size.width * 0.35, height: 11)
                }
            }
            .frame(height: 33)

            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
                .frame(width: 36, height: 12)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .fill(BirdoTheme.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .strokeBorder(BirdoTheme.Gradients.glassStroke, lineWidth: 1)
        )
    }
}

// MARK: - Empty state (spec §4.1 item 5; BirdoEmptyState port)

private struct ServersEmptyState: View {
    let title: String
    let description: String?
    let showRetry: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle()
                    .fill(BirdoTheme.surfaceRaised)
                    .frame(width: 64, height: 64)
                Image(systemName: "server.rack")
                    .font(.system(size: 28))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
            }
            .accessibilityHidden(true)

            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.top, BirdoTheme.Spacing.lg)

            if let description {
                Text(description)
                    .font(.system(size: 13))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
            }

            if showRetry {
                PrimaryButton("Retry", variant: .secondary, icon: "arrow.clockwise", height: 48) {
                    onRetry()
                }
                .frame(maxWidth: 200)
                .padding(.top, BirdoTheme.Spacing.xl)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

// MARK: - Thin indeterminate refresh bar (spec §4.1 item 4)

private struct ThinRefreshBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var slide = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(BirdoTheme.white05)
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(BirdoTheme.greenLight)
                    .frame(width: geo.size.width * 0.3)
                    .offset(x: slide ? geo.size.width : -geo.size.width * 0.3)
            }
        }
        .frame(height: 3)
        .clipped()
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) {
                slide = true
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Model

/// Lightweight server representation for the iOS app.
struct ServerInfo: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let country: String
    let countryCode: String
    let city: String
    let load: Int32
    /// Minimum plan required for this node: RECON | OPERATIVE | SOVEREIGN.
    let minPlan: String
    /// Server-computed: does THIS user's plan reach `minPlan`? The backend is
    /// the only authority on access — the client just renders it.
    let accessible: Bool
    let isPremium: Bool
    /// Low-load / high-throughput node. NOT a streaming-unblocking claim.
    let isHighSpeed: Bool
    /// Node supports inbound port forwarding (see PortForwardView).
    let isPortForwarding: Bool
    let isOnline: Bool

    /// Regional-indicator flag for `countryCode`. Computed, never stored, so
    /// decoding is unaffected; falls back to 🌐 on a missing/malformed code.
    var flag: String {
        let code = countryCode.uppercased()
        guard code.count == 2, code.allSatisfy({ $0.isASCII && $0.isLetter }) else { return "🌐" }
        var flag = ""
        for scalar in code.unicodeScalars {
            guard let regional = Unicode.Scalar(0x1F1E6 + scalar.value - Unicode.Scalar("A").value) else {
                return "🌐"
            }
            flag.unicodeScalars.append(regional)
        }
        return flag
    }

    /// Hand-rolled so a missing key degrades to a safe default instead of
    /// throwing and blanking the WHOLE server list. Swift's synthesized
    /// `Decodable` ignores property default values, so this has to be explicit.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? ""
        countryCode = try c.decodeIfPresent(String.self, forKey: .countryCode) ?? ""
        city = try c.decodeIfPresent(String.self, forKey: .city) ?? ""
        load = try c.decodeIfPresent(Int32.self, forKey: .load) ?? 0
        minPlan = try c.decodeIfPresent(String.self, forKey: .minPlan) ?? "RECON"
        // Default true: an older backend that doesn't emit the field must not
        // lock the user out of every node client-side.
        accessible = try c.decodeIfPresent(Bool.self, forKey: .accessible) ?? true
        isPremium = try c.decodeIfPresent(Bool.self, forKey: .isPremium) ?? false
        isHighSpeed = try c.decodeIfPresent(Bool.self, forKey: .isHighSpeed) ?? false
        isPortForwarding = try c.decodeIfPresent(Bool.self, forKey: .isPortForwarding) ?? false
        isOnline = try c.decodeIfPresent(Bool.self, forKey: .isOnline) ?? true
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, country, countryCode, city, load
        case minPlan, accessible, isPremium, isHighSpeed, isPortForwarding, isOnline
        // DEPRECATED keys `isStreaming` / `isP2p` are still emitted by the
        // backend as hard-coded `false` for already-shipped clients. They are
        // deliberately NOT decoded here: nothing may read them again.
    }
}
