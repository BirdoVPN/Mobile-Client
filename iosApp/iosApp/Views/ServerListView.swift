import SwiftUI
import BirdoShared

/// Server browser with search, filters, and favorites.
struct ServerListView: View {
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var searchQuery = ""
    @State private var activeFilter: ServerFilter = .all

    /// The old "Streaming" / "P2P" pills matched NOTHING in production — both
    /// flags were cosmetic and false on every node. They are replaced by the
    /// two capabilities Birdo actually ships: a low-load / high-throughput
    /// node, and inbound port forwarding. Birdo offers no streaming-unblocking
    /// and no P2P servers, so no pill may imply otherwise.
    enum ServerFilter: String, CaseIterable {
        case all            = "All"
        case favorites      = "⭐ Favorites"
        case highSpeed      = "⚡ High-Speed"
        case portForwarding = "⇄ Port Forwarding"
    }

    private var filteredServers: [ServerInfo] {
        vpnVM.servers
            .filter { server in
                let matchesSearch = searchQuery.isEmpty
                    || server.name.localizedCaseInsensitiveContains(searchQuery)
                    || server.country.localizedCaseInsensitiveContains(searchQuery)
                    || server.city.localizedCaseInsensitiveContains(searchQuery)

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
        VStack(spacing: 0) {
            // Search bar
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(BirdoTheme.white40)
                    .font(.subheadline)

                TextField("Search servers…", text: $searchQuery)
                    .foregroundColor(.white)
                    .autocapitalization(.none)

                if !searchQuery.isEmpty {
                    Button(action: { searchQuery = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(BirdoTheme.white40)
                    }
                }
            }
            .padding(12)
            .background(BirdoTheme.glassInput)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(BirdoTheme.white10, lineWidth: 1))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Filter pills
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ServerFilter.allCases, id: \.self) { filter in
                        let isActive = filter == activeFilter
                        Button(action: { activeFilter = filter }) {
                            Text(filter.rawValue)
                                .font(.caption)
                                .fontWeight(isActive ? .medium : .regular)
                                .foregroundColor(isActive ? .white : BirdoTheme.white60)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 7)
                                .background(isActive ? BirdoTheme.white10 : .clear)
                                .clipShape(Capsule())
                                .overlay(
                                    isActive ? Capsule().stroke(BirdoTheme.white20, lineWidth: 1) : nil
                                )
                        }
                    }
                }
                .padding(.horizontal, 16)
            }

            // Server count
            Text("\(filteredServers.count) servers")
                .font(.caption2)
                .foregroundColor(BirdoTheme.white20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 4)

            if vpnVM.isLoadingServers {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding()
            }

            // Server list
            List {
                ForEach(filteredServers) { server in
                    ServerRow(
                        server: server,
                        isSelected: server.id == vpnVM.selectedServer?.id,
                        isFavorite: vpnVM.favoriteIds.contains(server.id),
                        onSelect: { vpnVM.selectServer(server) },
                        onToggleFavorite: { vpnVM.toggleFavorite(server.id) }
                    )
                    .listRowBackground(BirdoTheme.black)
                    .listRowSeparator(.hidden)
                }

                if filteredServers.isEmpty && !vpnVM.isLoadingServers {
                    VStack(spacing: 12) {
                        Image(systemName: "server.rack")
                            .font(.largeTitle)
                            .foregroundColor(BirdoTheme.white20)
                        Text(activeFilter == .favorites ? "No favorites yet" : "No servers found")
                            .foregroundColor(BirdoTheme.white40)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .background(BirdoTheme.black)
        .navigationTitle("Servers")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { vpnVM.loadServers(forceRefresh: true) }) {
                    Image(systemName: "arrow.clockwise")
                        .foregroundColor(BirdoTheme.white40)
                }
            }
        }
    }
}

// MARK: - Server Row

private struct ServerRow: View {
    let server: ServerInfo
    let isSelected: Bool
    let isFavorite: Bool
    let onSelect: () -> Void
    let onToggleFavorite: () -> Void

    /// A node the current plan can't reach is shown locked and inert — offering
    /// it would only earn a server-side refusal.
    private var isLocked: Bool { !server.accessible }
    private var isSelectable: Bool { server.isOnline && !isLocked }

    var body: some View {
        HStack(spacing: 12) {
            // Flag
            Text(flagEmoji(server.countryCode))
                .font(.title3)
                .frame(width: 36, height: 36)
                .background(BirdoTheme.white10)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            // Info
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(server.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(isSelectable ? .white : BirdoTheme.white40)

                    if isLocked {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundColor(BirdoTheme.white40)
                            .accessibilityLabel("Locked — requires the \(server.minPlan) plan")
                    } else if server.isPremium {
                        Image(systemName: "crown.fill")
                            .font(.caption2)
                            .foregroundColor(BirdoTheme.yellow)
                    }
                }

                Text("\(server.city.isEmpty ? server.country : server.city) · \(server.load)% load")
                    .font(.caption)
                    .foregroundColor(BirdoTheme.white40)
            }

            Spacer()

            // Load indicator
            Circle()
                .fill(loadColor(server.load))
                .frame(width: 8, height: 8)

            // Favorite
            Button(action: onToggleFavorite) {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .foregroundColor(isFavorite ? BirdoTheme.yellow : BirdoTheme.white20)
                    .font(.subheadline)
            }
            .buttonStyle(.plain)

            // Selection
            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(BirdoTheme.green)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(isSelected ? BirdoTheme.white05 : .clear)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture { if isSelectable { onSelect() } }
        .opacity(isSelectable ? 1.0 : 0.5)
    }

    private func loadColor(_ load: Int32) -> Color {
        if load < 50 { return BirdoTheme.green }
        if load < 80 { return BirdoTheme.yellow }
        return BirdoTheme.red
    }
}

/// Lightweight server representation for the iOS app.
struct ServerInfo: Identifiable, Decodable {
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
        return flagEmoji(code)
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
