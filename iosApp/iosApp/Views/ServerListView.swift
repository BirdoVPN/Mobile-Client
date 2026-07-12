import SwiftUI
import BirdoShared

/// Server browser with search, filters, and favorites.
struct ServerListView: View {
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var searchQuery = ""
    @State private var activeFilter: ServerFilter = .all

    enum ServerFilter: String, CaseIterable {
        case all       = "All"
        case favorites = "⭐ Favorites"
        case streaming = "📺 Streaming"
        case p2p       = "📁 P2P"
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
                    case .all:       return true
                    case .favorites: return vpnVM.favoriteIds.contains(server.id)
                    case .streaming: return server.isStreaming
                    case .p2p:       return server.isP2p
                    }
                }()

                return matchesSearch && matchesFilter
            }
            .sorted {
                let aFav = vpnVM.favoriteIds.contains($0.id)
                let bFav = vpnVM.favoriteIds.contains($1.id)
                if aFav != bFav { return aFav }
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
                        .foregroundColor(server.isOnline ? .white : BirdoTheme.white40)

                    if server.isPremium {
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
                .fill(loadColor(Int(server.load)))
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
        .onTapGesture(perform: onSelect)
        .opacity(server.isOnline ? 1.0 : 0.5)
    }

    private func loadColor(_ load: Int) -> Color {
        if load < 50 { return BirdoTheme.green }
        if load < 80 { return BirdoTheme.yellow }
        return BirdoTheme.red
    }
}
