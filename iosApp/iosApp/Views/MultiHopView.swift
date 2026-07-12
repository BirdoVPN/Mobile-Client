import SwiftUI

/// Multi-hop (double VPN) screen — entry and exit server selection.
struct MultiHopView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var entryExpanded = false
    @State private var exitExpanded = false
    @State private var selectedEntry: ServerInfo?
    @State private var selectedExit: ServerInfo?

    private var sameServerWarning: Bool {
        guard let e = selectedEntry, let x = selectedExit else { return false }
        return e.id == x.id
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Header info
                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.branch")
                        .font(.title3)
                        .foregroundColor(BirdoTheme.accent)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Double VPN")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Route traffic through two servers for extra privacy")
                            .font(.caption)
                            .foregroundColor(BirdoTheme.white60)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(BirdoTheme.surface)
                .cornerRadius(16)

                // Entry server
                serverSelector(
                    label: "Entry Server",
                    icon: "1.circle.fill",
                    server: selectedEntry,
                    expanded: $entryExpanded,
                    onSelect: { selectedEntry = $0 }
                )

                // Swap indicator
                Image(systemName: "arrow.up.arrow.down.circle.fill")
                    .font(.title2)
                    .foregroundColor(BirdoTheme.white40)

                // Exit server
                serverSelector(
                    label: "Exit Server",
                    icon: "2.circle.fill",
                    server: selectedExit,
                    expanded: $exitExpanded,
                    onSelect: { selectedExit = $0 }
                )

                // Same-server warning
                if sameServerWarning {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(BirdoTheme.yellow)
                        Text("Entry and exit servers must be different.")
                            .font(.caption)
                            .foregroundColor(BirdoTheme.yellow)
                    }
                    .padding()
                    .background(BirdoTheme.yellow.opacity(0.1))
                    .cornerRadius(12)
                }

                // Connect button
                Button {
                    guard !sameServerWarning,
                          let entry = selectedEntry,
                          let exit = selectedExit else { return }
                    vpnVM.connectMultiHop(entryId: entry.id, exitId: exit.id)
                    dismiss()
                } label: {
                    Text("Connect Multi-Hop")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            (selectedEntry != nil && selectedExit != nil && !sameServerWarning)
                                ? BirdoTheme.accent
                                : BirdoTheme.white10
                        )
                        .cornerRadius(14)
                }
                .disabled(selectedEntry == nil || selectedExit == nil || sameServerWarning)
            }
            .padding()
        }
        .background(BirdoTheme.black.ignoresSafeArea())
        .navigationTitle("Multi-Hop")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Server Selector

    @ViewBuilder
    private func serverSelector(
        label: String,
        icon: String,
        server: ServerInfo?,
        expanded: Binding<Bool>,
        onSelect: @escaping (ServerInfo) -> Void
    ) -> some View {
        VStack(spacing: 0) {
            // Header
            Button {
                withAnimation(.easeInOut(duration: 0.25)) {
                    expanded.wrappedValue.toggle()
                }
            } label: {
                HStack {
                    Image(systemName: icon)
                        .foregroundColor(BirdoTheme.accent)
                    Text(label)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BirdoTheme.white80)
                    Spacer()
                    if let srv = server {
                        Text("\(srv.flag) \(srv.name)")
                            .font(.subheadline)
                            .foregroundColor(.white)
                    } else {
                        Text("Select")
                            .font(.subheadline)
                            .foregroundColor(BirdoTheme.white40)
                    }
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white40)
                }
                .padding()
                .background(BirdoTheme.surface)
                .cornerRadius(expanded.wrappedValue ? 0 : 14)
            }

            // Expandable list
            if expanded.wrappedValue {
                VStack(spacing: 0) {
                    ForEach(vpnVM.servers) { srv in
                        Button {
                            onSelect(srv)
                            withAnimation { expanded.wrappedValue = false }
                        } label: {
                            HStack {
                                Text(srv.flag)
                                    .font(.title3)
                                Text(srv.name)
                                    .font(.subheadline)
                                    .foregroundColor(.white)
                                Spacer()
                                if server?.id == srv.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(BirdoTheme.green)
                                }
                            }
                            .padding(.horizontal)
                            .padding(.vertical, 10)
                        }
                        Divider().background(BirdoTheme.border)
                    }
                }
                .background(BirdoTheme.card)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}
